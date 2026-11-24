"""Figer les cas à partir des listes R16 sans pixels, score ni autre checkpoint."""
import argparse
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path

from r14.mesurer import read, save
from r15.corpus import corpus, fingerprint
from r16.donnees import FIRST, INDEX, axis, matrices, operational, scaling

SOURCES = ('r14/partitions.json','r15/lectures.json','r15/partitions.json','r15/manifeste-scelle.json',
           'r16/exemples-fit.json','r16/candidats-fit.json','r16/effectifs-fit.json',
           'r16/mesures/ordinary-9301.json','r16/predictions/ordinary-9301.json',
           'r15/caracteristiques/ordinary-9301.csv')
BASE = '8ec45fbf88c30fbbdcc09c9eee769aa1f1abe5ea'


def freeze():
    photos = {p['id']:p for p in corpus()}
    mats = matrices(FIRST)
    examples = read('r16/exemples-fit.json')
    assert examples['checkpoint'] == FIRST
    entries = []
    for e in examples['exemples']:
        if e['y'] == 1:
            entries.append(('positif_fit', e['photo'], e['rang_sortie'], [e['instance']], e['poids']))
    for e in read('r16/candidats-fit.json'):
        if e['decision'] == 'zone_ignoree' and e['instances_strictes']:
            entries.append(('exclu_ignore', e['photo'], e['rang_sortie'], e['instances_strictes'], None))
    data = read('r16/mesures/ordinary-9301.json')['segments_par_photo']
    raw = read('r16/predictions/ordinary-9301.json')
    for k,p in data.items():
        neighbors = defaultdict(list)
        for ref in p['references']:
            for rank in ref['stricts']:
                neighbors[rank].append(ref['instance'])
        for rank, ids in sorted(neighbors.items()):
            entries.append(('dev_strict', k, rank, ids, None))
    counts = Counter(e[0] for e in entries)
    if counts != {'positif_fit':12, 'exclu_ignore':4, 'dev_strict':12}:
        raise ValueError(counts)
    cases = []
    lineage = defaultdict(list)
    geom = {}
    for n,(group,k,rank,ids,weight) in enumerate(entries,1):
        p = photos[k]
        row = operational(mats[k])[rank]
        seg = axis(row).tolist()
        if group=='dev_strict':
            # Uniquement les coordonnées, rang et ordre du générateur.
            length_rank,full = raw[k]['segments'][rank][:2]
            if length_rank!=row['rang_longueur'] or [format(v,'.12g') for q in full for v in q]!=[row[v] for v in ('x0','y0','x1','y1')]:
                raise ValueError(k)
            seg=full
        key = sha256(json.dumps([p['sha256_pixels'],sorted(seg)],separators=(',',':')).encode()).hexdigest()
        case_id=f'R17-{n:02d}'
        link=dict(cas_initial=case_id,ensemble=group,rang_sortie=rank,rang_longueur=row['rang_longueur'],
                  instances=ids,poids_train_R16=weight)
        if key in geom:
            geom[key]['filiation'].append(link)
            already={ref['instance'] for ref in geom[key]['references']}
        else:
            entry=dict(cas=case_id,photo=k,provenance=p['provenance'],partition_R15=p['partition'],
                       affectation_R17='train' if p['partition']=='train' else 'dev_explore_R16',
                       supports=p['supports'],sha256_pixels=p['sha256_pixels'],taille_travail=p['taille'],
                       geometrie_sha256=key,echelle_704=scaling(p).tolist(),segment_704=seg,
                       regions_ignorees=p['regions_ignorees'],references=[],filiation=[link])
            cases.append(entry)
            geom[key]=entry
            already=set()
        entry=geom[key]
        for ident in ids:
            if entry['cas'] not in lineage[ident]:
                lineage[ident].append(entry['cas'])
            if ident not in already:
                mark=next(t for t in p['tubes'] if t['instance']==ident)
                entry['references'].append(dict(instance=ident,axe=mark['axe'],entree=mark['entree'],
                                               contact_visible=mark['contact_visible'],statut_initial=mark['statut']))
    return dict(base=BASE, checkpoint=FIRST, sources_sha256={s:fingerprint(s) for s in SOURCES},
                entrees_initiales=len(entries), par_ensemble=dict(counts), geometries=len(cases),
                liens_cas_instance=sum(len(c['references']) for c in cases), instances_distinctes=len(lineage),
                acquisitions=len({c['photo'] for c in cases}), provenances=len({c['provenance'] for c in cases}),
                par_instance=dict(sorted(lineage.items())),cas=cases)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sortie',type=Path)
    save(parser.parse_args().sortie,freeze())
