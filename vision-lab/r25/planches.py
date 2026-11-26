"""Planches comparables à partir des seuls logits et tracés publics. Aucune photo."""
from base64 import b64encode
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.donnees import read, save, work_to
from r19.tracer import label, node, page, save_svg
from r25.commun import ARMS, ROOT, historical, tag
from r25.tableaux import LIMIT, NAMES, STATUS, choose, parts, stem, write

W,H=202,135
PANEL_NAMES=['Logit d’axe','Logit de masque','Après seuillage','Ligne PCA et portions','Segments et sondes']
XLINK='http://www.w3.org/1999/xlink'


def viewport(paths,view):
    points=np.concatenate(paths)
    low,high=points.min(0)-24,points.max(0)+24
    use=max(*((high-low)/[W,H]),.5)
    size=np.array([W,H])*use
    first=np.minimum(np.maximum((low+high-size)/2,-.5),np.maximum(view-.5-size,-.5))
    return first,use


def grad(raw):
    # Échelle fixe, en logits. Le blanc est zéro et les deux extrêmes saturent à ±8.
    t=np.minimum(abs(raw.astype(float))/8,1)[:,:,None]
    mid=np.array([246,244,236])
    ends=np.where((raw>=0)[:,:,None],[0,99,106],[105,67,146])
    return np.rint(mid*(1-t)+ends*t).astype(np.uint8)


def frame(m,a,components,segments,paths,probes,hits,near,first,step,view):
    grid=np.array(m.shape[1::-1])
    yy,xx=np.indices((H,W))
    scaled=first+np.stack((xx+.5,yy+.5),axis=-1)*step
    ij=np.floor((scaled+.5)*grid/view).astype(int)
    ok=((ij>=0)&(ij<grid)).all(axis=-1)
    x,y=ij.clip([0,0],grid-1).transpose(2,0,1)
    mask,axis=m[y,x],a[y,x]
    on,inside=axis>=0,(axis>=0)&(mask>=0)
    binary=np.full((H,W,3),[237,238,235],np.uint8)
    binary[on]=[222,148,57]
    binary[inside]=[35,115,125]
    pca=np.full_like(binary,[247,247,241])
    pca[inside]=[195,213,213]
    marked=np.full_like(binary,[240,242,238])
    def point(v):
        return tuple(np.rint((v-first)/step-.5).astype(int))
    def line(plane,p,q,color,width=1):
        cv.line(plane,point(p),point(q),color,width,cv.LINE_8)
    lower,upper=first-3*step,first+step*np.array([W+3,H+3])
    for c in components:
        p=c['pca']
        if not p or 'portions' not in p:
            continue
        centre=work_to(c['centre'],grid,view)
        direction=np.array(p['direction'])*view/grid
        end_points=centre+np.array([p['t_min'],p['t_max']])[:,None]*direction
        if np.any(end_points.max(0)<lower) or np.any(end_points.min(0)>upper):
            continue
        line(pca,*end_points,(150,131,167))
        ts=np.linspace(p['t_min'],p['t_max'],p['echantillons'])
        colors={'trop_court':(194,117,0),'conserve':(0,113,127),'hors_composante':(154,113,166)}
        for lo,hi,code,_ in p['portions']:
            p0,p1=centre+ts[[lo,hi-1],None]*direction
            line(pca,p0,p1,colors[code],2 if code=='conserve' else 1)
    for path in paths:
        for p,q in zip(path[:-1],path[1:]):
            line(marked,p,q,(158,150,141),1)
    for p,q in segments:
        line(marked,np.array(p),np.array(q),(37,89,160),2)
    for j,p in enumerate(probes):
        pos=point(np.array(p))
        color=(0,121,88) if (hits>>j)&1 else ((219,136,20) if (near>>j)&1 else (167,46,68))
        cv.circle(marked,pos,3,(255,255,255),-1,cv.LINE_8)
        cv.circle(marked,pos,2,color,-1,cv.LINE_8)
    planes=[grad(axis),grad(mask),binary,pca,marked]
    for plane in planes:
        plane[~ok]=[210,212,212]
    return planes


def embed(root,plane,x,y):
    ok,encoded=cv.imencode('.png',cv.cvtColor(plane,cv.COLOR_RGB2BGR),[cv.IMWRITE_PNG_COMPRESSION,6])
    if not ok:
        raise ValueError('Échec de l’encodage PNG')
    node(root,'image',x=x,y=y,width=W,height=H).set('{'+XLINK+'}href','data:image/png;base64,'+b64encode(encoded).decode())
    node(root,'rect',x=x,y=y,width=W,height=H,fill='none',stroke='#a4aaa6')


