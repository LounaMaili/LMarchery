"""Rejouer les champs float32 B, sans construire ou appeler de réseau."""
import argparse
from hashlib import sha256
from pathlib import Path

import cv2 as cv
import numpy as np
import torch

from r19.decoder import decode
from r19.donnees import fingerprint, input_size, read
from r19.supervision import losses, prepare
from r20.champs import field_summary, load_fields
from r20.corpus import ROOT, references
from r20.instrumenter import diagnose


def compare(left,right,atol=1.e-8,rtol=1.e-8):
    if isinstance(left,dict):
        if left.keys()!=right.keys():raise ValueError('Champs différents')
        for key in left:compare(left[key],right[key],atol,rtol)
    elif isinstance(left,list):
        if len(left)!=len(right):raise ValueError('Longueurs différentes')
        for x,y in zip(left,right,strict=True):compare(x,y,atol,rtol)
    elif isinstance(left,float):
        if not np.isclose(left,right,atol=atol,rtol=rtol):raise ValueError((left,right))
    elif left!=right:
        raise ValueError((left,right))


def verify(archives):
    cv.setNumThreads(1);torch.set_num_threads(4)
    seal=read(ROOT/'configuration.json')
    key=seal['photo_B'];r=references()[key]
    t=prepare(r['spec'],r['record'],r['annotation'],r['mask'])
    for step in read(archives/'B/execution.json')['controles']:
        folder=archives/'B'/f'{step:04}';control=read(folder/'controle.json')
        planes=load_fields(folder/'champs')
        for i,q in enumerate(control['champs']):
            f=folder/'champs'/q['fichier']
            if (q['q']!=i or fingerprint(f)!=q['sha256'] or
                sha256(planes[i].astype('<f4').tobytes()).hexdigest()!=q['sha256_plans_float32_le']):
                raise ValueError('Champ public modifié')
        compare(field_summary(planes),read(folder/'similarites.json'))
        old=read(folder/'pertes.json')[key]
        f=torch.from_numpy(planes.reshape(12,3,-1))
        pres=torch.tensor(old['logits_presence'],dtype=torch.float32)
        _,parts,audit=losses(f,pres,t)
        compare(diagnose(f,pres,t,parts,audit),old,atol=2.e-6,rtol=2.e-6)
        new,bits=decode(f,pres,input_size(r['size']),r['size'])
        expected=read(folder/'predictions.json')[key]
        new['sha256_plan_uint16_le']=sha256(bits.astype('<u2').tobytes()).hexdigest()
        compare(new,expected,atol=2.e-6,rtol=2.e-6)
        if not np.array_equal(bits,cv.imread(str(folder/'masques'/(key+'.png')),cv.IMREAD_UNCHANGED)):
            raise ValueError('Plan binaire différent')
        print(step,'36 champs, scores, coûts, affectations, fragments et masques reproduits')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('archives',nargs='?',type=Path,default=ROOT/'archives')
    verify(p.parse_args().archives)
