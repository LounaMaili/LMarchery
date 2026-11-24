"""Identifier le chemin LSD de chaque segment archivé et préparer la lecture sur pixels."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys
from time import perf_counter
import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw
from r7.prepare import resized
from r12.geometrie import repere
from r13.segments import reconstruct

FIRST = 'ordinary-9301'


def read(path):
    return json.loads(Path(path).read_bytes())


def save(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False)+'\n')


def population():
    return read('r12/historique.json')+[r for r in read('r12/nouvelles.json')['photos'] if r['split']=='nouveau']


def boards(worktrees):
    historic = {r['id']:r for r in read(worktrees/'vision-lab-r9/vision-lab/data/r9/dev.json')}
    for row in population():
        ident = row['id']
        if ident in historic:
            r = historic[ident]
            n = 8 if r['origin']=='r8-development' else 9
            path=worktrees/f'vision-lab-r{n}/vision-lab/data/r{n}'/r['image']
            digest=r['sha256']
            image=cv.imread(str(path))
        else:
            path=worktrees/'vision-lab-r12/vision-lab/data/r12/media'/(ident+'.jpg')
            digest=row['source']['sha256']
            image=resized(cv.imread(str(path)))
        if sha256(path.read_bytes()).hexdigest()!=digest or list(image.shape[1::-1])!=row['size']:
            raise ValueError(ident)
        _,board,_,_=repere(image,np.zeros((1,1),np.uint8))
        yield ident,board


def before_scoring(board):
    # The list before scoring and suppression depends only on the image.
    candidates=[]
    def observe(frame,event,arg):
        if frame.f_code is reconstruct.__code__ and event=='return':
            candidates.extend(frame.f_locals.get('candidates',[]))
    previous=sys.getprofile()
    try:
        sys.setprofile(observe)
        reconstruct(board,np.zeros(board.shape[:2],np.uint8))
    finally:
        sys.setprofile(previous)
    return candidates


def lineage(board):
    families={}
    for axis,pair in before_scoring(board):
        key=axis.tobytes()
        family='paire' if pair else 'isole'
        if key in families and families[key]!=family:
            raise ValueError('Famille ambiguë')
        families[key]=family
    return families


def selection(labels):
    selected=[]
    for family in ('paire','isole'):
        indices=[i for i,val in enumerate(labels) if val==family]
        if indices:
            selected.extend(indices[j] for j in sorted({0,(len(indices)-1)//2,len(indices)-1}))
    return selected


def panel(board,axis,caption):
    im=Image.fromarray(cv.cvtColor(board,cv.COLOR_BGR2RGB))
    xy=np.asarray(axis)
    lo=np.maximum(0,np.floor(xy.min(0)-25)).astype(int)
    hi=np.minimum(im.size,np.ceil(xy.max(0)+26)).astype(int)
    crop=im.crop((*lo,*hi))
    ratio=min(300/crop.width,122/crop.height,2.5)
    crop=crop.resize(tuple(max(1,round(x*ratio)) for x in crop.size))
    tile=Image.new('RGB',(610,145),'#ffffff')
    tile.paste(crop,(0,20))
    tile.paste(crop,(305,20))
    draw=ImageDraw.Draw(tile)
    points=(xy-lo)*ratio+[305,20]
    draw.line([tuple(x) for x in points],fill='#f20db8',width=1)
    for x,y in points:
        draw.ellipse((x-3,y-3,x+3,y+3),outline='#00ffff',width=1)
    draw.text((2,2),caption,fill='black')
    return tile


def collect(root,out,plates):
    cv.setNumThreads(1)
    predictions={p.stem:read(p) for p in sorted(Path('r13/sorties').glob('*.json'))}
    if len(predictions)!=6:
        raise ValueError('Six sorties attendues')
    out.mkdir(parents=True,exist_ok=True)
    plates.mkdir(parents=True,exist_ok=True)
    result={name:{} for name in predictions}
    slate=[]
    times={}
    def flush(index):
        sheet=Image.new('RGB',(1220,145*((len(slate)+1)//2)),'white')
        for j,tile in enumerate(slate):
            sheet.paste(tile,((j%2)*610,(j//2)*145))
        sheet.save(plates/f'{index:02}.jpg',quality=92)
        slate.clear()
    for n,(ident,board) in enumerate(boards(root)):
        start=perf_counter()
        families=lineage(board)
        times[ident]=perf_counter()-start
        for name,raw in predictions.items():
            result[name][ident]=[families[np.asarray(a,dtype=np.float64).tobytes()] for a in raw[ident]['axes_B']]
        labels=result[FIRST][ident]
        for i in selection(labels):
            slate.append(panel(board,predictions[FIRST][ident]['axes_B'][i],f'{ident}   #{i}   {labels[i]}'))
        if (n+1)%3==0 or n==16:
            flush(n//3)
    save(out/'familles.json',result)
    save(out/'temps-lecture.json',times)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('worktrees','out','planches'):
        p.add_argument(arg,type=Path)
    a=p.parse_args()
    collect(a.worktrees,a.out,a.planches)
