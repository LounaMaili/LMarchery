"""Capturer une fois les 21 champs manquants et reprendre le vingt-deuxième de R23."""
import argparse
import fcntl
from pathlib import Path
from shutil import copyfile
import time

import numpy as np
import torch

from r19.donnees import fingerprint, read, save
from r19.modele import Instances
from r19.pilote import setup
from r20.corpus import references
from r20.operations import metadata
from r21.plan import tensor_state
from r22.verifier import environment
from r25.commun import ROOT, REUSE, captures, tag
from r25.perimetre import reused, rgb_inputs, weights


@torch.inference_mode()
def run(worktrees,dest):
    setup(9301)
    config=read(ROOT/'configuration.json')
    for name,h in config['empreintes_gel'].items():
        if fingerprint(Path(name))!=h:
            raise ValueError('Gel différent '+name)
    refs=references()
    rgbs,inputs=rgb_inputs(worktrees,refs)
    if (environment()!=config['environnement'] or inputs!=config['photos'] or weights(worktrees)!=config['poids']
            or reused(worktrees)!=config['reemploi']):
        raise ValueError('Environnement, données ou poids différents du protocole')
    dest.mkdir(parents=True,exist_ok=False)
    start,cpu=time.monotonic(),time.process_time()
    states,records={},[]
    for key,record in config['poids'].items():
        model=Instances()
        model.load_state_dict(torch.load(worktrees/record['fichier'],weights_only=True,map_location='cpu'),strict=True)
        model.eval()
        if any(m.training for m in model.modules()) or tensor_state(model.state_dict())['sha256']!=record['etat']:
            raise ValueError('État différent '+key)
        states[key]=model
    for index,case in enumerate(captures(),1):
        arm,seed,k=case
        key=f'{arm}-{seed}'
        folder=dest/tag(*case)
        folder.mkdir()
        path=folder/'champs.npz'
        fresh=case!=REUSE
        event=dict(numero=index,bras=arm,graine=seed,photo=k,appel_modele=fresh)
        save(folder/'operation.json',event)
        if fresh:
            fields,presence=states[key](rgbs[k])
            if (torch.is_grad_enabled() or fields.requires_grad or presence.requires_grad or
                    not torch.isfinite(fields).all() or not torch.isfinite(presence).all()):
                raise ValueError('Champs non conformes')
            np.savez_compressed(path,champs=fields.numpy(),presence=presence.numpy())
        else:
            copyfile(worktrees/config['reemploi']['fichier'],path)
        records.append(dict(**event,nom=str(path.relative_to(dest)),octets=path.stat().st_size,
                            sha256=fingerprint(path),poids=config['poids'][key]['sha256']))
    if (sum(r['appel_modele'] for r in records)!=21 or len(records)!=22 or
            any(tensor_state(m.state_dict())['sha256']!=config['poids'][key]['etat'] for key,m in states.items())):
        raise ValueError('Budget ou état final divergent')
    save(dest/'execution.json',dict(cas=records,appels=21,reemplois=1,ajustements=0,gradient=False,
                                    lectures_developpement=0,lectures_test=0,**metadata(start,cpu)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('capture',type=Path)
    args=p.parse_args()
    args.capture.parent.mkdir(parents=True,exist_ok=True)
    with (args.capture.parent/'processus.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        run(args.worktrees.resolve(),args.capture)
