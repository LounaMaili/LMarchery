"""Verrou de parité sur les 22 cas avant le dépouillement des cartes."""
import argparse
from hashlib import sha256
from pathlib import Path

import cv2 as cv
import numpy as np
import torch

from r19.decoder import decode
from r19.donnees import fingerprint, read, save
from r19.pilote import setup
from r20.corpus import references
from r23.parite import exact
from r25.commun import REUSE, ROOT, captures, historical, tag


def sha_array(a,dtype):
    return sha256(np.ascontiguousarray(a,dtype=dtype).tobytes()).hexdigest()


@torch.inference_mode()
def verify(src,out):
    setup(9301)
    refs=references()
    ledger=read(src/'execution.json')
    if [(r['bras'],r['graine'],r['photo']) for r in ledger['cas']]!=list(captures()):
        raise ValueError('Cas incomplets')
    records={}
    for case,r in zip(captures(),ledger['cas'],strict=True):
        arm,seed,k=case
        ref=refs[k]
        path=src/r['nom']
        folder=historical(arm,seed)
        weight=read(folder/'controle.json')['poids_sha256']
        if fingerprint(path)!=r['sha256'] or weight!=r['poids'] or r['appel_modele']!=(case!=REUSE):
            raise ValueError('Empreinte ou registre divergent')
        with np.load(path,allow_pickle=False) as raw:
            fields,logits=raw['champs'],raw['presence']
        observed,bits=decode(torch.from_numpy(fields),torch.from_numpy(logits),2*ref['grid'],ref['size'])
        plan=sha_array(bits,'<u2')
        observed['sha256_plan_uint16_le']=plan
        old=read(folder/'predictions.json')[k]
        png=folder/'masques'/(k+'.png')
        expected=cv.imread(str(png),cv.IMREAD_UNCHANGED)
        if expected.dtype!=bits.dtype or not np.array_equal(bits,expected):
            raise ValueError('Masques binaires différents')
        h=exact('Coordonnées, scores, présences, segments et géométrie',observed,old)
        records[tag(*case)]=dict(poids=weight,capture=r['sha256'],sorties_json=h,masques_uint16=plan,
            sha256_png_archive=fingerprint(png),champs_float32=sha_array(fields,'<f4'),requêtes=len(observed['sorties']),
            segments=sum(len(s['segments']) for s in observed['sorties']),parite_exacte=True)
    save(out,dict(parite_complete=True,cas=records,appels_modele_pour_parite=0))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture',type=Path)
    p.add_argument('out',type=Path,nargs='?',default=ROOT/'parite.json')
    a=p.parse_args()
    verify(a.capture,a.out)
