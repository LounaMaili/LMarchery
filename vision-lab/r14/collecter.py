"""Collecte documentaire Flickr. Exclure les anciens catalogues avant les pixels."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

QUERIES = ["pfeile zielscheibe treffer", "pijlen blazoen", "flechas en la diana",
           "kyudo mato arrows target", "archery arrows Portsmouth round",
           "archery face three spot arrows", "arrows in straw boss archery",
           "tir à l'arc flèches groupées", "archery target worn face holes"]
PAGES = [(q,1) for q in QUERIES] + [('archery target', page) for page in (16,18,20,22)] + [
    ('arrows target',10), ('arrows target',12), ('archery practice',7), ('archery practice',9)]
MARKER = '{"_flickrModelRegistry":"photo-lite-models",'


def identities(value):
    if isinstance(value, list):
        for item in value:
            yield from identities(item)
    if isinstance(value, dict):
        for key, item in value.items():
            if isinstance(item, str) and key in {
                'id','credit','author','group','provenance','creator','source_group','url',
                'author_url','creator_url','source_url','foreign_landing_url','owner','ownerNsid'
            }:
                yield item
                if '/photos/' in item:
                    yield item.split('/photos/')[1].split('/')[0]
            elif isinstance(item, (dict, list)):
                yield from identities(item)


def normalized(value):
    return re.sub(r'[^a-z0-9]', '', str(value).lower())


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url, path):
    if not path.exists():
        request = Request(url, headers={'User-Agent':'LMArchery-Vision/1.0 (research; github.com/LounaMaili/LMarchery)'})
        with urlopen(request, timeout=15) as response:
            path.write_bytes(response.read())
    return path.read_bytes()


def collect(out, worktrees):
    old = set()
    for step, patterns in {7:['commons-*.json','external-exclusions.json'],8:['*verified.json','inventory.json'],
                           9:['*verified.json','sources.json'],12:['*verified.json'],13:['*verified.json']}.items():
        root = worktrees/f'vision-lab-r{step}'/'vision-lab/data'/f'r{step}'
        if not root.is_dir():
            raise FileNotFoundError(root)
        for pattern in patterns:
            for path in root.glob(pattern):
                old.update(map(normalized, identities(json.loads(path.read_bytes()))))
    for file in ('public-sources.json','r2-sources.json','r3/real-sources.json','r4/public-sources.json',
                 'r5/public-candidates.json','r9/sources.json','r12/nouvelles.json','r13/collecte.json'):
        old.update(map(normalized, identities(json.loads(Path(file).read_bytes()))))
    media = out/'media'
    media.mkdir(parents=True, exist_ok=True)
    rows, errors, seen = [], [], set()
    for i, (query,page) in enumerate(PAGES):
        url = 'https://www.flickr.com/search/?'+urlencode(dict(text=query, page=page, license='4,5,9,10'))
        try:
            data = fetch(url,out/f'q{i}.html').decode()
        except OSError as err:
            errors.append([i,str(err)])
            continue
        for match in re.finditer(re.escape(MARKER),data):
            p=json.JSONDecoder().raw_decode(data[match.start():])[0]
            key=str(p['id'])
            aliases=[key,p.get('username'),p.get('realname'),p.get('ownerNsid'),p.get('pathAlias')]
            if key in seen or any(normalized(v) in old for v in aliases if v):
                continue
            if p.get('license') not in (4,5,9,10) or len(rows)>=64:
                continue
            seen.add(key)
            author=p.get('pathAlias') or p['ownerNsid']
            source=f'https://www.flickr.com/photos/{author}/{key}'
            try:
                notice=fetch(source,media/(key+'.html'))
                terms=re.search(rb'"license":\s*"(https://creativecommons.org/[^"]+)"',notice)
                img=re.search(rb'<meta property="og:image" content="([^"]+)"',notice)
                if not terms or not img:
                    continue
                lic=terms[1].decode()
                pixels=html.unescape(img[1].decode())
                if not lic.startswith(('https://creativecommons.org/licenses/by/','https://creativecommons.org/licenses/by-sa/','https://creativecommons.org/publicdomain/zero/')) or urlparse(pixels).hostname!='live.staticflickr.com' or key+'_' not in pixels:
                    continue
                blob=fetch(pixels,media/(key+'.jpg'))
                rows.append(dict(id=key,source=source,credit=p['username'],terms=lic,pixels_url=pixels,
                                 sha256=digest(blob),octets=len(blob),notice_sha256=digest(notice),requete=i))
            except OSError as err:
                errors.append([key,str(err)])
        print(i,len(rows),flush=True)
    (out/'candidats.json').write_text(json.dumps(dict(requetes=PAGES,erreurs=errors,photos=rows),indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('worktrees',type=Path)
    args=parser.parse_args()
    collect(args.out,args.worktrees)
