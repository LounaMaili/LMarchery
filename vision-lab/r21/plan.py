"""Plan partagé et empreintes indépendantes du format de sérialisation Torch."""
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path
import json

import numpy as np

from r19.donnees import SEEDS, fingerprint, read, save
from r20.rejouer import compact
from r21.inventorier import ROOT

ARMS=('temoin','candidat')
STEPS=(96,768)


def draws(photos,seed):
    groups=defaultdict(list)
    for p in photos:
        if p['partition']!='train':raise ValueError('Seul le train est admis')
        groups[p['provenance']].append(p['photo'])
    if len(groups)!=8 or len(photos)!=9:raise ValueError('Périmètre différent')
    rng=np.random.default_rng(seed)
    rows=[]
    for cycle in range(96):
        for group in rng.permutation(sorted(groups)):
            keys=sorted(groups[group])
            rows.append(dict(pas=len(rows)+1,provenance=str(group),photo=keys[cycle%len(keys)]))
    return rows


def tensor_state(state):
    tensors={}
    for name,t in sorted(state.items()):
        a=t.detach().cpu().contiguous().numpy()
        tensors[name]=dict(type=str(a.dtype),forme=list(a.shape),sha256=sha256(a.tobytes()).hexdigest())
    digest=sha256(json.dumps(tensors,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return dict(sha256=digest,tenseurs=tensors)


def freeze(worktrees):
    import torch
    from r19.modele import Instances,parameter_counts
    from r19.pilote import setup
    spec=read('r20/configuration.json')
    registry=read(ROOT/'inventaires.json')
    plans={str(s):draws(spec['train'],s) for s in SEEDS}
    old=read('r19/tirages.json')
    assert all(v[:96]==old[k] and len(v)==768 for k,v in plans.items())
    certified=[k for k,v in registry.items() if v['admissible']]
    multi=[k for k in certified if registry[k]['actives']>1]
    if len({registry[k]['provenance'] for k in multi})<2:raise ValueError('Porte de données fermée')
    compact(ROOT/'tirages.json',plans)
    setup(SEEDS[0])
    start=spec['initial_R9'];weight=worktrees/start['fichier']
    if fingerprint(weight)!=start['sha256']:raise ValueError(weight)
    initials={}
    for s in SEEDS:
        torch.manual_seed(s)
        model=Instances(weight)
        initials[str(s)]=tensor_state(model.state_dict())['sha256']
        torch.manual_seed(s)
        second=Instances(weight)
        if initials[str(s)]!=tensor_state(second.state_dict())['sha256']:raise ValueError('Initialisations divergentes')
    config=dict(base_R20='17eac118306a1c37e44343fe06c47f7d90888e8a',preuves_R20='46ae03b871bf6d1579dff96de4d32f7db64d959b',
        bras=list(ARMS),graines=list(SEEDS),pas=list(STEPS),decision_pas=768,initial_R9=start,
        etats_initiaux_par_graine=initials,poids_temoin_historiques=spec['checkpoints_A'],
        admises=certified,partielles=[k for k in registry if k not in certified],multifleches_admises=multi,
        portes_franchies=True,photos=spec['train'],taille_lot=1,cycles=96,expositions=Counter(r['photo'] for r in plans['9301']),
        seuil_presence=.5,requêtes=12,parametres=parameter_counts(model),architectures_pertes='r19/configuration.json',
        poids_pertes=dict(masque=1,axe=1,entree=1,presence=.25),optimiseur=dict(nom='AdamW',lr=.001,betas=[.9,.999],eps=1.e-8,weight_decay=.01,foreach=False),
        ecretage=5,torch_threads=4,interop=1,opencv_threads=1,blas_threads=1,
        empreintes={str(p):fingerprint(p) for p in [ROOT/'inventaires.json',ROOT/'tirages.json','r18/validites.json',
            'r19/configuration.json','r19/tirages.json','r20/plafonds.json',*[Path('r19')/n for n in spec['code_R19']],
            *[ROOT/n for n in ['inventorier.py','regle.py','plan.py','mesures.py','executer.py']]]})
    save(ROOT/'configuration.json',config)


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('worktrees',type=Path)
    freeze(p.parse_args().worktrees)
