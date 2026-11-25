"""Publier les sorties brutes et inventorier les états conservés localement."""
import argparse
from pathlib import Path
from shutil import copyfile, copyfileobj
from zipfile import ZipFile, ZipInfo

from r19.donnees import fingerprint, read, save
from r19.publier import item
from r20.corpus import ROOT, STEPS


def capsule(src,path):
    with ZipFile(path,'w') as bundle:
        for f in sorted(p for p in src.rglob('*') if p.is_file()):
            entry=ZipInfo(f.relative_to(src).as_posix())
            entry.external_attr=0o100644<<16
            with f.open('rb') as original,bundle.open(entry,'w') as dest:
                copyfileobj(original,dest)


def publish(src,out,worktrees,zip_path):
    seal=read(ROOT/'configuration.json')
    b=read(src/'B/execution.json')
    checks=b['controles']
    allowed={f'{s:04}' for s in checks}
    if checks!=list(STEPS[:len(checks)]) or {p.name for p in (src/'B').iterdir() if p.is_dir()}!=allowed:
        raise ValueError('Contrôles excédentaires ou manquants')
    inventory=[item(worktrees/seal['initial_R9']['fichier'],'Initialisation du tronc R9')]
    for seed,info in seal['checkpoints_A'].items():
        inventory.append(item(worktrees/info['fichier'],'Poids final R19 utilisé pour A, graine '+seed))
    if [v['sha256'] for v in inventory]!=[seal['initial_R9']['sha256'],*[p['sha256'] for p in seal['checkpoints_A'].values()]]:
        raise ValueError('Poids initiaux modifiés')
    for step in checks:
        folder=src/'B'/f'{step:04}'
        state=read(folder/'controle.json')
        for name,key in [('poids.pt','sha256_poids'),('optimiseur.pt','sha256_optimiseur')]:
            record=item(folder/name,f'État B à {step} mises à jour, '+('réseau' if name=='poids.pt' else 'AdamW et générateur CPU'))
            if record['sha256']!=state[key]:raise ValueError(name)
            inventory.append(record)
        for q in state['champs']:
            path=folder/'champs'/q['fichier']
            if fingerprint(path)!=q['sha256'] or path.stat().st_size!=q['octets']:raise ValueError(path)
    for path in sorted(p for p in src.rglob('*') if p.is_file() and p.suffix!='.pt'):
        dest=out/'archives'/path.relative_to(src)
        dest.parent.mkdir(parents=True,exist_ok=True)
        copyfile(path,dest)
    capsule(src,zip_path)
    inventory.append(item(zip_path,'Capsule complète des sorties A, de la trajectoire et des états B'))
    save(out/'inventaire-local.json',dict(fichiers=inventory,photos_train='configuration.json',
         sources_licences='../r18/sources.json',conservation_sources='../r18/inventaire-local.json',
         contenu_public='archives/',etats_B=checks))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('src',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('zip_path',type=Path)
    parser.add_argument('--worktrees',type=Path,default=Path('../..'))
    publish(**vars(parser.parse_args()))
