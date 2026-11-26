"""Rendre les deux cartes float32 par requête accessibles et inventorier les capsules locales."""
import argparse
from pathlib import Path
import numpy as np

from r19.donnees import fingerprint, read, save
from r19.publier import item
from r25.commun import ROOT, captures, tag
from r25.parite import sha_array


def export(src,out,worktrees):
    proof=read(out/'parite.json')
    if not proof['parite_complete'] or len(proof['cas'])!=22:
        raise ValueError('Parité manquante')
    ledger=read(src/'execution.json')
    config=read(ROOT/'configuration.json')
    registry={}
    items=[]
    for case,r in zip(captures(),ledger['cas'],strict=True):
        name=tag(*case)
        folder=out/'cartes'/name
        folder.mkdir(parents=True,exist_ok=True)
        p=src/r['nom']
        item_raw=item(p,'Trois cartes et présence '+name)
        if proof['cas'][name]['capture']!=item_raw['sha256']:
            raise ValueError('Champs différents après parité')
        items.append(item_raw)
        with np.load(p,allow_pickle=False) as raw:
            fields=raw['champs']
        if sha_array(fields,'<f4')!=proof['cas'][name]['champs_float32']:
            raise ValueError('Tenseurs divergents')
        w,h=config['photos'][case[2]]['grille']
        files=[]
        for q,row in enumerate(fields):
            m,a=(np.ascontiguousarray(row[j].reshape(h,w),dtype='<f4') for j in (0,1))
            path=folder/f'q{q:02}.npz'
            np.savez_compressed(path,masque=m,axe=a)
            files.append(dict(q=q,fichier=str(path.relative_to(out)),octets=path.stat().st_size,sha256=fingerprint(path),
                              masque_f32=sha_array(m,'<f4'),axe_f32=sha_array(a,'<f4')))
        registry[name]=files
    for key,record in config['poids'].items():
        items.append(item(worktrees/record['fichier'],'Checkpoint final '+key))
    for k,record in config['photos'].items():
        items.append(item(worktrees/record['fichier'],'Photo publique train '+k))
    items.append(item(worktrees/config['reemploi']['fichier'],'Capture R23 réemployée, copie source'))
    save(out/'execution.json',ledger)
    save(out/'cartes/inventaire.json',registry)
    save(out/'inventaire-local.json',dict(fichiers=items,corpus_anteriorite='../r18/inventaire-local.json',
                                          sources='../r18/sources.json'))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture',type=Path)
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('out',type=Path,nargs='?',default=ROOT)
    a=parser.parse_args()
    export(a.capture,a.out,a.worktrees.resolve())
