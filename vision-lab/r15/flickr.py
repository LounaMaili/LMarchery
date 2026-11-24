"""Recherche documentaire sur des tris et requêtes Flickr complémentaires."""
from hashlib import sha256
import html
import json
import re
from urllib.parse import urlencode,urlparse
from r14.collecter import MARKER
from r15.recherche import ROOT, NOTICES, MEDIA, fetch, save, exclusions, normalized

SEARCHES=[('archery target','date-posted-asc',1),('archery target','date-posted-asc',2),
          ('archery target','interestingness-desc',2),('archery target','date-posted-desc',2),
          ('archery target face','interestingness-desc',1),('archery holes','relevance',1),
          ('archery target paper','date-posted-asc',1),('archery bullseye','date-posted-asc',1),
          ('archery arrows','interestingness-desc',3),('archery boss','relevance',1),
          ('tir arc cible','date-posted-asc',1),('Bogenscheibe','interestingness-desc',1),
          ('Portsmouth archery target','relevance',1),('target face archery','date-posted-desc',1)]


def collect():
    blocked=exclusions()
    ids={}
    errors=[]
    for i,(q,sort,page) in enumerate(SEARCHES):
        url='https://www.flickr.com/search/?'+urlencode(dict(text=q,sort=sort,page=page,license='4,5,9,10'))
        try:
            raw=fetch(url,NOTICES/f'flickr-query-{i}.html').decode()
        except OSError as err:
            errors.append([i,str(err)])
            continue
        for match in re.finditer(re.escape(MARKER),raw):
            obj=json.JSONDecoder().raw_decode(raw[match.start():])[0]
            key=str(obj['id'])
            aliases=[key,obj.get('pathAlias'),obj.get('username'),obj.get('realname'),obj.get('ownerNsid')]
            if obj.get('license') not in (4,5,9,10) or any(normalized(v) in blocked for v in aliases if v):
                continue
            ids.setdefault(key,(obj,i))
    print('Nouvelles notices candidates',len(ids),flush=True)
    def priority(item):
        title=item[1][0].get('title','').lower()
        return (not any(t in title for t in ('target','bull','archery','cible','scheibe','arrow','score')),item[0])
    rows=[]
    for key,(obj,i) in sorted(ids.items(),key=priority)[:170]:
        owner=obj.get('pathAlias') or obj['ownerNsid']
        url=f'https://www.flickr.com/photos/{owner}/{key}'
        try:
            detail=fetch(url,NOTICES/('fl'+key+'.html'))
            match=re.search(rb'"license":\s*"(https://creativecommons.org/[^"]+)"',detail)
            image=re.search(rb'<meta property="og:image" content="([^"]+)"',detail)
            if not (match and image):
                continue
            terms=match[1].decode()
            pixels=html.unescape(image[1].decode())
            if not terms.startswith(('https://creativecommons.org/licenses/by/','https://creativecommons.org/licenses/by-sa/',
                                     'https://creativecommons.org/publicdomain/zero/')) or urlparse(pixels).hostname!='live.staticflickr.com' or key+'_' not in pixels:
                continue
            data=fetch(pixels,MEDIA/('fl'+key+'.jpg'))
            rows.append(dict(id='fl'+key,credit=obj['username'],compte=owner,url=url,title=obj.get('title',''),pixels=pixels,
                       licence_url=terms,sha256=sha256(data).hexdigest(),octets=len(data),
                       notice_sha256=sha256(detail).hexdigest(),requete=i,original_etabli=False))
            if len(rows)%15==0:
                print('Téléchargées',len(rows),flush=True)
            save(ROOT/'flickr-images.json',rows)
        except OSError as err:
            errors.append([key,str(err)])
    save(ROOT/'flickr-bilan.json',dict(recherches=SEARCHES,notices_candidates=len(ids),examinees=min(170,len(ids)),
                                       images=len(rows),echecs=errors))

if __name__=='__main__':
    collect()
