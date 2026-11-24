"""Compter les stricts bruts puis sélectionner les exemples ordinary-9301 avant le fit."""
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path

import numpy as np

from r12.geometrie import samples, direction
from r14.mesurer import maximum, metrics
from r15.corpus import counts, gates, bank
from r16.donnees import (LAB, FIRST, INDEX, INPUTS, RUNS, matrices, operational, axis,
    corpus, scaling, reference, crosses_ignored, documentary, fingerprint, photo_paths, read, save)


def verify():
    seal = read('r15/manifeste-scelle.json')
    lines = []
    for k,spec in sorted(seal['pieces'].items()):
        if fingerprint(k)!=spec['sha256'] or Path(k).stat().st_size!=spec['octets']:
            raise ValueError(k)
        lines.append(f'{spec["sha256"]}  {k}\n')
    if sha256(''.join(lines).encode()).hexdigest()!=seal['sha256']:
        raise ValueError('Manifeste R15')
    rows = corpus()
    parts = read('r15/partitions.json')['partitions']
    totals = read('r15/effectifs.json')['partitions']
    owner_split = {}
    for part,spec in parts.items():
        pop = [p for p in rows if p['partition']==part]
        if [p['id'] for p in pop]!=[p['photo'] for p in spec] or counts(pop,bank())!=totals[part]['total']:
            raise ValueError(part)
        for p in pop:
            key=p['provenance'].casefold()
            if owner_split.setdefault(key,part)!=part:
                raise ValueError('Provenance partagée')
    g = gates(totals,read('r14/portes.json'))
    if g!=read('r15/portes.json') or not g['deux_portes_franchies']:
        raise ValueError('Portes R14')
    paths = photo_paths()
    if len({p['sha256_pixels'] for p in rows})!=len(rows):
        raise ValueError('Doublon de photo')
    for p in rows:
        if fingerprint(paths[p['id']])!=p['sha256_pixels']:
            raise ValueError(p['id'])
    inventory = read('r15/inventaire-local.json')
    for item in inventory['poids']+inventory['capsules']:
        path = Path(item['emplacement'])
        if path.stat().st_size!=item['octets'] or fingerprint(path)!=item['sha256']:
            raise ValueError(path.name)
    for name in RUNS:
        matrices(name)
    return dict(manifeste=seal['sha256'],pieces=len(lines),photos=len(rows),
                partitions={p:len(v) for p,v in parts.items()},capsules=2,poids=6,matrices=6,
                seuils_B_herites={n:read(f'r13/mesures/{n}.json')['contacts']['B']['historique']['seuil'] for n in RUNS},
                colonnes=INPUTS,portes=g)


