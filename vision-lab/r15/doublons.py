"""Comparer les médias examinés et les 185 acquisitions publiques R14 autorisées."""
from collections import Counter
from hashlib import sha256
from itertools import combinations
from pathlib import Path

import cv2 as cv
from r15.recherche import ROOT, MEDIA, read, save


def dhash(path):
    gray = cv.imread(str(path), cv.IMREAD_GRAYSCALE)
    if gray is None:
        raise ValueError(path)
    small = cv.resize(gray, (9, 8), interpolation=cv.INTER_AREA)
    bits = (small[:, 1:] > small[:, :-1]).ravel()
    return int(''.join('1' if b else '0' for b in bits), 2)


def main():
    known = {r['sha256_pixels']:r['id'] for r in read('r14/partitions.json')['photos']}
    alts = {r['id']:r for r in read('r15/lectures.json')['photos']}
    rows = []
    def add(key, cat, path, digest=None):
        actual = sha256(path.read_bytes()).hexdigest()
        if digest is not None and actual != digest:
            raise ValueError(key)
        rows.append((key,cat,actual,dhash(path)))
    for r in read('r14/inventaire-pixels.json'):
        add(known[r['sha256']], 'R14', Path(r['emplacement']), r['sha256'])
    for name,cat in [('rf-exemples',''),('commons-images','commons'),('flickr-images','flickr')]:
        for r in read(ROOT / (name+'.json')):
            if 'sha256' in r and not r.get('mime','').startswith('video/'):
                add(r['id'],cat or r['project'],MEDIA / (r.get('fichier') or r['id']+'.jpg'),r['sha256'])
    for p in sorted(MEDIA.glob('shot-*.jpg')):
        add(p.stem,'shotplot',p)
    for k,r in alts.items():
        if 'instant_s' in r['source']:
            add(k,'video',MEDIA / r['source']['fichier_travail'],r['sha256_pixels'])
    counts = Counter()
    alerts = []
    nearest = {}
    for a,b in combinations(rows,2):
        if a[1]==b[1]=='R14':
            continue
        cats = ' × '.join(sorted([a[1],b[1]]))
        d = (a[3]^b[3]).bit_count()
        counts[(cats,'paires')] += 1
        if a[2]==b[2]:
            counts[(cats,'exactes')] += 1
        if d<=4:
            counts[(cats,'alertes_dhash')] += 1
            alerts.append(dict(a=a[0],b=b[0],distance=d,exact=a[2]==b[2]))
        for item,other in ((a,b),(b,a)):
            if item[0] in alts and (other[1]=='R14' or other[0] in alts):
                group='R14' if other[1]=='R14' else 'autres_admissions'
                previous=nearest.get((item[0],group), (65,''))
                nearest[item[0],group]=min(previous,(d,other[0]))
    save(ROOT/'alertes-doublons.json',alerts)
    save(Path('r15/doublons.json'),dict(seuil_dhash=4,bits=64,acquisitions_R14=len(known),
        fichiers_par_catalogue=dict(Counter(r[1] for r in rows)),
        comparaisons=[dict(catalogues=c,paires=counts[c,'paires'],sha256_identiques=counts[c,'exactes'],
                          dhash_alerte=counts[c,'alertes_dhash']) for c in sorted({k[0] for k in counts})],
        proches_par_admission=[dict(photo=k,population=g,distance=d,voisin=other)
                               for (k,g),(d,other) in sorted(nearest.items())]))
    print(len(rows),sum(v for (k,t),v in counts.items() if t=='paires'),len(alerts),'alertes')
    for a in alerts:
        if a['a'] in alts or a['b'] in alts:
            print(a)


if __name__=='__main__':
    main()
