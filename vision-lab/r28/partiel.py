"""Comptages dans les appariements appris et unions au même repère natif."""
from collections import defaultdict
from statistics import median

import cv2
import numpy as np

from r28.commun import SEEDS, STEPS, tag
from r28.couples import POLICIES

CLASSES=('identite','autres_identites','fond_certifie','ignores')


def counts_sum(rows):
    return {field:sum(row[field] for row in rows) for field in CLASSES}


def fraction(counts):
    total=sum(counts.values())
    return counts['ignores']/total if total else None


def summary(rows):
    counts=counts_sum([r['pixels'] for r in rows])
    rates=[r['fraction_ignoree'] for r in rows if r['fraction_ignoree'] is not None]
    return dict(associations=len(rows),fractions_definies=len(rates),incidences=counts,
                aire_totale=sum(counts.values()),fraction_ignoree_ponderee=fraction(counts),
                mediane_fraction_ignoree=median(rates) if rates else None)


def union_counts(labels, any_cover, own):
    known_pos=(labels!=0)&(labels!=255)
    assert not np.any(own & (~any_cover|~known_pos))
    row=dict(identite=int(np.count_nonzero(own)),
             autres_identites=int(np.count_nonzero(known_pos & any_cover & ~own)),
             fond_certifie=int(np.count_nonzero(any_cover & (labels==0))),
             ignores=int(np.count_nonzero(any_cover & (labels==255))))
    assert sum(row.values())==np.count_nonzero(any_cover)
    return row


def union_masks(labels,bits,identities,field):
    occupied=np.zeros(labels.shape,dtype=bool)
    own=np.zeros_like(occupied)
    for state in identities.values():
        q=state['appris'][field]
        if q is not None:
            m=(bits & (1<<q))!=0
            occupied|=m
            own|=m & (labels==state['indice'])
    return occupied,own


def partial(photos, states, paths):
    result={}
    names=[n for spec in photos.values() for n in spec['instances']]
    origins=list(dict.fromkeys(spec['provenance'] for spec in photos.values()))
    for policy,field in POLICIES.items():
        records=[]
        spatial=defaultdict(dict)
        for photo,spec in photos.items():
            labels=cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
            pooled_all=np.zeros((2,*labels.shape),bool)
            for seed in SEEDS:
                pooled_seed=np.zeros_like(pooled_all)
                for step in STEPS:
                    code=tag(seed,step,photo)
                    state=states[code]
                    with np.load(paths[code],allow_pickle=False) as f:
                        occupied,own=union_masks(labels,f['masques'],state['identites'],field)
                    pooled_seed[0]|=occupied
                    pooled_seed[1]|=own
                    spatial[f'{seed}-{step}'][photo]=union_counts(labels,occupied,own)
                    for n,inst in state['identites'].items():
                        q=inst['appris'][field]
                        if q is None:
                            continue
                        pix=inst['couples'][q]['pixels_appris']
                        records.append(dict(graine=seed,pas=step,photo=photo,provenance=spec['provenance'],identite=n,
                            q=q,pixels=pix,aire=sum(pix.values()),fraction_ignoree=fraction(pix)))
                spatial[str(seed)][photo]=union_counts(labels,*pooled_seed)
                pooled_all|=pooled_seed
            spatial['ensemble'][photo]=union_counts(labels,*pooled_all)

        levels={}
        for key,photo_unions in spatial.items():
            def belongs(r):
                return key in ('ensemble',str(r['graine']),f"{r['graine']}-{r['pas']}")
            subset=[r for r in records if belongs(r)]
            def describe(rows, selected_photos):
                union=counts_sum([photo_unions[k] for k in selected_photos])
                data=summary(rows)
                assert union['ignores']<=data['incidences']['ignores'] and sum(union.values())<=data['aire_totale']
                return dict(**data,union_spatiale=union,fraction_ignoree_union=fraction(union))
            levels[key]=dict(ensemble=describe(subset,list(photos)),
                provenances={s:describe([r for r in subset if r['provenance']==s],
                                        [k for k,v in photos.items() if v['provenance']==s]) for s in origins},
                identites={n:summary([r for r in subset if r['identite']==n]) for n in names},
                unions_par_photo=photo_unions)
        result[policy]=dict(associations=records,resumes=levels)
    return result
