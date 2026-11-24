"""Sceller la visionneuse hors ligne et référencer les actifs locaux effectivement utilisés."""
import argparse
from hashlib import file_digest
from pathlib import Path
from shutil import copyfileobj
from zipfile import ZipFile,ZipInfo

from r14.mesurer import read,save
from r15.corpus import corpus

LAB=Path('r17')


def item(path,role):
    with path.open('rb') as stream:
        sha=file_digest(stream,'sha256').hexdigest()
    return dict(nom=path.name,role=role,emplacement=str(path.resolve()),octets=path.stat().st_size,sha256=sha)


def archive(zpath,manifest=None,worktrees=None):
    zpath.parent.mkdir(parents=True,exist_ok=True)
    files=[LAB/'visionneuse.html',*(LAB/'figures'/n for n in ('calques.js','index.json','details.json')),
           *sorted((LAB/'figures').glob('*.jpg'))]
    with ZipFile(zpath,'w') as z:
        for p in sorted(files):
            header=ZipInfo(str(p.relative_to(LAB)))
            header.external_attr=0o100644<<16
            with p.open('rb') as f,z.open(header,'w') as dst:
                copyfileobj(f,dst)
    if manifest is None:
        return
    from r16.donnees import photo_paths
    from r15.restaurer import historique
    ids={c['photo'] for c in read(LAB/'cas-geles.json')['cas']}
    photos={p['id']:p for p in corpus() if p['id'] in ids}
    paths=photo_paths()
    pixels=[]
    for k,p in photos.items():
        x=item(historique(paths[k],worktrees),'Source photographique publique de lecture')
        if x['sha256']!=p['sha256_pixels']:
            raise ValueError(k)
        pixels.append(dict(photo=k,**x))
    old=read('r16/inventaire-local.json')
    save(manifest,dict(capsule=item(zpath,'Visionneuse hors ligne, planches, loupes et crédits'),
        photos_relues=pixels,annotations_historiques=read(LAB/'historique-sequences.json')['sources'],
        poids_documentaire=next(p for p in old['poids_figes'] if p['nom']=='ordinary-9301.pt'),
        corpus_R14=old['capsule_corpus_R14'],corpus_R15=old['capsules_corpus_R15'],
        cartes_R16='Aucun accès aux cartes, seules les sorties publiques ordinary-9301 ont été utilisées.'))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('zip',type=Path);p.add_argument('manifeste',type=Path,nargs='?')
    p.add_argument('--worktrees',type=Path)
    a=p.parse_args();archive(a.zip,a.manifeste,a.worktrees)
