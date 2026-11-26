"""Trois trajectoires successives. Chaque pas et chaque état sont écrits une seule fois."""
import argparse
from collections import Counter
import fcntl
import json
from pathlib import Path
import time

import torch

from r19.donnees import SEEDS, fingerprint, read, save
from r19.pilote import setup
from r20.corpus import references
from r20.operations import metadata
from r21.plan import STEPS, tensor_state
from r22.executer import checkpoint
from r22.regle import losses
from r22.verifier import environment, initialization
from r24.adaptation import (check_frozen, clone, configure, displacement, frozen_tensors,
                            gradients, group_signature, optimizer_for)
from r24.diagnostic import ROOT
from r24.verifier import inputs


def execute(seed, worktrees, out):
    cfg = read(ROOT/'configuration.json')
    parity = read(ROOT/'comparabilite.json')
    for n,h in {**cfg['empreintes_gel'], **parity['empreintes_historiques']}.items():
        if fingerprint(Path(n)) != h:
            raise ValueError('Empreinte différente '+n)
    setup(seed)
    if not parity['comparabilite_verifiee'] or environment() != parity['environnement']:
        raise ValueError('Environnement différent')
    s = str(seed)
    weight = worktrees/cfg['initial_R9']['fichier']
    if fingerprint(weight) != cfg['initial_R9']['sha256']:
        raise ValueError('Poids du tronc initial')
    model, initial = initialization(seed,weight,read('r21/configuration.json'))
    groups = configure(model)
    if group_signature(groups) != parity['graines'][s]['groupes_initiaux']:
        raise ValueError('Paramètres initiaux différents')
    params = [p for ps in groups.values() for p in ps.values()]
    optimizer = optimizer_for(groups,cfg['optimiseur'])
    current = frozen_tensors(model, groups)
    fixed = clone(current)
    if tensor_state(fixed)['sha256'] != parity['graines'][s]['gel_initial']:
        raise ValueError('Tenseurs gelés initiaux différents')
    start_groups = {g:clone(ps) for g,ps in groups.items()}
    refs = references()
    data, targets = inputs(worktrees, refs)
    plan = read('r21/tirages.json')[s]
    if targets != parity['cibles'] or len(plan) != 768 or len(refs) != 9:
        raise ValueError('Exposition ou supervision différente')
    check_frozen(model, fixed, current)
    folder = out/s
    folder.mkdir(exist_ok=False)
    start,cpu = time.monotonic(), time.process_time()
    counts,positives,negatives = Counter(),Counter(),Counter()
    controls = {}
    with (folder/'progression.jsonl').open('x',buffering=1) as journal:
        for row in plan:
            k,step = row['photo'],row['pas']
            rgb,target = data[k]
            optimizer.zero_grad(set_to_none=True)
            fields,presence = model(rgb)
            total,parts,audit,cost = losses(fields,presence,target,cfg['coefficient_dice'])
            if not torch.isfinite(total):
                raise ValueError('Perte non finie')
            total.backward()
            before = {g:clone(ps) for g,ps in groups.items()}
            pre = {g:gradients(ps) for g,ps in groups.items()}
            norm = float(torch.nn.utils.clip_grad_norm_(params,cfg['ecretage'],error_if_nonfinite=True))
            post = {g:gradients(ps) for g,ps in groups.items()}
            optimizer.step()
            check_frozen(model, fixed, current)
            assigned = {a['instance']:a['q'] for a in cost['par_identite']}
            stats = {g:dict(norm_grad_avant=pre[g], norm_grad_apres=post[g],
                            delta=displacement(ps,before[g])) for g,ps in groups.items()}
            entry = dict(**row, **{k:v.item() for k,v in parts.items()}, total=total.item(), **audit,
                         norme_gradient=norm, ecrete=norm>cfg['ecretage'], groupes=stats,
                         positives=assigned, negatives=cost['negatifs'])
            journal.write(json.dumps(entry,ensure_ascii=False,allow_nan=False)+'\n')
            counts[k] += 1
            positives[k] += len(assigned)
            negatives[k] += len(cost['negatifs'])
            if step in STEPS:
                path = folder/str(step)
                control = checkpoint(model,optimizer,step,path,refs,data,counts,positives,negatives)
                check_frozen(model, fixed, frozen_tensors(model, groups))
                control.update(sha256_geles=tensor_state(current)['sha256'], sha256_groupes=group_signature(groups),
                               ecart_initial={g:displacement(ps,start_groups[g]) for g,ps in groups.items()},
                               controles_gel_apres_pas=step)
                save(path/'controle.json',control)
                controls[str(step)] = control
                print(f'Graine {seed}, pas {step}, neuf photos train évaluées.',flush=True)
    save(folder/'execution.json',dict(bras='bloc8',graine=seed,etat_initial=initial,initial_R9=cfg['initial_R9']['sha256'],
        nombre_pas=len(plan), expositions=dict(counts), positives=dict(positives), negatives=dict(negatives),
        controles=controls, vues_train_lues_aux_controles=sum(len(c['photos_evaluees']) for c in controls.values()),
        **metadata(start,cpu)))


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('seed',type=int,choices=SEEDS)
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('out',type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    with (args.out/'processus.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        execute(args.seed,args.worktrees.resolve(),args.out)