def prepare(out):
    out.mkdir(parents=True,exist_ok=True)
    valid = verify()
    matrix = matrices(FIRST)
    labels = documentary()
    train = [p for p in corpus() if p['partition']=='train']
    block = {p['id']:('Crigges / support commun' if p['provenance']=='Crigges' else p['id']) for p in train}
    records = []
    candidates = defaultdict(list)
    stats = {}
    for p in train:
        k = p['id']
        group = matrix.get(k,[])
        outputs = operational(group)
        axes = [axis(r) for r in outputs]
        row,raw = reference(p,axes)
        marks = row['marks']
        refs = raw['B']['reference_axes']
        strict = [v['strict_ids'] for v in refs]
        maximum_here = maximum(strict)
        if maximum_here!=metrics(row,raw,set(range(len(outputs))))['strict_1a1']:
            raise ValueError('Parité R14')
        by_segment = defaultdict(list)
        for mark,edges in zip(marks,strict,strict=True):
            for j in edges:
                by_segment[j].append(mark)
        stats[k] = dict(provenance=p['provenance'],bloc=block[k],instances_annotees=[t['instance'] for t in p['tubes']],
                        axes=[t['instance'] for t in marks],appariables=[m['instance'] for m,e in zip(marks,strict,strict=True) if e],
                        appariement_maximal_brut=maximum_here,sorties=len(outputs),avec_matrice=bool(group))
        for r in group:
            rank,j = r['rang_longueur'],r['rang_sortie_R13']
            doc = labels.get((k,rank))
            match = by_segment.get(j,[])
            segment = axis(r)
            excluded = crosses_ignored(segment,p)
            rec = dict(photo=k,provenance=p['provenance'],bloc=block[k],rang_longueur=rank,rang_sortie=j,
                lecture=doc['statut'] if doc else None,classe_lecture=doc['lecture'] if doc else None,
                rencontre_zone_ignoree=excluded,instances_strictes=[m['instance'] for m in match],decision='sans_supervision')
            records.append(rec)
            if excluded:
                rec['decision']='zone_ignoree'
            elif doc and doc['statut']=='negatif_verifie':
                rec['decision']='conflit_de_supervision' if match else 'negatif'
            elif doc and doc['statut'] not in ('tube','negatif_verifie'):
                rec['decision']='lecture_inutilisable'
            elif len(match)>1:
                rec['decision']='plusieurs_instances'
            elif len(match)==1:
                m=match[0]
                pts=samples(np.array(m['axis'])*scaling(p))
                v=direction(segment)
                closest=segment[0]+np.clip((pts-segment[0])@v,0.,np.linalg.norm(segment[1]-segment[0]))[:,None]*v
                distances=np.linalg.norm(pts-closest,axis=1)
                hits=int(np.sum(distances<=6))
                if hits<11:
                    raise ValueError('Géométrie de sélection')
                rec.update(decision='autre_segment_du_tube',sondes=hits,distance_moyenne=float(distances.mean()))
                candidates[m['instance']].append(((-hits,float(distances.mean()),k,j),rec))
    for instance,options in candidates.items():
        winner=min(options,key=lambda x:x[0])[1]
        winner.update(decision='positif',instance=instance)
    selected=[r for r in records if r['decision'] in ('positif','negatif')]
    classes=defaultdict(Counter)
    for r in selected:
        classes[r['bloc']][r['decision']]+=1
    for r in selected:
        c=classes[r['bloc']]
        r['poids']=len(selected)/(len(classes)*len(c)*c[r['decision']])
    if len({(r['photo'],r['rang_longueur']) for r in selected})!=len(selected):
        raise ValueError('Segment dupliqué')
    for k,v in stats.items():
        subset=[r for r in records if r['photo']==k]
        v['decisions']=dict(Counter(r['decision'] for r in subset))
        v['negatifs_dont_hors_sortie']=sum(r['decision']=='negatif' and r['rang_sortie'] is None for r in subset)
        v['representants']=[r['instance'] for r in subset if r['decision']=='positif']
    summary=dict(photos_train=len(train),photos_avec_matrice=sum(v['avec_matrice'] for v in stats.values()),
        tubes_annotes_distincts=len({t['instance'] for p in train for t in p['tubes']}),
        tubes_avec_axe=len({t['instance'] for p in train for t in p['tubes'] if t['axe']}),
        tubes_sans_axe=len({t['instance'] for p in train for t in p['tubes']}-{t['instance'] for p in train for t in p['tubes'] if t['axe']}),
        tubes_avec_voisin_strict=len({i for v in stats.values() for i in v['appariables']}),
        stricts_bruts_un_a_un=sum(v['appariement_maximal_brut'] for v in stats.values()),
        positifs=sum(r['decision']=='positif' for r in selected),negatifs=sum(r['decision']=='negatif' for r in selected),
        negatifs_documentes=sum(s['partition']=='train' and s['statut']=='negatif_verifie' for s in labels.values()),
        negatifs_hors_sortie=sum(v['negatifs_dont_hors_sortie'] for v in stats.values()),blocs_avec_exemples=len(classes),
        decisions=dict(Counter(r['decision'] for r in records)),lignes=len(records))
    save(out/'verification.json',valid)
    save(out/'effectifs-fit.json',dict(total=summary,par_acquisition=stats,par_bloc=dict(classes)))
    save(out/'candidats-fit.json',records)
    pop_by_key={(r['photo'],r['rang_longueur']):r for rows in matrix.values() for r in rows}
    examples=[]
    for r in selected:
        src=pop_by_key[r['photo'],r['rang_longueur']]
        examples.append(dict(photo=r['photo'],provenance=r['provenance'],bloc=r['bloc'],rang_longueur=r['rang_longueur'],
            rang_sortie=r['rang_sortie'],instance=r.get('instance'),y=int(r['decision']=='positif'),poids=r['poids'],
            x=[float(src[n]) if src[n]!='' else None for n in INPUTS]))
    save(out/'exemples-fit.json',dict(checkpoint=FIRST,colonnes=INPUTS,exemples=examples))
    print(summary)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    prepare(parser.parse_args().out)
