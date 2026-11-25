"""Rendre les pixels autorisés et leurs calques, à partir des lectures et cartes publiées."""
import argparse
import json
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

from r14.mesurer import read
from r15.corpus import fingerprint
from r17.planches import font
from r18.sources import decode, matrices, rights
from r18.supervision import ROOT, compact

BG=(246,247,245)
INK='#18313b'
COLORS=['#0ed7b1','#ee5f9c','#40adff','#e99e1d','#9fe349','#e899e7','#25cbdc','#4f92ff','#ffc857','#9f89ff','#a4ebb1']


def rectangles(mask):
    """Compresser les plages de pixels identiques en rectangles entiers sans approximation."""
    rects=[]
    prev={}
    for y, row in enumerate(mask):
        keep={}
        spans=np.flatnonzero(np.diff(row,prepend=False,append=False)).reshape(-1,2)
        for x,end in spans:
            span=(int(x),int(end-x))
            index=prev.get(span)
            if index is None:
                index=len(rects)
                rects.append([span[0],y,span[1],0])
            rects[index][3]+=1
            keep[span]=index
        prev=keep
    return rects


def points(coords, matrix):
    return [tuple(v) for v in (np.asarray(coords)@matrix[:,:2].T+matrix[:,2])]


def context(box):
    x,y,u,v=box
    size=(550,390)
    s=min(size[0]/(u-x),size[1]/(v-y))
    center=np.array([(u+x-1)/2,(v+y-1)/2])
    affine=np.column_stack([np.eye(2)*s,(np.array(size)-1)/2-center*s])
    return affine,size


def detail(ref):
    size=(630,166)
    history=ref['historique'] or {}
    axis=history.get('axe')
    if not axis:
        axis=max(ref['axes_observes'],key=len)
    direction=np.array(axis[-1])-axis[0]
    if direction[np.argmax(abs(direction))]<0:
        direction=-direction
    direction=direction/np.linalg.norm(direction)
    basis=np.array([direction,[-direction[1],direction[0]]])
    outline=[v for poly in ref['polygones_observes'] for v in poly]+list(axis)
    for xy in [history.get('entree'),ref['entree']['xy_lu']]:
        if xy is not None:
            outline.append(xy)
    uv=np.asarray(outline)@basis.T
    lo,hi=uv.min(axis=0),uv.max(axis=0)
    center=(hi+lo)/2
    span=hi-lo+[32,36]
    factors=np.minimum([3.0,4.0],np.array(size)/span)
    affine=np.column_stack([factors[:,None]*basis,(np.array(size)-1)/2-factors*center])
    return affine,size


def precision(box):
    x,y,u,v=box
    s=6.
    return np.array([[s,0,-s*x],[0,s,-s*y]]), (int((u-x)*s),int((v-y)*s))


def permitted_source(bgr, source_work, box):
    """Écarter les pixels extérieurs au cadrage autorisé avant toute rotation ou interpolation."""
    w,h=bgr.shape[1::-1]
    x,y,u,v=box
    sx,sy=source_work[0,0],source_work[1,1]
    left,top,right,bottom=np.rint([x/sx,y/sy,u/sx,v/sy]).astype(int)
    if [left,top,right,bottom]==[0,0,w,h]:
        return bgr
    masked=np.full_like(bgr,224)
    masked[top:bottom,left:right]=bgr[top:bottom,left:right]
    return masked


