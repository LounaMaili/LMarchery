"""Publier seulement les sorties numériques et inventorier les états lourds."""
import argparse
from pathlib import Path
from shutil import copyfile

from r19.donnees import SEEDS, read, save
from r19.publier import item
from r20.conditionner import capsule
from r21.plan import STEPS


def package(src, out, worktrees):
    parity=read('r22/comparabilite.json')
    records=[item(worktrees/parity['initial_R9']['fichier'],'Tronc initial commun R9')]
    for s in map(str,SEEDS):
        folder=src/s
        for step in map(str,STEPS):
            c=read(folder/step/'controle.json')
            records.extend(parity['temoins'][s]['etats'][step]['fichiers'].values())
            for role in ('poids','optimiseur'):
                record=item(folder/step/(role+'.pt'),f'Dice {s}, pas {step}, {role}')
                if c[role+'_sha256']!=record['sha256']:
                    raise ValueError(record['role'])
                records.append(record)
        for f in sorted(folder.rglob('*')):
            if f.is_file() and f.suffix in ('.json','.jsonl','.png'):
                dest=out/'archives/dice'/f.relative_to(src)
                dest.parent.mkdir(parents=True,exist_ok=True)
                copyfile(f,dest)
    archive=src.parent/'r22-etats-et-sorties.zip'
    capsule(src,archive)
    records.append(item(archive,'Trois trajectoires candidates, six états réseau/optimiseur et sorties numériques'))
    for p in read('r21/configuration.json')['photos']:
        records.append(item(worktrees/p['fichier_reference'],'Photo publique train '+p['photo']))
    save(out/'inventaire-local.json',dict(fichiers=records,corpus_R18='../r18/inventaire-local.json',
                                          provenance_et_licences='../r18/sources.json'))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('src',type=Path)
    p.add_argument('out',type=Path)
    p.add_argument('--worktrees',type=Path,default=Path('../..'))
    a=p.parse_args()
    package(a.src,a.out,a.worktrees)
