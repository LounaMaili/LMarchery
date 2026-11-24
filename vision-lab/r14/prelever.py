"""Présenter cinq candidats avant admission par acquisition documentaire, sans modèle."""
import argparse
from hashlib import sha256
from pathlib import Path
import cv2 as cv
import numpy as np
from PIL import Image
from r7.prepare import resized
from r12.geometrie import repere
from r13.segments import length
from r14.lire import before_scoring,panel,read,save


def records():
    train=[(12,r) for r in read('r12/nouvelles.json')['photos'] if r['split']=='train']
    pilot=[(13,r) for r in read('r13/collecte.json')['lectures']]
    crigges=[(8,dict(id='13-01',group='Crigges',source={'sha256':next(
        r['pixels_travail_sha256'] for r in read('r9/sources.json')['photos'] if r['id']=='13-01')}))]
    return train+pilot+[(14,r) for r in read('r14/collecte.json')['lectures']]+crigges


def sample(rows,root,out):
    out.mkdir(parents=True,exist_ok=True)
    data=[]
    for step,r in rows:
        k=r['id']
        path=root/f'vision-lab-r{step}/vision-lab/data/r{step}/media'/(k+'.jpg')
        if step==8:
            path=root/'vision-lab-r7/vision-lab/data/r7/pilot/work'/(k+'.png')
        if sha256(path.read_bytes()).hexdigest()!=r['source']['sha256']:
            raise ValueError(k)
        im=resized(cv.imread(str(path)))
        scale,board,_,_=repere(im,np.zeros((1,1),np.uint8))
        ordered=sorted(before_scoring(board),key=lambda v:-length(v[0]))
        ranks=sorted({(len(ordered)-1)*j//4 for j in range(5)}) if ordered else []
        tiles=[]
        for rank in ranks:
            axis,pair=ordered[rank]
            tiles.append(panel(board,axis,f'{k}  #{rank}'))
            data.append(dict(photo=k,rang=rank,provenance=r['group'],partition='train' if step in (8,12) else 'dev_indep',
                             paire=pair,axe_px_travail=(axis/scale).tolist()))
        sheet=Image.new('RGB',(610,145*max(1,len(tiles))),'white')
        for j,t in enumerate(tiles):
            sheet.paste(t,(0,145*j))
        sheet.save(out/(k+'.jpg'),quality=93)
    save(out/'candidats.json',data)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    sample(records(),a.worktrees,a.out)
