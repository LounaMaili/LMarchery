"""Générer des planches de lecture publiables depuis les seules géométries du gel."""
import argparse
import json
from functools import lru_cache
from math import ceil, floor
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from r7.prepare import resized
from r14.mesurer import read, save
from r15.corpus import corpus, fingerprint
from r15.restaurer import historique
from r16.donnees import photo_paths

W,H = 520,308
GAP,TOP,CAP = 10,57,27
BG = '#f7f7f5'
INK = '#1b2831'
COLORS = {'axes':['#00fff2','#78b8ff'],'segment':'#ff32bb','entree':'#ff9a20','ignore':'#fbe342'}
FONTS = Path('/usr/share/fonts/dejavu-sans-fonts')


@lru_cache
def font(size):
    name='DejaVuSans.ttf'
    folders=(FONTS,Path('/usr/share/fonts/truetype/dejavu'))
    path=next((d/name for d in folders if (d/name).exists()),Path(name))
    return ImageFont.truetype(str(path),size)


def bounds(points,size):
    xy=np.asarray(points,dtype=float)
    lo,hi=xy.min(axis=0),xy.max(axis=0)
    span=hi-lo
    pad=max(20.,.08*max(span))
    span=np.maximum(span+2*pad,[120,82])
    center=(lo+hi)/2
    # Conserver du voisinage perpendiculaire même pour un tube très allongé.
    span=np.maximum(span,[span[1]*1.05,span[0]*.43])
    return [max(0,floor(center[0]-span[0]/2)),max(0,floor(center[1]-span[1]/2)),
            min(size[0],ceil(center[0]+span[0]/2)),min(size[1],ceil(center[1]+span[1]/2))]


def framed(image,box):
    x,y,x1,y1=box
    w,h=x1-x,y1-y
    scale=min(W/w,H/h)
    nw,nh=round(w*scale),round(h*scale)
    dx,dy=(W-nw)//2,(H-nh)//2
    panel=Image.new('RGB',(W,H),'#e7e9e8')
    panel.paste(image.crop(tuple(box)).resize((nw,nh),Image.Resampling.LANCZOS),(dx,dy))
    return panel,[nw/w,nh/h,dx-x*nw/w,dy-y*nh/h]


def projected(pts,m):
    a,b,tx,ty=m
    return [(round(a*x+tx),round(b*y+ty)) for x,y in pts]


def line(draw,pts,color,dashed=False):
    for p,q in zip(pts,pts[1:]):
        a,b=np.array(p),np.array(q)
        length=np.linalg.norm(b-a)
        if length==0:
            continue
        for t in (np.arange(0,length,12) if dashed else [0]):
            u=min(t+7,length) if dashed else length
            v=[tuple(np.rint(a+(b-a)*s/length).astype(int)) for s in (t,u)]
            draw.line(v,fill='#20232b',width=4)
            draw.line(v,fill=color,width=2)


def layers(panel,m,c,seg,show):
    view=panel.copy()
    draw=ImageDraw.Draw(view)
    if 'ignore' in show:
        for lo,hi in c['regions_ignorees']:
            corners=projected([lo,hi],m)
            hatch=Image.new('RGBA',view.size)
            hd=ImageDraw.Draw(hatch)
            x0,y0=corners[0]; x1,y1=corners[1]
            left,top=max(x0,0),max(y0,0); right,bottom=min(x1,W-1),min(y1,H-1)
            if left<=right and top<=bottom:
                hd.rectangle((left,top,right,bottom),fill=(255,219,35,24))
                for start in range(left-(bottom-top),right+1,20):
                    xx=max(left,start); end=min(right,start+bottom-top)
                    hd.line((xx,top+xx-start,end,top+end-start),fill=(255,231,70,108),width=1)
                view.paste(hatch,mask=hatch.getchannel('A'))
            a,b=lo
            x,y=hi
            line(draw,projected([(a,b),(x,b),(x,y),(a,y),(a,b)],m),COLORS['ignore'],True)
    for i,ref in enumerate(c['references']):
        if 'axes' in show:
            pts=projected(ref['axe'],m)
            line(draw,pts,COLORS['axes'][i])
            for x,y in pts:
                draw.rectangle((x-2,y-2,x+2,y+2),outline=COLORS['axes'][i],width=1)
    if 'segment' in show:
        pts=projected(seg,m)
        line(draw,pts,COLORS['segment'],True)
        for x,y in pts:
            draw.ellipse((x-2,y-2,x+2,y+2),fill=COLORS['segment'],outline='#20232b')
    if 'entree' in show:
        for ref in c['references']:
            if ref['entree'] is not None:
                (x,y),=projected([ref['entree']],m)
                draw.ellipse((x-5,y-5,x+5,y+5),outline='#222222',width=4)
                draw.ellipse((x-5,y-5,x+5,y+5),outline=COLORS['entree'],width=2)
    return view


