"""Vérifier les témoins et les cibles R18 sans passage de modèle."""
import argparse
from pathlib import Path
from tempfile import TemporaryDirectory

import torch

from r19.donnees import SEEDS, fingerprint, read, save
from r20.corpus import references
from r20.operations import tensors
from r21.mesures import measure_views
from r21.plan import STEPS, tensor_state
from r22.mesures import trajectory
from r22.verifier import initialization, verify as verify_r21
from r24.adaptation import clone, configure, displacement, frozen_tensors, group_signature
from r24.diagnostic import ROOT, archived


def inputs(worktrees, refs):
    data, info = {}, {}
    registry = read('r21/inventaires.json')
    for k,ref in refs.items():
        rgb, target = tensors(ref, worktrees)
        valid = {v['instance']:v['champs'] for v in registry[k]['identites'] if v['champs']}
        masses = dict(zip(target['ids'], target['contact'].sum(1).tolist(), strict=True))
        if set(valid) != set(masses) or any(abs(v - int('entree' in valid[n])) > 2.e-6 for n,v in masses.items()):
            raise ValueError('Supervision des entrées différente '+k)
        info[k] = dict(rgb=tensor_state({'rgb':rgb})['sha256'],
                       cibles=tensor_state({n:v for n,v in target.items() if n!='ids'})['sha256'],
                       identites=target['ids'], masses_entree=masses,
                       entrees_inconnues=[n for n,v in masses.items() if v==0])
        data[k] = (rgb,target)
    if sum(len(v['entrees_inconnues']) for v in info.values()) != 5:
        raise ValueError('Dénominateur des entrées inconnues')
    return data, info


def verify(worktrees, output):
    old = read('r22/comparabilite.json')
    with TemporaryDirectory() as tmp:
        if verify_r21(worktrees, Path(tmp)/'r21.json') != old:
            raise ValueError('Comparabilité R21 divergente')
    cfg = read('r22/configuration.json')
    hashes = {**cfg['empreintes_gel'], **old['empreintes_entrees']}
    for n,digest in hashes.items():
        if fingerprint(Path(n)) != digest:
            raise ValueError('Fichier historique divergent '+n)
    refs = references()
    _, target_info = inputs(worktrees, refs)
    plans = read('r21/tirages.json')
    registry = read('r21/inventaires.json')
    catalog = {r['role']:r for r in read('r22/inventaire-local.json')['fichiers']}
    controls, layout = {}, {}
    for seed in SEEDS:
        s = str(seed)
        folder = Path('r22/archives/dice')/s
        ledger = read(folder/'execution.json')
        model, initial = initialization(seed, worktrees/cfg['initial_R9']['fichier'], cfg)
        groups = configure(model)
        layout = {g:{n:dict(dimensions=list(p.shape), elements=p.numel()) for n,p in ps.items()} for g,ps in groups.items()}
        frozen = tensor_state(frozen_tensors(model, groups))
        initial_groups = group_signature(groups)
        start_groups = {g:clone(ps) for g,ps in groups.items()}
        if ledger['etat_initial'] != initial or ledger['initial_R9'] != cfg['initial_R9']['sha256']:
            raise ValueError('Initialisation différente '+s)
        if trajectory(folder,seed,'dice',registry,plans[s],old) != read('r22/pertes-et-expositions.json')['dice'][s]:
            raise ValueError('Pertes ou expositions différentes '+s)
        states = {}
        for step in STEPS:
            control = ledger['controles'][str(step)]
            files = {}
            for role in ('poids','optimiseur'):
                item = catalog[f'Dice {seed}, pas {step}, {role}']
                original = worktrees/'vision-lab-r22/vision-lab/artifacts/r22/dice'/s/str(step)/(role+'.pt')
                if (original.stat().st_size != item['octets'] or fingerprint(original) != item['sha256']
                        or item['sha256'] != control[role+'_sha256']):
                    raise ValueError('Capsule historique différente '+str(original))
                files[role] = dict(fichier=str(original.relative_to(worktrees)), octets=item['octets'], sha256=item['sha256'])
            state = torch.load(worktrees/files['poids']['fichier'], map_location='cpu', weights_only=True)
            model.load_state_dict(state,strict=True)
            if (tensor_state(state)['sha256'] != control['sha256_etat'] or
                    tensor_state(frozen_tensors(model,groups))['sha256'] != frozen['sha256'] or
                    group_signature(groups)['bloc8'] != initial_groups['bloc8']):
                raise ValueError('État R22 illisible')
            saved = torch.load(worktrees/files['optimiseur']['fichier'],map_location='cpu',weights_only=True)
            opt = saved['optimiseur']
            settings = opt['param_groups'][0]
            expected = cfg['optimiseur']
            if (saved['pas']!=step or len(opt['param_groups'])!=1 or
                    any(int(v['step'])!=step for v in opt['state'].values()) or
                    len(opt['state'])!=len(groups['tete']) or list(settings['betas'])!=expected['betas'] or
                    any(settings[k]!=expected[k] for k in ('lr','eps','weight_decay','foreach'))):
                raise ValueError('État AdamW R22 différent')
            raw = Path(f'r22/mesures/dice-{seed}-{step}.json')
            if measure_views(refs,registry,folder/str(step),seed) != read(raw):
                raise ValueError('Mesures R22 divergentes '+str(raw))
            states[str(step)] = dict(fichiers=files, etat=control['sha256_etat'], mesures_identiques=9,
                ecart_initial={g:displacement(ps,start_groups[g]) for g,ps in groups.items()})
            hashes[str(raw)] = fingerprint(raw)
        for path in sorted(folder.rglob('*')):
            if path.is_file():
                hashes[str(path)] = fingerprint(path)
        known = {name for ref in refs.values() for name in ref['entries']}
        coverage = {}
        for arm in ('R21','R22'):
            _, rows = archived(arm,seed,768)
            coverage[arm] = {c['instance'] for row in rows.values() for c in row['couverture']['paires']}
        union = coverage['R21'] | coverage['R22']
        if len(known)!=26 or union!=known:
            raise ValueError('Union historique différente '+s)
        controls[s] = dict(initial=initial, groupes_initiaux=initial_groups, gel_initial=frozen['sha256'],
                           union_entrees_finales=sorted(union),
                           tenseurs_geles=len(frozen['tenseurs']), etats_R22=states, presentations=len(plans[s]))
    for path in ('r22/configuration.json','r22/comparabilite.json','r21/configuration.json','r18/sources.json',
                 'r21/tirages.json','r7/model.py','r21/publier.py','r22/mesures.py','r22/lecture.py'):
        hashes[path] = fingerprint(Path(path))
    save(output,dict(environnement=old['environnement'], groupes=layout, graines=controls, cibles=target_info,
                     empreintes_historiques=hashes, nouveaux_passages=0, comparabilite_verifiee=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('out',type=Path,nargs='?',default=ROOT/'comparabilite.json')
    args = parser.parse_args()
    verify(args.worktrees.resolve(), args.out)
