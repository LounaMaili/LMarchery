"""Fusion des lectures publiques autorisées, avec identités d’instances dans les séquences."""
import argparse
from collections import Counter
from hashlib import sha256
from pathlib import Path

from r14.mesurer import read,save


def collect(worktrees,out):
    def root(n):
        return worktrees/f'vision-lab-r{n}/vision-lab/data/r{n}'
    photos={}
    paths={}
    negatives=read('r14/banque-segments.json')
    def add(step,part,r,marks,source,path,source_hash=None):
        key=r['id']
        old=photos.get(key)
        if old and (old['partition']!=part or old['provenance']!=r['group']):
            raise ValueError('Collision de partition')
        if old:
            # R8 complète la supervision courante et celle des flèches antérieures.
            known={m['instance']:m for m in old['tubes']}
            for m in marks:
                was=known.get(m['instance'])
                if was and was['contact_visible'] and not m['contact_visible']:
                    m['autre_vue_ou_lecture_visible']=True
            if set(known)-{m['instance'] for m in marks}:
                raise ValueError('Instance source perdue')
        h=sha256(path.read_bytes()).hexdigest()
        if source_hash and h!=source_hash:
            raise ValueError(key)
        paths[key]=path
        photos[key]=dict(id=key,etapes=(old['etapes'] if old else [])+[step],partition=part,provenance=r['group'],
                         supports=r.get('boards',[]),conditions=r.get('flags',[]),taille=r['size'],sha256_pixels=h,
                         complet=r['exhaustive'],regions_ignorees=r.get('ignore',[]),source=source,tubes=marks)
    def tubes(r,field='marks'):
        return [dict(instance=m.get('instance') or f"{r['id']}:{i}",axe=m.get('axis'),
                     bouts_trace=m.get('axis'),masque_source=bool(m.get('polygon')),entree=m['entry'],
                     contact_visible=m['entry'] is not None,statut=m.get('visibility') or (
                         'visible' if m['entry'] is not None else 'inconnu_ou_occulte'))
                for i,m in enumerate(r[field],1)]
    # R7 exprimait les annotations LabelMe dans le repère original.
    for r in read(root(7)/'pilot/annotations.json'):
        if r['split']!='train':
            continue
        flags=['empty'] if not r['new_instance'] else []
        row=dict(id=r['id'],group='Crigges',boards=['crigges-4'],size=r['work_size'],flags=flags,exhaustive=r['source_exhaustive'])
        entry=None if r['entry'] is None else [p*w/o for p,w,o in zip(r['entry'],r['work_size'],r['oriented_size'],strict=True)]
        marks=[] if not r['new_instance'] else [dict(instance=r['new_instance'],axe=None,bouts_trace=None,masque_source=True,
                               entree=entry,contact_visible=entry is not None,statut=r['entry_status'])]
        add('R7','train',row,marks,dict(url=r['source'],licence=r['license'],source_file=r['source_file'],
            sha256_original=r['source_sha256']),root(7)/'pilot'/r['work_file'],r['work_sha256'])
    for r in read(root(7)/'external.json'):
        if r['split']!='dev':
            continue
        row=dict(r,size=r['oriented_size'],exhaustive=r['complete'],ignore=r.get('ignore_regions',[]))
        add('R7','dev_explore',row,tubes(r,'instances'),dict(url=r['source_url'],licence=r['license']),
            root(7)/r['source_file'],r['source_sha256'])
    sources={r['id']:r['source'] for r in read('r9/sources.json')['photos']}
    for r in read(root(8)/'corpus.json'):
        if r['split']=='train':
            path=paths[r['id']] if r['pool']=='control' else root(8)/r['image']
            add('R8','train',r,tubes(r),sources[r['id']],path,r['sha256'])
    for r in read(root(9)/'dev.json'):
        n=8 if r['origin']=='r8-development' else 9
        add('R8/R9' if n==8 else 'R9','dev_explore',r,tubes(r),sources[r['id']],root(n)/r['image'],r['sha256'])
    for n,records in [(12,read('r12/nouvelles.json')['photos']),(13,read('r13/collecte.json')['lectures']),
                      (14,read('r14/collecte.json')['lectures'])]:
        for r in records:
            part='dev_indep' if n>12 else ('train' if r['split']=='train' else 'dev_explore')
            add(f'R{n}',part,r,tubes(r),r['source'],root(n)/'media'/(r['id']+'.jpg'),r['source']['sha256'])
    for mark in read('r14/axes-complementaires.json'):
        row=photos[mark['photo']]
        tube=next(m for m in row['tubes'] if m['instance']==mark['instance'])
        assert row['partition']=='train' and tube['masque_source'] and tube['axe'] is None
        tube.update(axe=mark['axe'],bouts_trace=mark['axe'],lecture_axe='R14')
    groups=[{r['provenance'].casefold() for r in photos.values() if r['partition']==p}
            for p in ('train','dev_explore','dev_indep')]
    if any(g & h for i,g in enumerate(groups) for h in groups[i+1:]):
        raise ValueError('Auteur commun entre partitions')
    if len({p['sha256_pixels'] for p in photos.values()})!=len(photos):
        raise ValueError('Pixels en double')
    import cv2 as cv
    def dhash(path):
        gray=cv.imread(str(path),cv.IMREAD_GRAYSCALE)
        thumb=cv.resize(gray,(9,8),interpolation=cv.INTER_AREA)
        return (thumb[:,1:]>thumb[:,:-1]).ravel()
    new=read('r14/collecte.json')['lectures'][0]['id']
    thumb=dhash(paths[new])
    nearest=min((int((thumb!=dhash(path)).sum()),k) for k,path in paths.items() if k!=new)
    summary={}
    for part in ('train','dev_explore','dev_indep'):
        rows=[r for r in photos.values() if r['partition']==part]
        def summary_of(rs):
            keys={r['id'] for r in rs}
            negatives_here=[b for b in negatives if b['photo'] in keys and b['statut']=='negatif_verifie']
            subjects=[m for r in rs for m in r['tubes']]
            visible={m['instance'] for m in subjects if m['contact_visible'] or m.get('autre_vue_ou_lecture_visible')}
            neg_photos={b['photo'] for b in negatives_here}
            return dict(photos=len(rs),provenances_occupees=len({r['provenance'] for r in rs if r['tubes']}),
                        instances_distinctes=len({m['instance'] for m in subjects}),entrees_distinctes=len(visible),
                        axes_traces_distincts=len({m['instance'] for m in subjects if m['axe']}),
                        groupes=len({(r['provenance'],tuple(r['supports'])) for r in rs if 'grouped' in r['conditions']}),
                        supports_vides_uses=len({(r['provenance'],tuple(r['supports'])) for r in rs if 'empty' in r['conditions'] and 'worn' in r['conditions'] and r['complet']}),
                        segments_negatifs=len(negatives_here),acquisitions_negatives=len(neg_photos),
                        occupees_avec_negatifs=sum(r['id'] in neg_photos and bool(r['tubes']) for r in rs),
                        classes_negatives=dict(Counter(b['lecture'] for b in negatives_here)))
        summary[part]=dict(total=summary_of(rows),par_provenance={g:summary_of([r for r in rows if r['provenance']==g])
                                                for g in sorted({r['provenance'] for r in rows})})
    save(out/'partitions.json',dict(repere='pixels de travail orientés; R7 externe : original orienté',photos=list(photos.values())))
    save(out/'inventaire-pixels.json',[dict(nom=p.name,role=photos[k]['partition'],emplacement=str(p.resolve()),
        octets=p.stat().st_size,sha256=photos[k]['sha256_pixels']) for k,p in paths.items()])
    save(out/'effectifs.json',dict(partitions=summary,controle_nouvelle=dict(comparaisons=len(paths)-1,
        distance_min_dhash=nearest[0],plus_proche=nearest[1]),ensemble_exclu='tous les tests'))
    thresholds={
        'train':(6,30,30,2,2,30,6,3),
        'dev_indep':(4,12,12,2,2,12,4,2)
    }
    criteria=('provenances_occupees','entrees_distinctes','axes_traces_distincts','groupes','supports_vides_uses',
              'segments_negatifs','acquisitions_negatives','occupees_avec_negatifs')
    gates={}
    for part,limits in thresholds.items():
        measured=summary[part]['total']
        items={key:dict(observe=measured[key],minimum=low,manque=max(0,low-measured[key]))
               for key,low in zip(criteria,limits,strict=True)}
        classes=measured['classes_negatives']
        diversity=bool(classes.get('blason')) and bool(classes.get('decor') or classes.get('ombre'))
        gates[part]=dict(criteres=items,diversite_negatifs=diversity,
                         admissible=diversity and all(x['manque']==0 for x in items.values()))
    save(out/'portes.json',dict(partitions=gates,apprentissage=all(v['admissible'] for v in gates.values())))
    return photos

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    collect(a.worktrees,a.out)
