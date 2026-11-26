"""Copier les sorties numériques, inventorier les capsules conservées localement."""
import argparse
from pathlib import Path
from shutil import copyfile

from r19.donnees import SEEDS, read, save
from r19.publier import item
from r20.conditionner import capsule
from r21.plan import STEPS


def package(src,out,worktrees):
    config = read('r24/configuration.json')
    parity = read('r24/comparabilite.json')
    records = [item(worktrees/config['initial_R9']['fichier'],'Tronc initial R9')]
    for seed in SEEDS:
        s = str(seed)
        for step in STEPS:
            folder = src/s/str(step)
            control = read(folder/'controle.json')
            for role in ('poids','optimiseur'):
                candidate = item(folder/(role+'.pt'),f'Bloc 8, graine {seed}, pas {step}, {role}')
                old = parity['graines'][s]['etats_R22'][str(step)]['fichiers'][role]
                witness = item(worktrees/old['fichier'],f'R22 Dice, graine {seed}, pas {step}, {role}')
                if control[role+'_sha256'] != candidate['sha256'] or witness['sha256'] != old['sha256']:
                    raise ValueError('Empreinte différente '+candidate['role'])
                records.extend([candidate,witness])
        for path in sorted((src/s).rglob('*')):
            if path.is_file() and path.suffix in ('.json','.jsonl','.png'):
                dest=out/'archives/bloc8'/path.relative_to(src)
                dest.parent.mkdir(parents=True,exist_ok=True)
                copyfile(path,dest)
    archive = src.parent/'r24-etats-et-sorties.zip'
    capsule(src,archive)
    records.append(item(archive,'Trois trajectoires candidates complètes et leurs six états réseau/AdamW'))
    for spec in config['photos']:
        records.append(item(worktrees/spec['fichier_reference'],'Photo publique train '+spec['photo']))
    save(out/'inventaire-local.json',dict(fichiers=records, corpus='../r18/inventaire-local.json',
                                          sources='../r18/sources.json', reference_secondaire='../r21/inventaire-local.json'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('src',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('--worktrees',type=Path,default=Path('../..'))
    args=parser.parse_args()
    package(args.src,args.out,args.worktrees.resolve())
