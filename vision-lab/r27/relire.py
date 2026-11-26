"""Contrôles hors réseau des collages archivés et des checkpoints locaux, sans forward."""
import math
from pathlib import Path

import numpy as np
import torch
from detectron2.layers.mask_ops import paste_masks_in_image
from detectron2.structures import Boxes

from r27.essai import configure, read, write
from r27.modele import file_hash, group, tensor_hash


def verify():
    configure()
    root=Path('r27')
    snapshots=read(root/'sorties/index.json')
    manifest=read(root/'inventaire-local.json')
    train=read('r26/train.json')['photos']
    tensors=read(root/'tenseurs.json')
    checks, results, checked = {}, {}, {}
    for seed,steps in snapshots['lectures'].items():
        results[seed]={}
        for step,photos in steps.items():
            results[seed][step]={}
            for key,rec in photos.items():
                checksum=rec['sha256']
                if checksum not in checked:
                    path=root/rec['fichier']
                    assert file_hash(path)==checksum
                    with np.load(path,allow_pickle=False) as a:
                        b=torch.from_numpy(a['boites'])
                        prob=torch.from_numpy(a['probabilites'])
                        size=tuple(train[key]['dimensions'][::-1])
                        m=paste_masks_in_image(prob,Boxes(b),size,threshold=.5).numpy()
                        bits=a['masques']
                        for q,v in enumerate(m):
                            assert np.array_equal(v,((bits>>q)&1)!=0)
                        assert bits.shape==size and np.all(bits < (1<<len(m)))
                        checked[checksum]=len(m)
                results[seed][step][key]=checked[checksum]
        base=None
        for step in map(int,steps):
            path=Path('artifacts/r27/essai')/seed/f'pas-{step:03d}.pt'
            original=next(v for v in manifest['capsules'] if v['fichier']==str(path))
            assert file_hash(path)==original['sha256']
            ck=torch.load(path,map_location='cpu')
            assert ck['pas']==step and ck['graine']==int(seed)
            state=ck['modele']
            missing=set(tensors)-set(state)
            expected={'pixel_mean','pixel_std'}|{'proposal_generator.anchor_generator.cell_anchors.'+str(i) for i in range(5)}
            assert missing==expected and not set(state)-set(tensors)
            changed=[]
            for n,v in state.items():
                if not tensors[n]['entraine'] or step==0:
                    assert tensor_hash(v)==tensors[n]['sha256'],n
                elif tensor_hash(v)!=tensors[n]['sha256']:
                    changed.append(n)
            if step==0:
                base={n:v.clone() for n,v in state.items() if tensors[n]['entraine']}
            distances={g:math.sqrt(sum(float((state[n]-v).double().norm())**2 for n,v in base.items() if group(n)==g))
                       for g in ('rpn','boite','masque')}
            opt=ck['optimiseur']
            assert len(opt['state'])==(len(base) if step else 0)
            assert ck['rng_torch'].dtype==torch.uint8 and ck['rng_numpy'][0]=='MT19937' and len(ck['rng_python'])==3
            for grp in opt['param_groups']:
                assert (grp['lr'],grp['momentum'],grp['nesterov'])==(.001,.9,False)
            checks[f'{seed}-{step}']=dict(sha256=original['sha256'],tenseurs_persistants=len(state),
                       tenseurs_modifies=len(changed),parametres_avec_momentum=len(opt['state']),
                       normes_deplacement_depuis_zero=distances,etats_RNG_verifies=['torch','numpy','python'])
    write(root/'mesures/relecture-collage.json',dict(capsules_distinctes=len(checked),masques_par_lecture=results,
          masques_distincts_recolles=sum(checked.values()),identiques_bit_a_bit=True,
          checkpoints=checks,passages_modele=0))


if __name__=='__main__':
    verify()
