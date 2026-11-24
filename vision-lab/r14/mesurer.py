"""Mesures à partir des coordonnées et lectures publiques, sans photo ni réseau."""
import argparse
from collections import Counter
import json
from math import dist
from pathlib import Path
from statistics import median


def read(path):
    return json.loads(Path(path).read_bytes())


def save(path,data):
    Path(path).write_text(json.dumps(data,ensure_ascii=False,indent=2,allow_nan=False)+'\n')


def maximum(edges):
    """Cardinalité maximale, y compris quand une réaffectation est nécessaire."""
    owner={}
    def assign(tube,seen):
        for segment in sorted(edges[tube]):
            if segment in seen:
                continue
            seen.add(segment)
            if segment not in owner or assign(owner[segment],seen):
                owner[segment]=tube
                return True
        return False
    return sum(assign(i,set()) for i in range(len(edges)))


def metrics(row,raw,keep):
    strict=[]
    ends=[]
    near=0
    segments=raw['axes_B']
    scale=raw['echelle']
    for mark,ref in zip(row['marks'],raw['B']['reference_axes'],strict=True):
        ids=keep.intersection(ref['strict_ids'])
        strict.append(ids)
        if mark['entry'] is not None:
            xy=[a*b for a,b in zip(mark['entry'],scale,strict=True)]
            close={i for i in keep if min(dist(p,xy) for p in segments[i])<=10}
            near+=bool(close)
            ends.append(close & ids)
    counts=Counter(i for ids in strict for i in ids)
    return dict(segments=len(keep),tubes=len(strict),entrees=len(ends),strict=sum(map(bool,strict)),
                strict_1a1=maximum(strict),bout_proche=near,bout_strict_1a1=maximum(ends),
                tubes_plusieurs_segments=sum(len(ids)>1 for ids in strict),
                segments_plusieurs_tubes=sum(v>1 for v in counts.values()),
                parasites_sur_vide=len(keep) if 'empty' in row['flags'] else 0)


def totals(records):
    total=Counter()
    for record in records:
        total.update(record)
    return dict(total)


def analyze(out):
    out.mkdir(exist_ok=True,parents=True)
    families=read('r14/mesures/familles.json')
    readings=read('r14/lectures.json')
    labels={(r['photo'],r['segment']):r for r in readings}
    if len(labels)!=len(readings):
        raise ValueError('Lecture en double')
    rows=read('r12/historique.json')+[p for p in read('r12/nouvelles.json')['photos'] if p['split']=='nouveau']
    all_totals={}
    for name,images in families.items():
        frozen=read(f'r13/sorties/{name}.json')
        contacts=read(f'r13/mesures/{name}.json')['contacts']['B']
        photos={}
        for row in rows:
            key=row['id']
            raw=frozen[key]
            groups=images[key]
            full=set(range(len(groups)))
            if len(groups)!=len(raw['axes_B']):
                raise ValueError(key)
            variants={'LSD':full,'paire':{i for i in full if groups[i]=='paire'},'isole':{i for i in full if groups[i]=='isole'}}
            if name=='ordinary-9301':
                target=set()
                for family in ('paire','isole'):
                    ids=sorted(variants[family])
                    if ids:
                        target.update((ids[0],ids[(len(ids)-1)//2],ids[-1]))
                if target!={i for photo,i in labels if photo==key} or any(labels[key,i]['famille']!=groups[i] for i in target):
                    raise ValueError('Échantillon ou famille inattendus')
                rejected={i for i in target if labels[key,i]['classe'] in ('blason','decor','ombre','association_erronee')}
                variants['retrait_documentaire']=set() if 'empty' in row['flags'] else full-rejected
            scores={v:metrics(row,raw,keep) for v,keep in variants.items()}
            refs=raw['B']['reference_axes']
            assert scores['LSD']['strict']==sum(r['axis_recovered'] for r in refs)
            assert scores['LSD']['bout_proche']==sum(r.get('entry_near',False) for r in refs)
            photos[key]=dict(provenance=row['group'],partition=row.get('split','historique'),flags=row['flags'],
                             ms_LSD=1000*raw['temps_s']['B'],mesures=scores)
            if photos[key]['partition']!='nouveau':
                photos[key]['partition']='historique'
        strata={}
        for subset in ('ensemble','historique','nouveau','worn','grouped','empty'):
            pop=[r for r in photos.values() if subset=='ensemble' or r['partition']==subset or subset in r['flags']]
            strata[subset]={'photos':len(pop),'ms_LSD_mediane':median(r['ms_LSD'] for r in pop),
                            'mesures':{v:totals(r['mesures'][v] for r in pop) for v in variants}}
        save(out/(name+'.json'),dict(par_photo=photos,strates=strata,contacts_LSD=contacts))
        all_totals[name]=strata
    save(out/'synthese.json',all_totals)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    analyze(parser.parse_args().out)
