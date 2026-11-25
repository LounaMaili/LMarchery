"""Huit lectures uniques. Les trois champs bruts sont conservés avant toute mesure."""
import argparse
from hashlib import sha256
from pathlib import Path
import time

import numpy as np
import torch

from r19.donnees import fingerprint, read, save
from r19.modele import Instances
from r19.pilote import setup
from r20.operations import metadata
from r21.plan import tensor_state
from r22.verifier import environment
from r23.commun import ARMS, PHOTOS, ROOT, SEED, STEPS, tag
from r23.perimetre import describe_inputs, inputs, references, weights


@torch.inference_mode()
def capture(worktrees, dest):
    config=read(ROOT/'configuration.json')
    setup(SEED)
    if environment()!=config['environnement'] or weights(worktrees)!=config['poids']:
        raise ValueError('Environnement ou poids différents du gel')
    for name,digest in config['empreintes'].items():
        if fingerprint(Path(name))!=digest:
            raise ValueError(name)
    refs=references()
    data=inputs(worktrees,refs)
    if describe_inputs(data,refs)!=config['entrees']:
        raise ValueError('Transformations ou cibles différentes')
    dest.mkdir(parents=True,exist_ok=False)
    start,cpu=time.monotonic(),time.process_time()
    ledger=[]
    for arm in ARMS:
        for step in STEPS:
            spec=config['poids'][arm][str(step)]
            model=Instances()
            model.load_state_dict(torch.load(worktrees/spec['fichier'],map_location='cpu',weights_only=True),strict=True)
            model.eval()
            if tensor_state(model.state_dict())['sha256']!=spec['etat_tensoriel'] or any(m.training for m in model.modules()):
                raise ValueError('État ou mode différent')
            for k in PHOTOS:
                name=tag(arm,step,k)
                folder=dest/name
                folder.mkdir()
                save(folder/'appel.json',dict(numero=len(ledger)+1,bras=arm,pas=step,photo=k))
                rgb,_=data[k]
                fields,presence=model(rgb)
                if (torch.is_grad_enabled() or fields.requires_grad or presence.requires_grad or
                        not torch.isfinite(fields).all() or not torch.isfinite(presence).all()):
                    raise ValueError('Sortie hors protocole')
                path=folder/'champs.npz'
                np.savez_compressed(path,champs=fields.numpy(),presence=presence.numpy())
                ledger.append(dict(bras=arm,pas=step,photo=k,numero=len(ledger)+1,fichier=str(path.relative_to(dest)),
                     octets=path.stat().st_size,sha256=fingerprint(path),forme=list(fields.shape),
                     sha256_champs_f32=sha256(fields.numpy().astype('<f4').tobytes()).hexdigest()))
            if tensor_state(model.state_dict())['sha256']!=spec['etat_tensoriel']:
                raise ValueError('Poids modifiés pendant la lecture')
    save(dest/'execution.json',dict(passages=ledger,nombre=len(ledger),graine=SEED,gradient=False,ajustements=0,
                                   **metadata(start,cpu)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('destination_neuve',type=Path)
    args=p.parse_args()
    capture(args.worktrees,args.destination_neuve)