def overlay(raw, m, scope, vals, raster, source_polys, selected, correction):
    im=raw.convert('RGBA')
    def polygon(q,color):
        layer=Image.new('RGBA',im.size)
        ImageDraw.Draw(layer).polygon(points(q,m),fill=color)
        im.alpha_composite(layer)
    subjects=[(t,v) for t,v in zip(scope['instances'],vals['instances'],strict=True) if selected is None or t['instance']==selected]
    # La carte entière est une étiquette discrète. La projection aux pixels écran utilise le plus proche voisin.
    ids=cv.warpAffine(raster,m,im.size,flags=cv.INTER_NEAREST,borderValue=255)
    colors=np.zeros((*ids.shape,4),np.uint8)
    for t,_ in subjects:
        color=bytes.fromhex(COLORS[(t['indice']-1)%len(COLORS)][1:])
        colors[ids==t['indice']]=[*color,155]
    if scope['region_evaluation_temoin']:
        colors[ids==0]=[46,166,180,45]
    im.alpha_composite(Image.fromarray(colors))
    for r in scope['regions_ignorees']:
        polygon(r['polygone'],(245,181,38,43))
    d=ImageDraw.Draw(im)
    for r in scope['regions_ignorees']:
        coords=points(r['polygone'],m)
        d.line(coords+[coords[0]],fill='#d88700',width=1)
    for t,v in subjects:
        color=COLORS[(t['indice']-1)%len(COLORS)]
        for poly in t['polygones_observes']:
            coords=points(poly,m)
            d.line(coords+[coords[0]],fill=color,width=1)
        for line in v['axes_supervises']:
            d.line(points(line,m),fill='#fef6ff',width=1)
        xy=t['entree']['xy_lu']
        if xy is not None:
            (x,y),=points([xy],m)
            d.ellipse((x-4,y-4,x+4,y+4),outline='#0b2734',width=3)
            d.ellipse((x-4,y-4,x+4,y+4),outline='#ff9852' if v['entree_valide'] else '#fff046',width=2)
        h=t['historique'] or {}
        if selected is not None and h.get('masque_reference'):
            src=points(source_polys[h['masque_reference']]['points_travail'],m)
            d.line(src+[src[0]],fill='#4267ff',width=1)
        if selected is not None and not t['polygones_observes'] and h.get('axe'):
            d.line(points(h['axe'],m),fill='#9ba7ff',width=1)
    if correction:
        for key,color in [('historique','#ea624c'),('valeur_R18','#00f4e2')]:
            q=correction[key][-1] if correction['champ']=='axe' else correction[key]
            (x,y),=points([q],m)
            d.ellipse([x-5,y-5,x+5,y+5],outline=color,width=2)
    return im.convert('RGB')


def sheet(out,p,photo,vals,img,workmap,geometry,source_polys,raster,subject=None,fix=None):
    view_id, label, m, size=geometry
    raw=cv.warpAffine(img,(np.vstack([m,[0,0,1]])@workmap)[:2],size,flags=cv.INTER_LINEAR,borderValue=(225,226,224))
    raw=Image.fromarray(cv.cvtColor(raw,cv.COLOR_BGR2RGB))
    composite=overlay(raw,m,photo,vals,raster,source_polys,subject,fix)
    w,h=size
    gap=14
    top=62
    canvas=Image.new('RGB',(2*w+3*gap,top+h+41),BG)
    draw=ImageDraw.Draw(canvas)
    draw.text((gap,4),p['provenance']+'  ·  '+label,font=font(18),fill=INK)
    draw.text((gap,33),'Pixels sources',font=font(15),fill=INK)
    legend='Historique en rouge, décision R18 en cyan' if fix else 'Fût et axe admis, entrée et exclusions'
    draw.text((2*gap+w,33),legend,font=font(15),fill=INK)
    canvas.paste(raw,(gap,top))
    canvas.paste(composite,(2*gap+w,top))
    src=rights(p)
    draw.text((gap,top+h+9),f"{src['credit']} · {src['nom_licence']} · Dérivé LMArchery R18, même licence",font=font(13),fill=INK)
    filename=view_id+'.jpg'
    canvas.save(out/filename,quality=91,subsampling=0,optimize=True)
    return dict(id=view_id,photo=p['photo'],instance=subject,titre=label,fichier=filename,
        pixels=[gap,top,w,h],travail_vers_panneau=m.tolist(),sha256=fingerprint(out/filename),octets=(out/filename).stat().st_size)


