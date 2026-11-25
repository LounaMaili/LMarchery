"""Archiver la visionneuse et inventorier les fichiers de travail de R18."""
import argparse
from pathlib import Path
from shutil import copyfileobj
from zipfile import ZipFile, ZipInfo

from r14.mesurer import read, save
from r17.archiver import item

ROOT=Path('r18')


def archive(out, manifest=None, worktrees=None):
    files=[ROOT/f for f in ('visionneuse.html','perimetre.json','validites.json','effectifs.json','corrections.json',
          'compatibilite.json','champs-exclus.json','sources.json','tableaux.md','champs-exclus.md')]
    for folder in ('figures','masques','annotations'):
        files.extend((ROOT/folder).glob('*'))
    out.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(out,'w') as z:
        for file in sorted(files):
            entry=ZipInfo(file.relative_to(ROOT).as_posix())
            entry.external_attr=0o100644<<16
            with file.open('rb') as src,z.open(entry,'w') as dst:
                copyfileobj(src,dst)
    if manifest is None:
        return
    assets=[]
    for photo in read(ROOT/'perimetre.json')['acquisitions']:
        k=photo['photo']
        for field,size,sha,role in [('source_locale','taille_octets','sha256_source','Source photographique ou trame publique'),
                                   ('fichier_reference','taille_reference_octets','sha256_reference','Fichier de référence R17')]:
            if field=='fichier_reference' and photo[field]==photo['source_locale']:
                continue
            row=item(worktrees/photo[field],role)
            if row['sha256']!=photo[sha] or row['octets']!=photo[size]:
                raise ValueError(k)
            assets.append(dict(photo=k,**row))
        if photo['controle_Crigges']:
            row=item((worktrees/photo['source_locale']).with_suffix('.json'),'Annotation LabelMe source, conservée')
            if row['sha256']!=photo['source']['annotation_sha256']:
                raise ValueError(k)
            assets.append(dict(photo=k,**row))
        if 'instant_s' in photo['source']:
            row=item((worktrees/photo['source_locale']).with_name('wc82322984.webm'),'Film source de la trame à 22 s')
            if row['sha256']!=photo['source']['sha256_source']:
                raise ValueError(k)
            assets.append(dict(photo=k,**row))
    save(manifest,dict(capsule=item(out,'Corpus annoté, cartes et consultation hors ligne'),fichiers_sources=assets,
        poids_utilises=[],date='2026-11-25'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--manifest',type=Path)
    parser.add_argument('--worktrees',type=Path)
    args=parser.parse_args()
    if args.manifest and args.worktrees is None:
        parser.error('--worktrees requis pour inventorier les sources')
    archive(**vars(args))
