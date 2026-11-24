"""Restituer au plus une fois les deux cartes des 66 couples de développement R15."""
import argparse
from hashlib import sha256
from pathlib import Path
from time import perf_counter

import cv2 as cv
import numpy as np
import torch

from r7.model import ContactNet, full_maps
from r7.prepare import resized
from r15.restaurer import historique
from r16.donnees import INDEX, RUNS, corpus, photo_paths, fingerprint, read, save


def restore(cache, worktrees=None):
    cache.mkdir(parents=True,exist_ok=True)
    index=read(INDEX/'index.json')['poids']
    weights={Path(r['nom']).stem:r for r in read('r15/configuration.json')['poids']}
    photos=[p for p in corpus() if p['partition']=='dev_indep']
    paths = {k:historique(path, worktrees) for k,path in photo_paths().items()}
    if len(photos)!=11 or len(RUNS)!=6:
        raise ValueError('Budget R16')
    index_path = cache/'index.json'
    if index_path.exists():
        sealed = read(index_path)
        pairs = {(name, p['id']) for name in RUNS for p in photos}
        actual = {(a['poids'], a['photo']) for a in sealed['couples']}
        if len(sealed['couples']) != 66 or actual != pairs or sealed['passages_reserves'] != 66:
            raise ValueError('Index de cache incohérent')
        for a in sealed['couples']:
            if fingerprint(cache/a['fichier']) != a['sha256']:
                raise ValueError(f"Cache scellé différent pour {a['fichier']}")
        print('66 couples vérifiés depuis le cache scellé, zéro passage supplémentaire')
        return
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    result=[]
    for name in RUNS:
        torch.manual_seed(int(name.split('-')[1]))
        spec=weights[name]
        checkpoint = historique(spec['emplacement'], worktrees)
        if fingerprint(checkpoint)!=spec['sha256']:
            raise ValueError(name)
        model=ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(checkpoint,map_location='cpu',weights_only=True))
        model.eval()
        expected={r['photo']:r for r in index[name]['photos']}
        folder=cache/name
        folder.mkdir(exist_ok=True)
        for p in photos:
            k=p['id']
            archive=folder/(k+'.npz')
            marker=folder/(k+'.passage')
            if not archive.exists():
                if fingerprint(paths[k])!=p['sha256_pixels']:
                    raise ValueError(k)
                im=resized(cv.imread(str(paths[k])))
                if list(im.shape[1::-1])!=p['taille']:
                    raise ValueError(k)
                # Un couple interrompu après réservation ne consomme pas une seconde inférence.
                with marker.open('x') as log:
                    log.write(name+' '+k+'\n')
                start=perf_counter()
                maps=full_maps(model,im)
                with (folder/(k+'.tmp')).open('wb') as tmp:
                    np.savez_compressed(tmp,cartes=maps)
                (folder/(k+'.tmp')).rename(archive)
                with marker.open('a') as log:
                    log.write(f'{perf_counter()-start:.6f}\n')
            else:
                with np.load(archive,allow_pickle=False) as saved:
                    maps=saved['cartes']
            tube=sha256(maps[1].tobytes()).hexdigest()
            if tube!=expected[k]['carte_tube_sha256']:
                raise ValueError(f'Carte tube différente pour {name}/{k}')
            result.append(dict(poids=name,photo=k,source_photo_sha256=p['sha256_pixels'],
                sha256_tube=tube,sha256_contact=sha256(maps[0].tobytes()).hexdigest(),
                fichier=f'{name}/{archive.name}',octets=archive.stat().st_size,sha256=fingerprint(archive)))
        print(name,len(photos),'cartes tube conformes',flush=True)
    save(index_path,dict(passages_reserves=sum(1 for _ in cache.glob('*/*.passage')),couples=result))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cache',type=Path)
    parser.add_argument('--worktrees',type=Path)
    restore(**vars(parser.parse_args()))
