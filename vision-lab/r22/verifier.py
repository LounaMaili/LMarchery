"""Vérifier poids, environnement, masses, historiques et métriques avant le premier ajustement."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path

import numpy as np
import torch

from r19.donnees import SEEDS, cell_indices, fingerprint, read, save
from r19.modele import Instances, parameter_counts
from r19.pilote import setup
from r20.corpus import references
from r20.operations import metadata, tensors
from r21.mesures import by_group, measure_views
from r21.plan import STEPS, draws, tensor_state

ROOT = Path('r22')
OLD = Path('r21')
ENV_KEYS = ('threads_torch','threads_interop','threads_cv','torch','numpy','opencv','python','machine')


def environment():
    current = metadata(0,0)
    if any(os.environ.get(key) != '1' for key in ('OPENBLAS_NUM_THREADS','OMP_NUM_THREADS')):
        raise ValueError('OpenBLAS et OMP doivent utiliser un thread')
    if not torch.are_deterministic_algorithms_enabled():
        raise ValueError('Calcul déterministe requis')
    return {key:current[key] for key in ENV_KEYS}


def initialization(seed, weight, spec):
    torch.manual_seed(seed)
    model = Instances(weight)
    model.train()
    state = tensor_state(model.state_dict())['sha256']
    if state != spec['etats_initiaux_par_graine'][str(seed)] or parameter_counts(model) != spec['parametres']:
        raise ValueError('Paramètres ou initialisation différents du témoin')
    for frozen in (model.features, model.projections):
        if any(p.requires_grad for p in frozen.parameters()) or any(m.training for m in frozen.modules()):
            raise ValueError('Tronc ou normalisation non gelés')
    return model, state


def verify(worktrees, out):
    spec = read(OLD/'configuration.json')
    registry = read(OLD/'inventaires.json')
    plans = read(OLD/'tirages.json')
    refs = references()
    if [r['spec'] for r in refs.values()] != spec['photos']:
        raise ValueError('Partition ou contenu du train différents de R21')
    weight = worktrees/spec['initial_R9']['fichier']
    if fingerprint(weight) != spec['initial_R9']['sha256']:
        raise ValueError('Poids R9 différents')
    sources = dict(spec['empreintes'])
    for path in [OLD/'configuration.json', OLD/'inventaire-local.json', Path('r19/donnees.json'),
                 *sorted(Path('r20').glob('*.py'))]:
        sources[str(path)] = fingerprint(path)
    for path, digest in sources.items():
        if fingerprint(Path(path)) != digest:
            raise ValueError(path)
    setup(SEEDS[0])
    current_env = environment()
    checks = {}
    for k,r in refs.items():
        _, target = tensors(r,worktrees)
        known = r['mask'] != 255
        cells = cell_indices(r['size'], r['grid'])
        lengths = int(r['grid'].prod())
        counts = np.bincount(cells[known], minlength=lengths)
        for i,n in enumerate(target['ids']):
            actual = (target['own'][i]+target['others'][i]+target['background']).numpy()
            own = np.bincount(cells[r['mask']==r['ids'][n]], minlength=lengths)
            if not np.array_equal(actual,counts) or not np.array_equal(target['own'][i].numpy(),own):
                raise ValueError('Masses natives différentes '+n)
        checks[k] = dict(provenance=r['spec']['provenance'], pixels_evaluables=int(known.sum()),
                         pixels_ignores=int((~known).sum()), identites=target['ids'],
                         sha256_photo=r['spec']['sha256_reference'], sha256_BGR=r['spec']['sha256_BGR_travail'],
                         sha256_annotation=r['spec']['sha256_annotation'], sha256_masque=r['spec']['sha256_masque'])
    historical = worktrees/'vision-lab-r21/vision-lab/artifacts/r21/comparaison/temoin'
    inventory = {v['role']:v for v in read(OLD/'inventaire-local.json')['fichiers']}
    controls = {}
    for seed in SEEDS:
        key = str(seed)
        folder = OLD/'archives/temoin'/key
        model, initial = initialization(seed, weight, spec)
        ledger = read(folder/'execution.json')
        plan = plans[key]
        log = [json.loads(line) for line in (folder/'progression.jsonl').read_text().splitlines()]
        if (len(plan)!=768 or plan!=draws(spec['photos'],seed) or
                plan!=[{f:r[f] for f in ('pas','photo','provenance')} for r in log] or
                ledger['expositions']!=dict(Counter(r['photo'] for r in plan)) or
                ledger['etat_initial']!=initial or ledger['initial_R9']!=spec['initial_R9']['sha256'] or
                {k:ledger[k] for k in ENV_KEYS}!=current_env):
            raise ValueError('Comparabilité des tirages, de l’état ou de l’environnement '+key)
        states = {}
        for step in STEPS:
            path = folder/str(step)
            c = read(path/'controle.json')
            files = {}
            for role in ('poids','optimiseur'):
                f = historical/key/str(step)/(role+'.pt')
                original = inventory[f'temoin, graine {seed}, pas {step}, {role}']
                if fingerprint(f)!=original['sha256'] or original['sha256']!=c[role+'_sha256'] or f.stat().st_size!=original['octets']:
                    raise ValueError(str(f))
                files[role] = dict(nom=f.name,octets=f.stat().st_size,sha256=original['sha256'],
                                   emplacement=str(f.resolve()),role=original['role'])
            state = torch.load(historical/key/str(step)/'poids.pt',map_location='cpu',weights_only=True)
            model.load_state_dict(state, strict=True)
            if tensor_state(state)['sha256']!=c['sha256_etat'] or c!=ledger['controles'][str(step)]:
                raise ValueError('État historique illisible ou divergent')
            measured = measure_views(refs, registry, path, seed)
            published = OLD/f'mesures/temoin-{seed}-{step}.json'
            old = read(published)
            if measured!=old['par_photo'] or by_group(measured,registry)!=old['groupes']:
                raise ValueError('Mesures historiques divergentes '+str(published))
            states[str(step)] = dict(etat=c['sha256_etat'], fichiers=files, photos_rejouees=len(measured),
                mesures_identiques=True, mesures=str(published), sha256_mesures=fingerprint(published))
            sources[str(published)] = fingerprint(published)
        for p in sorted(folder.rglob('*')):
            if p.is_file():
                sources[str(p)] = fingerprint(p)
        controls[key] = dict(initial=initial, presentations=len(plan), plan_identique=True, etats=states)
    result = dict(base_R21='ec5de10f1364c2f61fd0f5e98ca61e3529620849',
        preuves_R21='3a722f499c55fb208808f04c5907adeeec2ceaa2', environnement=current_env,
        initial_R9=spec['initial_R9'], photos=checks, temoins=controls, empreintes_entrees=sources,
        comparaison_autorisee=True, nouvelles_inferences=0)
    save(out,result)
    return result


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('out',type=Path,nargs='?',default=ROOT/'comparabilite.json')
    args=parser.parse_args()
    verify(args.worktrees,args.out)