def build(out,worktrees):
    folder=out/'figures'
    folder.mkdir(exist_ok=True,parents=True)
    scope=read(ROOT/'perimetre.json')['acquisitions']
    valid={p['photo']:p for p in read(out/'validites.json')['vues']}
    src_polys=read('r17/polygones.json')['polygones']
    fixes=read(ROOT/'corrections.json')['decisions']
    views=[]
    layers={}
    gallery=['# Planches de contrôle R18','',
        'Chaque planche associe pixels sources et calques dans le même repère. Les détails de fûts sont redressés et agrandis transversalement. '
        'Le trait blanc suit l’axe admis, la couleur identifie le fût, le cercle orange situe l’entrée admise, le jaune une exclusion. '
        'Sur les deux détails Crigges, le trait bleu est le polygone source. Dans les deux loupes de correction, le cercle rouge est la valeur historique et le cyan la valeur R18. '
        'La [visionneuse hors ligne](../visionneuse.html) dissocie les calques et ouvre chaque vue sur les pixels seuls. '
        'Les [matrices de projection](index.json) relient les pixels originaux orientés au panneau par le repère R18. '
        '[Effectifs par tâche](bilan.svg) · [Données de la figure](../effectifs.json)','']
    for p in scope:
        k=p['photo']
        photo=read(ROOT/'annotations'/(k+'.json'))
        vals=valid[k]
        raster=cv.imread(str(out/vals['carte']['fichier']),cv.IMREAD_GRAYSCALE)
        img,_=decode(p,worktrees)
        _,workmap=matrices(p)
        img=permitted_source(img,workmap,photo['emprise_diffusee'])
        label='Champ d’évaluation vide' if photo['region_evaluation_temoin'] else 'Contexte publié'
        specs=[((k,label,*context(photo['emprise_diffusee'])),None,None)]
        for t in photo['instances']:
            specs.append(((k+f"-i{t['indice']:02d}",t['instance'],*detail(t)),t['instance'],None))
        for fix in (f for f in fixes if f['photo']==k):
            box=(469,805,526,844) if fix['champ']=='axe' else (254,279,316,318)
            specs.append(((fix['id'],fix['id']+' · '+fix['champ'],*precision(box)),fix['instance'],fix))
        block=[]
        for spec,subject,fix in specs:
            v=sheet(folder,p,photo,vals,img,workmap,spec,src_polys,raster,subject,fix)
            views.append(v)
            block.append(v)
        src=rights(p)
        layer=dict(credit=src,regions=photo['regions_ignorees'],fond=rectangles(raster==0),sujets=[],corrections=[f for f in fixes if f['photo']==k])
        for t,v in zip(photo['instances'],vals['instances'],strict=True):
            h=t['historique'] or {}
            layer['sujets'].append(dict(id=t['instance'],i=t['indice'],lus=t['polygones_observes'],
                admis=rectangles(raster==t['indice']),axes=v['axes_supervises'],axe_lu=t['axes_observes'],ancien_axe=h.get('axe'),
                ancien_masque=src_polys[h['masque_reference']]['points_travail'] if h.get('masque_reference') else None,
                ancien_point=h.get('entree'),point=t['entree']['xy_lu'],entree_valide=v['entree_valide'],note=t['observation']))
        layers[k]=layer
        gallery.extend([f"## {k} · {src['credit']}",'',
            f"{photo['lecture_vue']} Recadrage, rotation, agrandissement et calques par LMArchery. Dérivés sous la licence de la source. "
            f"[Notice de la source]({src['notice']}) · [{src['nom_licence']}]({src['licence']}) · [Annotation](../annotations/{k}.json) · [Carte indexée](../masques/{k}.png)",'',
            ' · '.join(f"[{v['titre']}]({v['fichier']})" for v in block),''])
    compact(folder/'index.json',views)
    payload=dict(vues=views,photos=layers,couleurs=COLORS)
    (folder/'calques.js').write_text('const R18 = '+json.dumps(payload,ensure_ascii=False,separators=(',',':'),allow_nan=False)+';\n')
    (folder/'README.md').write_text('\n'.join(gallery))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--worktrees',type=Path,required=True)
    build(**vars(parser.parse_args()))
