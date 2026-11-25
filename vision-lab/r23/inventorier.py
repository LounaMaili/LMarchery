"""Inventorier les deux photos publiques, les poids et les huit captures locales."""
import argparse
from pathlib import Path
from shutil import copyfile

from r19.donnees import fingerprint, read, save
from r19.publier import item
from r23.commun import ARMS, PHOTOS, ROOT, STEPS, cases, tag


def inventory(src,worktrees,out):
    config=read(ROOT/'configuration.json')
    records=[]
    for k in PHOTOS:
        spec=config['entrees'][k]['photo']
        records.append(item(worktrees/spec['fichier_reference'],'Photo publique train '+k))
        if records[-1]['sha256']!=spec['sha256_reference']:
            raise ValueError(k)
    for arm in ARMS:
        for t in STEPS:
            spec=config['poids'][arm][str(t)]
            records.append(item(worktrees/spec['fichier'],spec['role']))
            if records[-1]['sha256']!=spec['sha256']:
                raise ValueError(spec['fichier'])
    execution=read(src/'execution.json')
    for case,row in zip(cases(),execution['passages'],strict=True):
        f=src/row['fichier']
        records.append(item(f,'Trois champs et présence, '+tag(*case)))
        if fingerprint(f)!=row['sha256'] or f.stat().st_size!=row['octets']:
            raise ValueError(str(f))
    copyfile(src/'execution.json',out/'execution.json')
    save(out/'inventaire-local.json',dict(fichiers=records,grilles_publiques='cartes/README.md'))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture',type=Path)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    inventory(a.capture,a.worktrees,a.out)
