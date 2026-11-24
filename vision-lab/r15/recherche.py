"""Collecte séquentielle d’octets publics et de leurs notices primaires, avec cache local."""
from hashlib import sha256
from pathlib import Path
import html
import json
import re
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path('data/r15')
NOTICES = ROOT/'notices'
MEDIA = ROOT/'media'
UA = 'LMArcheryVision/1.0 (dataset research; https://github.com/LounaMaili/LMarchery)'
COMMONS = 'https://commons.wikimedia.org/w/api.php'


def save(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False)+'\n')


def read(path):
    return json.loads(Path(path).read_bytes())


def fetch(url, path, limit=50_000_000):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with urlopen(Request(url, headers={'User-Agent':UA}), timeout=30) as response:
            blob=response.read(limit+1)
            if len(blob)>limit:
                raise ValueError('Taille limite dépassée')
        path.write_bytes(blob)
    return path.read_bytes()


def plain(text):
    return ' '.join(html.unescape(re.sub('<[^>]+>',' ',str(text))).split())


def normalized(s):
    return re.sub(r'[^a-z0-9]','',s.casefold())


def exclusions():
    """Comparer des identités dans les registres. Aucun accès aux pixels des tests."""
    from r14.collecter import identities
    paths=[Path(p) for p in ('public-sources.json','r2-sources.json','r3/real-sources.json','r4/public-sources.json',
                             'r5/public-candidates.json','r9/sources.json','r12/nouvelles.json','r13/collecte.json',
                             'r14/partitions.json')]
    for n,name in ((7,'commons-source.json'),(7,'external-exclusions.json'),(8,'inventory.json'),(9,'sources.json')):
        paths.append(Path(f'../../vision-lab-r{n}/vision-lab/data/r{n}')/name)
    values=set()
    for p in paths:
        values.update(normalized(v) for v in identities(read(p)) if v)
    return values


def commons():
    categories=['Archery targets','World Archery targets','Mato (target)','Arrows in targets',
                'Clout archery','Field archery','Videos of archery']
    queries=['intitle:archery target','intitle:archery arrows','intitle:Zielscheibe Pfeile','intitle:cible arc','intitle:archery filetype:video']
    ids=set()
    failures=[]
    for i,(kind,term) in enumerate([('categorymembers',x) for x in categories]+[('search',x) for x in queries]):
        if kind=='categorymembers':
            args=dict(cmnamespace=6,cmtitle='Category:'+term,cmlimit=150)
        else:
            args=dict(srnamespace=6,srsearch=term,srlimit=90)
        url=COMMONS+'?'+urlencode(dict(action='query',list=kind,format='json',**args))
        try:
            result=json.loads(fetch(url,NOTICES/f'commons-list-{i}.json'))
            ids.update(r['pageid'] for r in result['query'][kind])
        except (OSError,KeyError) as err:
            failures.append([url,str(err)])
    old=exclusions()
    rows=[]
    for start in range(0,len(ids),25):
        subset=sorted(ids)[start:start+25]
        url=COMMONS+'?'+urlencode(dict(action='query',pageids='|'.join(map(str,subset)),
            prop='imageinfo',iiprop='url|size|mime|sha1|extmetadata',format='json'))
        try:
            pages=json.loads(fetch(url,NOTICES/f'commons-info-{start}.json'))['query']['pages']
        except (OSError,KeyError) as err:
            failures.append([url,str(err)])
            continue
        for p in pages.values():
            info=p.get('imageinfo',[{}])[0]
            meta={k:plain(v.get('value','')) for k,v in info.get('extmetadata',{}).items()}
            k=str(p['pageid'])
            credit=meta.get('Artist','')
            terms=meta.get('LicenseUrl','')
            if any(normalized(v) in old for v in [k,'wc'+k,credit,p['title'],info.get('descriptionurl','')] if v):
                continue
            if not credit or info.get('mime') not in ('image/jpeg','image/png','video/webm','video/ogg'):
                continue
            rows.append(dict(id='wc'+k,title=p['title'],credit=credit,licence=meta.get('LicenseShortName',''),
                licence_url=terms,source=meta.get('Credit',''),description=meta.get('ImageDescription','')[:900],
                url=info['descriptionurl'],pixels=info['url'],dimensions=[info['width'],info['height']],
                octets=info['size'],mime=info['mime'],sha1=info['sha1'],notice=info['descriptionurl']))
    save(ROOT/'commons-catalogue.json',dict(categories=categories,recherches=queries,echecs=failures,
                                             ids_consultes=len(ids),candidats=rows))


def roboflow():
    examples=[]
    for key in ('rf-empty','rf-key'):
        project = {'rf-key':'archery-scoring/archery-scoring-keypoints','rf-empty':'arrowcounting/arrow_counting'}[key]
        data=fetch('https://universe.roboflow.com/'+project,NOTICES/key).decode()
        objects=[json.JSONDecoder().raw_decode(data[m.start():])[0] for m in
                 re.finditer(re.escape('{"@context":"https://schema.org","@type":"ImageObject"'),data)]
        if not objects:
            raise ValueError(key)
        seen={}
        for obj in objects:
            seen[obj['contentUrl']]=obj
        for n,(url,obj) in enumerate(seen.items()):
            uid=url.split('/')[-2]
            ident=key+'-'+uid
            row=dict(id=ident,project=key,label=obj.get('description'),licence=obj.get('license'),
                     credit=obj.get('creator',{}).get('name'),pixels=url)
            if row['licence']=='https://creativecommons.org/licenses/by/4.0/':
                try:
                    data=fetch(url,MEDIA/(ident+'.jpg'))
                    row.update(octets=len(data),sha256=sha256(data).hexdigest())
                except OSError as err:
                    row['erreur']=str(err)
            examples.append(row)
    save(ROOT/'rf-exemples.json',examples)


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('catalogue',choices=('commons','roboflow'))
    args=p.parse_args()
    (commons if args.catalogue=='commons' else roboflow)()
