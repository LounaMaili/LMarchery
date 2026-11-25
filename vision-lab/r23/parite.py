"""Vérifier le décodage, les pertes et la géométrie depuis la capture unique."""
import argparse
from hashlib import sha256
import json
import math
from pathlib import Path

import numpy as np
import torch

from r19.donnees import fingerprint, read, save
from r19.pilote import setup
from r20.operations import save_prediction, write_predictions
from r21.mesures import measure_views
from r21.regle import losses as witness
from r22.regle import losses as dice
from r23.commun import ARMS, PHOTOS, ROOT, SEED, STEPS, archive, cases, measures, tag
from r23.perimetre import inputs, references


def digest(obj):
    return sha256(json.dumps(obj,sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()).hexdigest()


def exact(label,observed,expected):
    actual,old=digest(observed),digest(expected)
    if actual!=old:
        raise ValueError(f'{label} diffère de son archive : {actual} / {old}')
    return actual


def geometries(observed,expected):
    differences=[]
    def check(a,b,key):
        if a[key]==b[key]:
            return
        x,y=a[key],b[key]
        if x is None or y is None or abs(x-y)>2*math.ulp(y) or (x<=10)!=(y<=10):
            raise ValueError(f'Distance géométrique divergente {x}, {y}')
        differences.append(dict(champ=key,recalcule=x,archive=y,ulp=abs(x-y)/math.ulp(y)))
        a[key]=y
    for n,cs in observed['paires_par_identite'].items():
        for a,b in zip(cs,expected['paires_par_identite'][n],strict=True):
            check(a,b,'erreur_entree_px704')
    for field in ('couverture','au_seuil'):
        for a,b in zip(observed[field]['paires'],expected[field]['paires'],strict=True):
            check(a,b,'distance_px')
    return exact('Mesures géométriques',observed,expected),differences


def load_case(src,case,ledger):
    path=src/tag(*case)/'champs.npz'
    if fingerprint(path)!=ledger['sha256']:
        raise ValueError(str(path))
    with np.load(path,allow_pickle=False) as bundle:
        return torch.from_numpy(bundle['champs']),torch.from_numpy(bundle['presence'])


@torch.inference_mode()
def verify(src,worktrees,out):
    setup(SEED)
    refs=references()
    data=inputs(worktrees,refs)
    registry=read('r21/inventaires.json')
    ledger=read(src/'execution.json')
    keys=[(r['bras'],r['pas'],r['photo']) for r in ledger['passages']]
    if keys!=list(cases()) or [r['numero'] for r in ledger['passages']]!=list(range(1,9)) or ledger['nombre']!=8:
        raise ValueError('Registre des huit passages incomplet')
    records=dict(zip(keys,ledger['passages'],strict=True))
    proof={}
    for arm in ARMS:
        for step in STEPS:
            folder=src/f'decodage-{arm}-{step}'
            old=archive(arm)/str(step)
            old_pred,old_cost=read(old/'predictions.json'),read(old/'pertes.json')
            predictions,costs={},{}
            for k in PHOTOS:
                case=arm,step,k
                fields,logits=load_case(src,case,records[case])
                rgb,target=data[k]
                predictions[k]=save_prediction(fields,logits,rgb,refs[k],folder/'masques')
                _,_,_,costs[k]=witness(fields,logits,target,False) if arm=='temoin' else dice(fields,logits,target)
                png=folder/'masques'/(k+'.png')
                src_png=old/'masques'/(k+'.png')
                if fingerprint(png)!=fingerprint(src_png):
                    raise ValueError('Masque décodé différent')
                proof[tag(*case)]=dict(poids=read(old/'controle.json')['poids_sha256'],
                    champs_locaux=records[case]['sha256'],
                    points_presences_scores_segments=exact('Décodage',predictions[k],old_pred[k]),
                    couts_logits_et_affectations=exact('Coûts',costs[k],old_cost[k]),png_sha256=fingerprint(png),
                    plan_uint16=predictions[k]['sha256_plan_uint16_le'])
            write_predictions(folder,predictions,costs)
            rows=measure_views(refs,registry,folder,SEED)
            archived=measures(arm,step)
            for k,row in rows.items():
                val,diffs=geometries(row,archived[k])
                proof[tag(arm,step,k)].update(geometrie_sha256=val,ecarts_distances=diffs,parite=True)
    save(out,dict(parite_complete=True,passages_verifies=len(proof),nouveaux_passages=0,etats=proof))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture',type=Path)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    verify(a.capture,a.worktrees,a.out)
