"""Coller Ω une seule boîte à la fois et compter les pixels positifs R18."""
import argparse
import inspect
from pathlib import Path

import cv2
import numpy as np
import torch
from detectron2.layers.mask_ops import _do_paste_mask

from r27.essai import configure
from r29.commun import ROOT, checked, digest, read, save

ONES = torch.ones((1,1,28,28),dtype=torch.float32)


def tile(box, hw):
    h,w = hw
    x0,y0,x1,y1 = box
    # Un rectangle dégénéré ou dont le tampon historique ne touche pas le raster est vide.
    if x1<=x0 or y1<=y0 or min(w,np.ceil(x1)+1)<=max(0,np.floor(x0)-1) or min(h,np.ceil(y1)+1)<=max(0,np.floor(y0)-1):
        return np.zeros((0,0),bool),(slice(0,0),slice(0,0))
    values,region = _do_paste_mask(ONES,torch.from_numpy(box[None]),h,w,skip_empty=True)
    return (values[0]>=.5).numpy(),region


def count(box,labels,indices):
    mask,region=tile(box,labels.shape)
    hist=np.bincount(labels[region][mask],minlength=256)
    return hist[indices]


def compute(dest):
    configure()
    train=read('r26/train.json')['photos']
    snapshots=read(ROOT/'traces/index.json')
    reference=read('r28/supports/index.json')
    assert digest(inspect.getfile(_do_paste_mask))==reference['sha256_mask_ops']
    dest.mkdir(exist_ok=True,parents=True)
    verified=0
    for tag,rec in snapshots['traces'].items():
        seed,key=rec['graine'],rec['photo']
        spec=train[key]
        assert digest(spec['masque'])==spec['sha256_masque']
        labels=cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
        names=sorted(spec['instances'],key=lambda n:spec['instances'][n]['indice'])
        indices=[spec['instances'][n]['indice'] for n in names]
        values={name:[] for name in ('rpn','regression','borne')}
        r28=reference['supports'][f'{seed}-768-{key}']
        full=cv2.imread(str(checked(Path('r28/supports')/r28['fichier'],r28)),cv2.IMREAD_UNCHANGED)
        with np.load(checked(ROOT/'traces'/rec['fichier'],rec),allow_pickle=False) as trace:
            scale=trace['echelle']
            rpn,raw,clip=[trace[k]*scale for k in ('rpn','regression','borne')]
            for j,box in enumerate(raw):
                before=count(rpn[j],labels,indices)
                after=count(box,labels,indices)
                bounded=after if np.array_equal(box,clip[j]) else count(clip[j],labels,indices)
                for k,row in [('rpn',before),('regression',after),('borne',bounded)]:
                    values[k].append(row.tolist())
            for q,j in enumerate(trace['propositions']):
                mask,region=tile(clip[j],labels.shape)
                expected=(full & (1<<q))!=0
                assert np.array_equal(mask,expected[region]) and mask.sum()==expected.sum()
                assert np.bincount(labels[expected],minlength=256)[indices].tolist()==values['borne'][j]
                verified+=1
        # Une ligne de texte par proposition et trois vecteurs dans l’ordre des identités.
        path=dest/(tag+'.json')
        import json
        with path.open('w') as f:
            f.write('{"identites":'+json.dumps(names)+',"etages":["rpn","regression","borne"],"par_origine_RPN":[\n')
            f.write(',\n'.join(json.dumps(line,separators=(',',':')) for line in zip(*values.values())))
            f.write('\n]}\n')
    assert verified==216
    save(dest/'index.json',dict(trace_gel=snapshots['gel'],supports_finaux_identiques_R28=verified,
         sha256_mask_ops=reference['sha256_mask_ops'],seuil_inclusif=.5,grille=[28,28],
         fichiers={tag:dict(nom=tag+'.json',octets=(dest/(tag+'.json')).stat().st_size,sha256=digest(dest/(tag+'.json'))) for tag in snapshots['traces']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sortie',type=Path,nargs='?',default=ROOT/'intersections')
    compute(parser.parse_args().sortie)
