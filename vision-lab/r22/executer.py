"""Trois trajectoires candidates autorisées, une graine et deux contrôles par invocation."""
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
from r20.operations import metadata, save_prediction, tensors, write_predictions
from r21.plan import STEPS, tensor_state
from r22.regle import losses
from r22.verifier import OLD, ROOT, environment, initialization


@torch.inference_mode()
def checkpoint(model, optimizer, step, folder, refs, data, counts, positives, negatives):
    folder.mkdir()
    torch.save(model.state_dict(),folder/'poids.pt')
    torch.save(dict(optimiseur=optimizer.state_dict(),rng_cpu=torch.get_rng_state(),pas=step),folder/'optimiseur.pt')
    digest = tensor_state(model.state_dict())['sha256']
    rng = torch.get_rng_state()
    records, costs = {}, {}
    model.eval()
    for photo,ref in refs.items():
        rgb,target = data[photo]
        fields,presence = model(rgb)
        records[photo] = save_prediction(fields,presence,rgb,ref,folder/'masques')
        _,_,_,costs[photo] = losses(fields,presence,target)
    write_predictions(folder,records,costs)
    model.train()
    if not torch.equal(rng,torch.get_rng_state()) or tensor_state(model.state_dict())['sha256']!=digest:
        raise ValueError('Évaluation modifiant la trajectoire')
    control = dict(pas=step, sha256_etat=digest, poids_sha256=fingerprint(folder/'poids.pt'),
                   optimiseur_sha256=fingerprint(folder/'optimiseur.pt'), expositions=dict(counts),
                   positives=dict(positives), negatives=dict(negatives), photos_evaluees=list(records))
    save(folder/'controle.json',control)
    return control


def execute(seed, worktrees, out):
    config = read(ROOT/'configuration.json')
    for path,digest in config['empreintes_gel'].items():
        if fingerprint(Path(path))!=digest:
            raise ValueError('Gel modifié '+path)
    parity = read(ROOT/'comparabilite.json')
    for path,digest in parity['empreintes_entrees'].items():
        if fingerprint(Path(path))!=digest:
            raise ValueError('Référence différente '+path)
    setup(seed)
    spec = read(OLD/'configuration.json')
    state = parity['temoins'][str(seed)]
    if not parity['comparaison_autorisee'] or environment()!=parity['environnement']:
        raise ValueError('Comparabilité numérique')
    weight = worktrees/spec['initial_R9']['fichier']
    if fingerprint(weight)!=spec['initial_R9']['sha256']:
        raise ValueError('Poids de départ différents')
    model,initial = initialization(seed,weight,spec)
    refs = references()
    plan = read(OLD/'tirages.json')[str(seed)]
    if (list(refs)!=list(parity['photos']) or state['initial']!=initial or len(plan)!=768 or
            len(refs)!=9 or state['presentations']!=len(plan) or not state['plan_identique']):
        raise ValueError('Train, initialisation ou plan différents')
    data = {k:tensors(r,worktrees) for k,r in refs.items()}
    params = [p for p in model.parameters() if p.requires_grad]
    opt = spec['optimiseur']
    optimizer = torch.optim.AdamW(params,lr=opt['lr'],betas=tuple(opt['betas']),eps=opt['eps'],
                                  weight_decay=opt['weight_decay'],foreach=opt['foreach'])
    folder = out/str(seed)
    folder.mkdir(exist_ok=False)
    start,cpu = time.monotonic(),time.process_time()
    counts,positives,negatives = Counter(),Counter(),Counter()
    controls = {}
    with (folder/'progression.jsonl').open('x',buffering=1) as journal:
        for row in plan:
            k = row['photo']
            rgb,target = data[k]
            optimizer.zero_grad(set_to_none=True)
            fields,presence = model(rgb)
            total,parts,audit,cost = losses(fields,presence,target,config['coefficient_dice'])
            if not torch.isfinite(total):
                raise ValueError('Perte non finie')
            total.backward()
            norm = float(torch.nn.utils.clip_grad_norm_(params,spec['ecretage'],error_if_nonfinite=True))
            optimizer.step()
            assigned = {r['instance']:r['q'] for r in cost['par_identite']}
            obs = dict(**row,**{f:v.item() for f,v in parts.items()},total=total.item(),norme_gradient=norm,
                       ecrete=norm>spec['ecretage'],**audit,positives=assigned,negatives=cost['negatifs'])
            journal.write(json.dumps(obs,ensure_ascii=False,allow_nan=False)+'\n')
            counts[k] += 1
            positives[k] += len(assigned)
            negatives[k] += len(cost['negatifs'])
            step = row['pas']
            if step in STEPS:
                controls[str(step)] = checkpoint(model,optimizer,step,folder/str(step),refs,data,counts,positives,negatives)
                print(f'Graine {seed}, pas {step}, {len(refs)} lectures train.',flush=True)
    save(folder/'execution.json',dict(bras='dice',graine=seed,etat_initial=initial,
         initial_R9=spec['initial_R9']['sha256'], nombre_pas=len(plan), expositions=dict(counts),
         positives=dict(positives),negatives=dict(negatives),controles=controls,
         vues_train_lues_aux_controles=sum(len(c['photos_evaluees']) for c in controls.values()),**metadata(start,cpu)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('seed',type=int,choices=SEEDS)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True)
    with (a.out/'processus.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        execute(a.seed,a.worktrees,a.out)