def render(root=ROOT,out=ROOT):
    from r20.corpus import references
    from xml.etree import ElementTree
    ElementTree.register_namespace('xlink',XLINK)
    refs=references()
    plots=out/'figures'
    plots.mkdir(parents=True,exist_ok=True)
    indices=[]
    index=['# Planches comparatives','','[Transitions et profils des axes conservés](profils.svg) · [Données de cette figure](donnees-profils.json)','',
           'Les dix-huit transitions et le gain de triplet chez zigazou76 sont représentés à repère et numéros '
           'de requête identiques entre bras. Les fonds sont les prédictions numériques. '
           'Les limites et les effectifs des douze requêtes se trouvent dans les [tables des transitions](../transitions.md).','',
           'Une ligne correspond à un seul bras et une seule requête. Les deux premiers panneaux partagent une échelle de −8 à +8 logits. '
           'Dans le dernier, les sondes vertes satisfont distance et orientation. Les sondes orange sont proches avec orientation incompatible. '
           'Les rouges se trouvent à plus de 6 px du segment le plus proche ou sans segment mesurable. '
           '[Repères, panneaux et valeurs numériques](donnees.json) · [Droits et crédits des six supports](../sources.md)','',
           '| Provenance | Graine | Identité | Observation | Requêtes |','|---|---:|---:|---|---|']
    for row in read(root/'bilan-identites.json'):
        seed,k,n=row['graine'],row['photo'],row['identite']
        pair=parts(root,seed,k)
        selected=choose(row,pair)
        if not selected:
            continue
        ref=refs[k]
        paths=ref['axes'][n]
        probes=read(root/'references-sondes.json')[k][n]['xy704']
        view=ref['view']
        first,step=viewport(paths,view)
        name=stem(row)
        desc=LIMIT[row['limite']] if 'limite' in row else 'coexistence du triplet'
        title=f"{row['provenance']} · axe nº {n.split(':')[1]} · graine {seed}"
        sub=(STATUS[row['statut']].capitalize()+' · '+desc if 'limite' in row else f"Axe conservé · gain du triplet sur q{row['triplets']['bloc8']}")
        h=188+2*len(selected)*185+48
        svg=page(1120,h,title,sub+'  |  Mêmes requêtes, cadrage et échelles dans les deux bras.')
        for i,caption in enumerate(PANEL_NAMES):
            label(svg,48+i*212,99,caption,14,'bold')
        frames=[]
        for q in selected:
            for arm in ARMS:
                nm=tag(arm,seed,k)
                rel=f'{nm}/q{q:02}'
                with np.load(root/'cartes'/(rel+'.npz'),allow_pickle=False) as f:
                    m,a=f['masque'],f['axe']
                comp=read(root/'composantes'/(rel+'.json'))['composantes']
                observation=read(root/'sondes'/(rel+'.json'))['identites'][n]
                segments=read(historical(arm,seed)/'predictions.json')[k]['sorties'][q]['segments']
                bits=observation['bits_proches']
                panels=frame(m,a,comp,segments,paths,probes,bits['oriente'],bits['segment'],first,step,view)
                y=140+185*len(frames)
                binding=pair[arm][n]
                rq=binding['requetes'][q]
                label(svg,48,y-13,f"{NAMES[arm]} · q{q}",15,'bold')
                label(svg,262,y-13,'Sondes sur 21',13)
                counts=observation['comptes']
                label(svg,370,y-13,'    '.join(f"{text} {counts[key]}" for key,text in
                      [('axe','axe'),('intersection','intersection'),('pca','ligne PCA'),('segment','segment'),('oriente','orientées')]))
                if rq['triplet_admissible']:
                    label(svg,1052,y-13,'triplet',13,'bold','end')
                for i,panel in enumerate(panels):
                    embed(svg,panel,48+i*212,y)
                frames.append(dict(bras=arm,q=q,trace=rel,comptes=counts,sondes_proches=bits['segment'],
                                   sondes_orientees=bits['oriente'],triplet_admissible=rq['triplet_admissible']))
        y=h-90
        for j,s in enumerate([
                'Logits du violet (≤ −8) au blanc (0), puis au bleu-vert (≥ +8). En binaire, ocre = axe seul · bleu-vert = intersection.',
                'Sur la ligne de PCA, violet = hors composante · ocre = portion trop courte · bleu-vert = portion conservée.',
                'Segments bleus · référence grise · sondes vertes = couvertes · orange = proches avec angle incompatible · rouges = éloignées ou sans segment.',
                f"Recadrage en repère 704 · {W*step:.1f} × {H*step:.1f} px. Les mesures utilisent les grilles et segments complets."]):
            label(svg,48,y+21*j,s,12)
        save_svg(svg,plots/(name+'.svg'))
        indices.append(dict(planche=name+'.svg',graine=seed,photo=k,identite=n,provenance=row['provenance'],
                            repere=view.tolist(),origine_xy704=first.tolist(),pas_px704=step,dimensions=[W,H],
                            axe_reference_xy704=[p.tolist() for p in paths],requetes=selected,panneaux=frames))
        index.append(f"| {row['provenance']} | {seed} | [{n.split(':')[1]}]({name}.svg) | {sub} | "+', '.join(f'q{q}' for q in selected)+' |')
    save(plots/'donnees.json',indices)
    write(plots/'README.md',index)


if __name__=='__main__':
    render()
