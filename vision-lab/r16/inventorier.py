"""Indexer les fichiers locaux et sceller le cache de cartes de développement."""
import argparse
from hashlib import file_digest
from pathlib import Path
from shutil import copyfileobj
from zipfile import ZipFile, ZipInfo, ZIP_STORED

from r14.mesurer import read, save


def digest(path):
    with path.open('rb') as f:
        return file_digest(f, 'sha256').hexdigest()


def item(path, role):
    return dict(nom=path.name,role=role,octets=path.stat().st_size,sha256=digest(path),
                emplacement=str(path.resolve()))


def inventory(cache, artifacts, out):
    maps=read(cache/'index.json')
    if len(maps['couples'])!=66 or maps['passages_reserves']!=66:
        raise ValueError('Budget de cartes')
    names=['index.json']
    for m in maps['couples']:
        path=cache/m['fichier']
        if path.stat().st_size!=m['octets'] or digest(path)!=m['sha256']:
            raise ValueError(m['fichier'])
        names.append(m['fichier'])
    archive=artifacts/'cartes-developpement.zip'
    # Pas de dates, permissions ou ordre dépendant de la machine dans les en-têtes.
    with ZipFile(archive,'w',compression=ZIP_STORED) as z:
        for k in sorted(names):
            info=ZipInfo(k)
            info.external_attr=0o100644 << 16
            with (cache/k).open('rb') as src, z.open(info,'w') as dst:
                copyfileobj(src,dst)
    out.mkdir(parents=True,exist_ok=True)
    save(out/'inventaire-cartes.json', dict(racine=str(cache.resolve()),role='Cartes contact et tube du développement R15',
                                          **maps))
    old=read('r15/inventaire-local.json')
    wheels=[item(p,'Roue du runtime CPU pour l’ajustement unique') for p in sorted((artifacts/'roues').glob('*.whl'))]
    if len(wheels)!=5:
        raise ValueError('Cinq roues attendues')
    save(out/'inventaire-local.json',dict(capsule_cartes=item(archive,'66 paires de cartes avec index interne'),
        index_cartes=item(out/'inventaire-cartes.json','Entrées NPZ individuelles et empreintes des canaux'),
        poids_figes=old['poids'],capsules_corpus_R15=old['capsules'],
        capsule_corpus_R14=next(i for i in read('r14/inventaire.json') if i['nom']=='r14-documentation.zip'),
        index_pixels_R14=item(Path('r14/inventaire-pixels.json'),'185 acquisitions documentées'),
        index_pixels_R15=item(Path('r15/inventaire-local.json'),'10 nouvelles acquisitions, sources et poids'),
        roues_fit=wheels))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for k in ('cache','artifacts','out'):
        p.add_argument(k,type=Path)
    inventory(**vars(p.parse_args()))