def source(p):
    s=p['source']
    terms=s.get('terms') or s['licence_image']
    return dict(credit=s.get('credit') or p['provenance'],titre=s.get('title') or s.get('source_file') or p['id'],
                url=s.get('url') or s['source'],licence=terms,
                nom_licence=('CC0 1.0' if 'publicdomain/zero' in terms else
                    'CC '+('BY-SA' if 'by-sa/' in terms else 'BY')+' '+('2.0' if '/2.0' in terms else '4.0')))


def render(out,worktrees=None):
    out.mkdir(parents=True,exist_ok=True)
    records=read('r17/cas-geles.json')['cas']
    population={p['id']:p for p in corpus()}
    paths=photo_paths()
    images={}
    metadata=[]
    for c in records:
        k=c['photo']
        p=population[k]
        if k not in images:
            path=historique(paths[k],worktrees)
            if fingerprint(path)!=c['sha256_pixels']:
                raise ValueError(k)
            im=resized(cv.imread(str(path)))
            images[k]=Image.fromarray(cv.cvtColor(im,cv.COLOR_BGR2RGB))
        im=images[k]
        if list(im.size)!=c['taille_travail']:
            raise ValueError(k)
        seg=(np.array(c['segment_704'])/c['echelle_704']).tolist()
        pts=list(seg)
        for ref in c['references']:
            pts.extend(ref['axe'])
            if ref['entree'] is not None:
                pts.append(ref['entree'])
        box=bounds(pts,im.size)
        context,mc=framed(im,[0,0,*im.size])
        crop,mz=framed(im,box)
        refs=' + '.join(r['instance'] for r in c['references'])
        src=source(p)
        titles=['Contexte photo','Détail photo','Axe(s) de référence','Segment émis',
                'Entrée(s) et régions ignorées','Superposition']
        views=[context,crop,layers(crop,mz,c,seg,['axes']),layers(crop,mz,c,seg,['segment']),
               layers(crop,mz,c,seg,['entree','ignore']),layers(crop,mz,c,seg,['axes','segment','entree','ignore'])]
        sheet=Image.new('RGB',(3*W+4*GAP, TOP+2*(CAP+H+GAP)+32),BG)
        d=ImageDraw.Draw(sheet)
        d.text((GAP,5),f"{c['cas']}   {p['provenance']}   ·   {refs}",fill=INK,font=font(20))
        d.text((GAP,31),'Axe : cyan (seconde référence en bleu)     Segment : magenta pointillé     Entrée visible : cercle orange     Zone ignorée : cadre jaune',fill=INK,font=font(15))
        origins=[]
        for i,(label,view) in enumerate(zip(titles,views,strict=True)):
            x=GAP+(i%3)*(W+GAP); y=TOP+(i//3)*(CAP+H+GAP)
            d.text((x,y+1),label,fill=INK,font=font(17))
            origins.append([x,y+CAP,W,H])
            sheet.paste(view,(x,y+CAP))
        name=c['cas']+'.jpg'
        d.text((GAP,sheet.height-27),f"Photographie : {src['credit']} · {src['nom_licence']}   |   Réduction, détail et calques LMArchery R17 · même licence",fill=INK,font=font(14))
        sheet.save(out/name,quality=87,subsampling=0,optimize=True)
        metadata.append(dict(cas=c['cas'],photo=k,fichier=name,sha256=fingerprint(out/name),cadre_detail=box,
            contexte=dict(source=origins[0],affine=mc),detail=dict(source=origins[1],affine=mz),
            segment=seg,references=c['references'],regions_ignorees=c['regions_ignorees'],droits=src))
    save(out/'index.json',metadata)
    # Un script local évite fetch(file://) pour la consultation hors ligne.
    (out/'calques.js').write_text('const planchesR17 = '+json.dumps(metadata,ensure_ascii=False,separators=(',',':'))+';\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    p.add_argument('--worktrees',type=Path)
    render(**vars(p.parse_args()))
