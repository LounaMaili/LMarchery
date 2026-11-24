"""Contrôle secondaire sur le seul checkpoint avec masques archivés."""
from collections import Counter
from pathlib import Path
import argparse
import cv2 as cv
import numpy as np
from r12.geometrie import continuity
from r13.segments import length,profile,sample
from r14.lire import FIRST,boards,read,save,population


def measure(root,out):
    families=read('r14/mesures/familles.json')[FIRST]
    raw=read('r13/sorties/'+FIRST+'.json')
    rows={r['id']:r for r in population()}
    labels={(r['photo'],r['segment']):r['classe'] for r in read('r14/lectures.json')}
    photos={}
    counts=Counter()
    yy,xx=np.ogrid[-3:4,-3:4]
    for k,b in boards(root):
        mask=cv.imread('r13/sorties/masques/'+k+'.png',cv.IMREAD_GRAYSCALE)
        if mask.shape!=b.shape[:2]:
            raise ValueError(k)
        supported=cv.dilate((mask>0).astype(np.uint8),((xx*xx+yy*yy)<=9).astype(np.uint8))
        gray=cv.cvtColor(b,cv.COLOR_BGR2GRAY)
        photos[k]=[]
        for i,(coords,family) in enumerate(zip(raw[k]['axes_B'],families[k],strict=True)):
            axis=np.array(coords)
            support=continuity(axis,supported)
            contrast=float(min(abs(profile(gray,axis,6)-np.median(sample(gray,np.linspace(*axis,13))))))
            fails=bool(length(axis)<40 or (support<.35 and contrast<18))
            if family=='isole' and fails:
                raise ValueError('Incohérence avec le générateur')
            group='vide' if 'empty' in rows[k]['flags'] else 'occupee'
            label=labels.get((k,i),'non_lu')
            photos[k].append(dict(segment=i,famille=family,longueur=length(axis),soutien=support,
                                  contraste=contrast,hors_condition_isole=fails,lecture=label))
            counts[(family,group,label,fails)]+=1
    save(out,dict(par_photo=photos,effectifs=[dict(famille=f,photo=g,lecture=l,hors_condition_isole=h,n=n)
                                              for (f,g,l,h),n in sorted(counts.items())]))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    measure(a.worktrees,a.out)
