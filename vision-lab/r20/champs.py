"""Résumés reproductibles des douze champs FP32 à chacun des points de contrôle."""
from itertools import combinations

import numpy as np


CHANNELS=('masque','axe','entree')


def softmax(a):
    x=np.exp(a-a.max(axis=-1,keepdims=True))
    return x/x.sum(axis=-1,keepdims=True)


def field_summary(fields):
    x=np.asarray(fields,dtype=np.float64).reshape(12,3,-1)
    if not np.isfinite(x).all():
        raise ValueError('Champ non fini')
    peaks=x.argmax(axis=2)
    centered=x-x.mean(axis=-1,keepdims=True)
    norms=np.linalg.norm(centered,axis=-1)
    probs=softmax(x[:,2])
    stats=[]
    for q,r in combinations(range(12),2):
        metrics={}
        for c,channel in enumerate(CHANNELS):
            delta=x[q,c]-x[r,c]
            den=norms[q,c]*norms[r,c]
            corr=float(np.clip(np.dot(centered[q,c],centered[r,c])/den,-1,1)) if den else None
            metrics[channel]=dict(rmse_logit=float(np.sqrt(np.mean(delta**2))),ecart_max=float(np.max(np.abs(delta))),
                                  correlation_centree=corr,meme_maximum=bool(peaks[q,c]==peaks[r,c]))
        metrics['entree']['distance_variation_totale']=float(.5*np.abs(probs[q]-probs[r]).sum())
        stats.append(dict(q=q,r=r,**metrics))
    rows=[]
    for q in range(12):
        row={}
        for c,channel in enumerate(CHANNELS):
            a=x[q,c]
            top=np.partition(a,-2)[-2:]
            row[channel]=dict(min=float(a.min()),max=float(a.max()),moyenne=float(a.mean()),ecart_type=float(a.std()),
                              cellule_max_ligne_colonne=int(peaks[q,c]),marge_pic=float(top.max()-top.min()))
        rows.append(dict(q=q,**row))
    same=[r['entree'] for r in stats if r['entree']['meme_maximum']]
    return dict(requetes=rows,paires=stats,coordonnees_distinctes=len(set(peaks[:,2].tolist())),paires_meme_pic=len(same),
                paires_champs_entree_identiques=sum(r['entree']['ecart_max']==0 for r in stats),
                mediane_TV_meme_pic=float(np.median([r['distance_variation_totale'] for r in same])) if same else None,
                mediane_correlation_entree=float(np.median([r['entree']['correlation_centree'] for r in stats
                     if r['entree']['correlation_centree'] is not None])) if any(r['entree']['correlation_centree'] is not None for r in stats) else None)


def load_fields(folder):
    planes=[]
    for q in range(12):
        with np.load(folder/f'q{q:02}.npz',allow_pickle=False) as data:
            row=np.stack([data[c] for c in CHANNELS])
        if row.dtype!=np.float32 or row.ndim!=3:
            raise ValueError('Champ FP32 attendu')
        planes.append(row)
    return np.stack(planes)
