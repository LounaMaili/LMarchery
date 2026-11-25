"""Conditionnement léger des archives finales. Aucun appel au modèle."""
import argparse
from collections import Counter
import json
from pathlib import Path
from shutil import copyfile

from r19.donnees import BASELINE, INITIAL, ROOT, SEEDS, WEIGHT, fingerprint, read, save
from r19.bilan import replay


def readable(path):
    """Découper le JSON en lignes sur des séparateurs, sans changer une seule valeur."""
    s = path.read_text().strip()
    lines, start = [], 0
    quote = escape = False
    for i,ch in enumerate(s):
        if quote:
            if escape:
                escape = False
            elif ch=='\\':
                escape = True
            elif ch=='"':
                quote = False
        elif ch=='"':
            quote = True
        elif ch==',' and (i-start>=120 or (i and s[i-1]=='}')):
            lines.append(s[start:i+1]);start=i+1
    lines.append(s[start:])
    result = '\n'.join(lines)+'\n'
    if json.loads(s)!=json.loads(result):
        raise ValueError('Sérialisation non fidèle')
    path.write_text(result)


def item(path, role):
    return dict(nom=path.name,role=role,octets=path.stat().st_size,sha256=fingerprint(path),emplacement=str(path.resolve()))


def publish(experiments,out,worktrees):
    plans = read(ROOT/'tirages.json')
    resources = []
    weight = worktrees/WEIGHT
    inventory = [item(weight,'Checkpoint hérité R9, ordinary-9301')]
    if inventory[0]['sha256']!=INITIAL:
        raise ValueError('Empreinte initiale')
    (out/'apprentissage').mkdir(parents=True,exist_ok=True)
    for seed in SEEDS:
        name = str(seed)
        src = experiments/name
        meta = read(src/'execution.json')
        logs = [json.loads(s) for s in (src/'progression.jsonl').read_text().splitlines()]
        if (len(logs)!=96 or [{k:r[k] for k in ('pas','provenance','photo')} for r in logs]!=plans[name] or
            meta['final_sha256']!=fingerprint(src/'final.pt') or meta['photos_inferees']!=11 or
            meta['expositions_photos']!=Counter(r['photo'] for r in logs)):
            raise ValueError('Progression et poids incompatibles avec le plan')
        resources.append(meta)
        inventory.append(item(src/'final.pt',f'Checkpoint final R19, graine {name}, pas 96'))
        copyfile(src/'progression.jsonl',out/'apprentissage'/(name+'.jsonl'))
        dest = out/'predictions'/name
        (dest/'masques').mkdir(parents=True,exist_ok=True)
        copyfile(src/'predictions.json',dest/'predictions.json')
        readable(dest/'predictions.json')
        for png in sorted((src/'masques').glob('*.png')):
            copyfile(png,dest/'masques'/png.name)
    save(out/'executions.json',resources)
    save(out/'inventaire-local.json',dict(checkpoints=inventory, corpus='../r18/inventaire-local.json',
        reference_B=dict(fichier=str(BASELINE),sha256=fingerprint(BASELINE)),configuration=fingerprint(ROOT/'configuration.json'),
        plans=fingerprint(ROOT/'tirages.json'),donnees=fingerprint(ROOT/'donnees.json')))
    replay(out/'predictions',out)
    for path in (out/'mesures').glob('*.json'):
        readable(path)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('experiments',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('--worktrees',type=Path,default=Path('../..'))
    publish(**vars(parser.parse_args()))
