"""Les 27 lectures A et la trajectoire B, sur les seuls pixels train scellés."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import platform
import resource
import sys
import time

import cv2 as cv
import numpy as np
import torch

from r19.decoder import decode
from r19.donnees import INITIAL, SEEDS, archive, fingerprint, load_image, read, save
from r19.mesures import candidate
from r19.modele import Instances, parameter_counts, photograph
from r19.pilote import setup
from r19.publier import readable
from r19.supervision import losses, prepare
from r20.champs import CHANNELS, field_summary
from r20.corpus import ROOT, STEPS, MEMORY_SEED, frozen, references
from r20.instrumenter import diagnose, grad_groups
from r20.mesurer import photo


def check_inputs(worktrees):
    seal,ceilings=frozen()
    if seal!=read(ROOT/'configuration.json') or ceilings!=read(ROOT/'plafonds.json'):
        raise ValueError('Le gel des références a changé')
    for cp in [seal['initial_R9'],*seal['checkpoints_A'].values()]:
        if fingerprint(worktrees/cp['fichier'])!=cp['sha256']:
            raise ValueError(cp['fichier'])
    return seal,ceilings


def tensors(ref,worktrees):
    frame=load_image(ref['spec'],worktrees)
    target=prepare(ref['spec'],ref['record'],ref['annotation'],ref['mask'])
    return photograph(frame),target


def save_prediction(fields,presence,rgb,ref,folder):
    # Le modèle a déjà rendu les champs à partir du seul tenseur RGB. Aucune cible dans le décodage.
    record,bits=decode(fields.detach(),presence.detach(),rgb.shape[-1:-3:-1],ref['size'])
    folder.mkdir(parents=True,exist_ok=True)
    key=ref['spec']['photo']
    if not cv.imwrite(str(folder/(key+'.png')),bits,[cv.IMWRITE_PNG_COMPRESSION,9]):
        raise OSError(key)
    record['sha256_plan_uint16_le']=sha256(bits.astype('<u2').tobytes()).hexdigest()
    return record


def write_predictions(folder,records,costs):
    for name,value in [('predictions',records),('pertes',costs)]:
        path=folder/(name+'.json')
        archive(path,value);readable(path)


def metadata(start,cpu):
    return dict(duree_murale_s=time.monotonic()-start,cpu_processus_s=time.process_time()-cpu,
                rss_max_Kio=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,threads_torch=torch.get_num_threads(),
                threads_interop=torch.get_num_interop_threads(),threads_cv=cv.getNumThreads(),torch=str(torch.__version__),
                numpy=np.__version__,opencv=cv.__version__,python=sys.version.split()[0],machine=platform.machine())


@torch.inference_mode()
def operation_a(worktrees,out):
    seal,_=check_inputs(worktrees)
    setup(MEMORY_SEED)
    run=out/'A'
    run.mkdir(parents=True,exist_ok=False)
    start,cpu=time.monotonic(),time.process_time()
    refs=references()
    data={k:tensors(r,worktrees) for k,r in refs.items()}
    ledger=[]
    for seed in SEEDS:
        dest=run/str(seed)
        cp=seal['checkpoints_A'][str(seed)]
        model=Instances()
        model.load_state_dict(torch.load(worktrees/cp['fichier'],weights_only=True,map_location='cpu'),strict=True)
        model.eval()
        records,costs={},{}
        for k,ref in refs.items():
            rgb,target=data[k]
            before=time.monotonic()
            fields,presence=model(rgb)
            record=save_prediction(fields,presence,rgb,ref,dest/'masques')
            seconds=time.monotonic()-before
            total,parts,audit=losses(fields,presence,target)
            costs[k]=diagnose(fields,presence,target,parts,audit)
            records[k]=record
            ledger.append(dict(graine=seed,photo=k,sha256_checkpoint=cp['sha256'],passage=len(ledger)+1,
                               inference_decodage_ecriture_masque_s=seconds,perte_observee=total.item()))
        write_predictions(dest,records,costs)
    if len(ledger)!=27:
        raise ValueError('Budget A')
    save(run/'execution.json',dict(lectures=ledger,mises_a_jour=0,**metadata(start,cpu)))
    print('A',len(ledger),'passages',flush=True)


def checkpoint(step,model,optimizer,folder,ref,rgb,fields,presence,parts,audit,target,norm,groups,cap,threshold):
    k=ref['spec']['photo']
    folder.mkdir(parents=True,exist_ok=False)
    weights=folder/'poids.pt'
    torch.save(model.state_dict(),weights)
    torch.save(dict(pas=step,optimiseur=optimizer.state_dict(),rng_cpu=torch.get_rng_state()),folder/'optimiseur.pt')
    cost=diagnose(fields.detach(),presence.detach(),target,parts,audit)
    record=save_prediction(fields,presence,rgb,ref,folder/'masques')
    write_predictions(folder,{k:record},{k:cost})
    grid=record['grille']
    dense=fields.detach().numpy().reshape(12,3,grid[1],grid[0])
    raw=folder/'champs'
    raw.mkdir()
    chunks=[]
    for q,planes in enumerate(dense):
        path=raw/f'q{q:02}.npz'
        np.savez_compressed(path,**dict(zip(CHANNELS,planes,strict=True)))
        chunks.append(dict(q=q,fichier=path.name,octets=path.stat().st_size,sha256=fingerprint(path),
                           sha256_plans_float32_le=sha256(planes.astype('<f4').tobytes()).hexdigest()))
    archive(folder/'similarites.json',field_summary(dense));readable(folder/'similarites.json')
    report=photo(ref,candidate({k:ref},folder)[k],cap,threshold,cost)
    info=report['bilan']
    success=info['triplets']==info['instances_completes']
    progress=dict(pas=step,sha256_poids=fingerprint(weights),sha256_optimiseur=fingerprint(folder/'optimiseur.pt'),
                  champs=chunks,gradient_avant_ecretage=norm,normes_par_groupe=groups,
                  conjoint=info['triplets'],completes=info['instances_completes'],succes=success)
    save(folder/'controle.json',progress)
    print('B',step,info['triplets'],'/',info['instances_completes'],flush=True)
    return progress


def operation_b(worktrees,out):
    seal,ceilings=check_inputs(worktrees)
    setup(MEMORY_SEED)
    run=out/'B'
    run.mkdir(parents=True,exist_ok=False)
    start,cpu=time.monotonic(),time.process_time()
    key=seal['photo_B'];ref=references()[key]
    model=Instances(worktrees/seal['initial_R9']['fichier'])
    if parameter_counts(model)!=read('r19/configuration.json')['parametres']:
        raise ValueError('Architecture différente')
    model.train()
    trainable=[p for p in model.parameters() if p.requires_grad]
    optimizer=torch.optim.AdamW(trainable,lr=.001,betas=(.9,.999),eps=1.e-8,weight_decay=.01,foreach=False)
    rgb,target=tensors(ref,worktrees)
    checks=[]
    with (run/'progression.jsonl').open('x',buffering=1) as log:
        for step in range(STEPS[-1]+1):
            before=time.monotonic()
            optimizer.zero_grad(set_to_none=True)
            fields,presence=model(rgb)
            total,parts,audit=losses(fields,presence,target)
            if not torch.isfinite(total):
                raise ValueError('Perte non finie')
            total.backward()
            groups=grad_groups(model) if step in STEPS else {}
            norm=float(torch.nn.utils.clip_grad_norm_(trainable,5.,error_if_nonfinite=True))
            if step in STEPS:
                check=checkpoint(step,model,optimizer,run/f'{step:04}',ref,rgb,fields,presence,parts,audit,target,
                                 norm,groups,ceilings[key]['instances'],seal['checkpoints_A'][str(MEMORY_SEED)]['seuil_R19'])
                checks.append(check)
                if check['succes'] or step==STEPS[-1]:
                    break
            optimizer.step()
            row=dict(pas=step+1,etat_avant=step,total=total.item(),**{k:p.item() for k,p in parts.items()},
                     **audit,norme_gradient=norm,ecrete=norm>5.,duree_s=time.monotonic()-before)
            log.write(json.dumps(row,allow_nan=False)+'\n')
    summary=dict(photo=key,graine=MEMORY_SEED,sha256_depart=INITIAL,mises_a_jour=step,passages_avant=step+1,
                 controles=[c['pas'] for c in checks],premier_succes=step if checks[-1]['succes'] else None,
                 anomalies_non_finies=0,**metadata(start,cpu))
    save(run/'execution.json',summary)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['A','B'])
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('out',type=Path)
    a=parser.parse_args()
    (operation_a if a.operation=='A' else operation_b)(a.worktrees,a.out)
