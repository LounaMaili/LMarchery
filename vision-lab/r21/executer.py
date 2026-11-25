"""Un ajustement R21, sur le seul train et aux deux états préenregistrés."""
import argparse
from collections import Counter
from hashlib import sha256
import fcntl
import json
from pathlib import Path
import time

import torch

from r19.donnees import SEEDS, fingerprint, read, save
from r19.modele import Instances, parameter_counts
from r19.pilote import setup
from r20.corpus import references
from r20.operations import metadata, save_prediction, tensors, write_predictions
from r21.inventorier import ROOT
from r21.plan import ARMS, STEPS, tensor_state
from r21.regle import losses


def parity(model,file):
    reference=torch.load(file,map_location='cpu',weights_only=True)
    state=tensor_state(model.state_dict());old=tensor_state(reference)
    names=sorted(set(state['tenseurs'])|set(old['tenseurs']))
    different=[k for k in names if state['tenseurs'].get(k)!=old['tenseurs'].get(k)]
    return dict(conteneur_historique=fingerprint(file),etat_historique=old['sha256'],etat_repris=state['sha256'],
                parametres_et_buffers=len(names),differents=different,identique=not different)


@torch.inference_mode()
def evaluate(model,data,refs,config,arm,folder):
    model.eval()
    records={};costs={}
    for k,ref in refs.items():
        rgb,target=data[k]
        fields,logits=model(rgb)
        records[k]=save_prediction(fields,logits,rgb,ref,folder/'masques')
        _,_,_,costs[k]=losses(fields,logits,target,arm=='candidat' and k in config['admises'])
    write_predictions(folder,records,costs)
    model.train()


def execute(arm,seed,worktrees,out):
    config=read(ROOT/'configuration.json')
    for p,expected in config['empreintes'].items():
        if fingerprint(Path(p))!=expected:raise ValueError('Entrée différente du gel '+p)
    plan=read(ROOT/'tirages.json')[str(seed)]
    if config['pas']!=list(STEPS) or len(plan)!=768 or plan[:96]!=read('r19/tirages.json')[str(seed)]:
        raise ValueError('Plan différent du gel')
    cp=config['initial_R9'];weight=worktrees/cp['fichier']
    hist=config['poids_temoin_historiques'][str(seed)];historical=worktrees/hist['fichier']
    if fingerprint(weight)!=cp['sha256'] or fingerprint(historical)!=hist['sha256']:raise ValueError('Poids de référence')
    setup(seed)
    start,cpu=time.monotonic(),time.process_time()
    model=Instances(weight);model.train()
    initial=tensor_state(model.state_dict())['sha256']
    if initial!=config['etats_initiaux_par_graine'][str(seed)] or parameter_counts(model)!=config['parametres']:
        raise ValueError('Initialisation incompatible')
    refs=references()
    if set(refs)!=set(config['admises']+config['partielles']):raise ValueError('Train modifié')
    data={k:tensors(ref,worktrees) for k,ref in refs.items()}
    trainable=[p for p in model.parameters() if p.requires_grad]
    optimizer=torch.optim.AdamW(trainable,lr=.001,betas=(.9,.999),eps=1.e-8,weight_decay=.01,foreach=False)
    folder=out/arm/str(seed);folder.mkdir(parents=True,exist_ok=False)
    counts=Counter();positive=Counter();negative=Counter();states={}
    with (folder/'progression.jsonl').open('x',buffering=1) as log:
        for row in plan:
            k=row['photo'];rgb,target=data[k]
            optimizer.zero_grad(set_to_none=True)
            fields,logits=model(rgb)
            total,parts,audit,cost=losses(fields,logits,target,arm=='candidat' and k in config['admises'])
            if not torch.isfinite(total):raise ValueError('Perte non finie')
            total.backward()
            norm=float(torch.nn.utils.clip_grad_norm_(trainable,5.,error_if_nonfinite=True))
            optimizer.step()
            assignments={v['instance']:v['q'] for v in cost['par_identite']}
            observation=dict(**row,**{l:v.item() for l,v in parts.items()},total=total.item(),norme_gradient=norm,
                    ecrete=norm>5.,**audit,positives=assignments,negatives=cost['negatifs'])
            log.write(json.dumps(observation,allow_nan=False,ensure_ascii=False)+'\n')
            counts[k]+=1;positive[k]+=len(assignments);negative[k]+=len(cost['negatifs'])
            if row['pas'] not in STEPS:continue
            step=row['pas'];dest=folder/str(step);dest.mkdir()
            torch.save(model.state_dict(),dest/'poids.pt')
            torch.save(dict(optimiseur=optimizer.state_dict(),rng_cpu=torch.get_rng_state(),pas=step),dest/'optimiseur.pt')
            checkpoint=dict(pas=step,sha256_etat=tensor_state(model.state_dict())['sha256'],
                            poids_sha256=fingerprint(dest/'poids.pt'),optimiseur_sha256=fingerprint(dest/'optimiseur.pt'),
                            expositions=dict(counts),positives=dict(positive),negatives=dict(negative))
            if arm=='temoin' and step==96:
                check=parity(model,historical);save(dest/'parite.json',check)
                if not check['identique']:raise ValueError('Divergence R19 à expliquer avant le contraste')
            before=torch.get_rng_state()
            evaluate(model,data,refs,config,arm,dest)
            if (not torch.equal(before,torch.get_rng_state()) or
                    tensor_state(model.state_dict())['sha256']!=checkpoint['sha256_etat']):
                raise ValueError('Évaluation modifiant l’état de la trajectoire')
            states[str(step)]=checkpoint
            save(dest/'controle.json',checkpoint)
            print(arm,seed,step,'parité R19 vérifiée' if arm=='temoin' and step==96 else 'état archivé',flush=True)
    save(folder/'execution.json',dict(bras=arm,graine=seed,initial_R9=cp['sha256'],etat_initial=initial,
        nombre_pas=len(plan),vues_train_lues_aux_controles=2*len(refs),controles=states,
        expositions=dict(counts),positives=dict(positive),negatives=dict(negative),**metadata(start,cpu)))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('arm',choices=ARMS);parser.add_argument('seed',type=int,choices=SEEDS)
    parser.add_argument('worktrees',type=Path);parser.add_argument('out',type=Path)
    a=parser.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    with (a.out/'processus.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        execute(a.arm,a.seed,a.worktrees,a.out)
