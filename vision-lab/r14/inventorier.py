"""Créer la capsule documentaire locale et inventorier les entrées effectivement conservées."""
import argparse
from hashlib import sha256
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED,ZipInfo
from r14.mesurer import read,save


def inventory(worktrees,out):
    worktrees=worktrees.resolve()
    lab=worktrees/'vision-lab-r14/vision-lab'
    data=lab/'data/r14'
    capsule=lab/'artifacts/r14-documentation.zip'
    capsule.parent.mkdir(parents=True,exist_ok=True)
    sources=[data/'candidats.json',*sorted(data.glob('q*.html')),*sorted((data/'media').glob('*')),
             *sorted((data/'lecture').glob('*.jpg')),*sorted((data/'banque').glob('*'))]
    with ZipFile(capsule,'w') as z:
        for f in sources:
            info=ZipInfo(f.relative_to(data).as_posix(),date_time=(2026,11,24,0,0,0))
            info.compress_type=ZIP_DEFLATED
            z.writestr(info,f.read_bytes())
    files=[(capsule,'Collecte R14 et supports de lecture assistée locaux')]
    for n,name in [(7,'pilot/annotations.json'),(7,'external.json'),(8,'corpus.json'),(9,'dev.json')]:
        files.append((worktrees/f'vision-lab-r{n}/vision-lab/data/r{n}'/name,'Manifeste historique; seules lignes train/dév utilisées'))
    for n,rows in [(12,read('r12/nouvelles.json')['photos']),(13,read('r13/collecte.json')['lectures']),
                   (14,read('r14/collecte.json')['lectures'])]:
        for row in rows:
            files.append((worktrees/f'vision-lab-r{n}/vision-lab/data/r{n}/media'/(row['id']+'.jpg'),f'Acquisition publique R{n}'))
    for n in ('ordinary','targeted'):
        for seed in (9301,9302,9303):
            files.append((worktrees/f'vision-lab-r9/vision-lab/artifacts/r9/train/{n}-{seed}.pt','Poids R9, référence des sorties R13'))
    save(out,[dict(nom=f.name,role=role,emplacement=str(f),octets=f.stat().st_size,sha256=sha256(f.read_bytes()).hexdigest())
              for f,role in files])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    inventory(a.worktrees,a.out)
