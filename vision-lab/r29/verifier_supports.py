"""Contrôle indépendant par l'API publique du collage CPU, sans réseau de neurones."""
import argparse
from pathlib import Path

import cv2
import numpy as np
import torch
from detectron2.layers.mask_ops import paste_masks_in_image

from r27.essai import configure
from r29.commun import ROOT, read, save
from r29.supports import tile


def verify(out):
    configure()
    photos=read('r26/train.json')['photos']
    register=read(ROOT/'traces/index.json')
    checked=0
    for tag,r in register['traces'].items():
        spec=photos[r['photo']]
        labels=cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
        I=read(ROOT/'intersections'/(tag+'.json'))
        indices=[spec['instances'][n]['indice'] for n in I['identites']]
        with np.load(ROOT/'traces'/r['fichier'],allow_pickle=False) as a:
            for j in (0,499,999):
                for col,field in enumerate(('rpn','regression','borne')):
                    b=a[field][j]*a['echelle']
                    full=paste_masks_in_image(torch.ones((1,28,28)),torch.from_numpy(b[None]),labels.shape,.5)[0].numpy()
                    small,region=tile(b,labels.shape)
                    assert np.array_equal(full[region],small) and full.sum()==small.sum()
                    assert np.bincount(labels[full],minlength=256)[indices].tolist()==I['par_origine_RPN'][j][col]
                    checked+=1
    assert checked==162
    save(out,dict(boites_verifiees_pixel_par_pixel=checked,etats=18,origines_RPN=[0,499,999],
                  geometries=['rpn','regression','borne'],ecarts=0,passages_modele=0))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sortie',type=Path,nargs='?',default=ROOT/'controle-collage.json')
    verify(parser.parse_args().sortie)
