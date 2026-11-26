"""Mesurer les cartes et la géométrie d'une requête sans créer de nouvelles sorties."""
import cv2 as cv
import numpy as np

from r19.donnees import work_to
from r19.mesures import samples
from r25.geometrie import distances, probe_rows, rounded

STAGES=('axe','intersection','taille','elongation','portee','pca','interieur','segment','oriente')
SUPPORTS=('axe','masque','intersection','taille','elongation','portee')


def grid_distance(on,points,grid,view):
    # Pour chaque ligne, seuls les deux voisins en abscisse peuvent minimiser la distance.
    xy=work_to(points,view,grid)
    scale=view/grid
    best=np.full(len(xy),np.inf)
    for y in np.flatnonzero(on.any(axis=1)):
        xs=np.flatnonzero(on[y])
        j=np.searchsorted(xs,xy[:,0])
        left=xs[(j-1).clip(0,len(xs)-1)]
        right=xs[j.clip(0,len(xs)-1)]
        dx=np.minimum(abs(left-xy[:,0]),abs(right-xy[:,0]))*scale[0]
        best=np.minimum(best,dx*dx+((y-xy[:,1])*scale[1])**2)
    return np.sqrt(best)


def ref_probes(ref):
    result={}
    ignored=[np.asarray(r['polygone'],np.float32) for r in ref['annotation']['regions_ignorees'] if 'axe' in r['taches']]
    for name,paths in ref['axes'].items():
        pts,tan=samples(paths)
        native=work_to(pts,ref['view'],ref['size'])
        pixels=np.floor(native+.5).astype(int).clip(0,ref['size']-1)
        result[name]=dict(xy704=pts.tolist(),tangentes=tan.tolist(),
            zones_ignorees_axe=[any(cv.pointPolygonTest(poly,tuple(v),False)>=0 for poly in ignored) for v in native],
            masque_R18_ignore=[bool(ref['mask'][y,x]==255) for x,y in pixels])
    return result


def read_map(z,xy):
    h,w=z.shape
    coord=xy.clip([0,0],[w-1,h-1])
    lo=np.floor(coord).astype(int)
    hi=(lo+1).clip(0,[w-1,h-1])
    wxy=coord-lo
    x,y=lo.T
    xx,yy=hi.T
    a,b=wxy.T
    value=(1-a)*(1-b)*z[y,x]+a*(1-b)*z[y,xx]+(1-a)*b*z[yy,x]+a*b*z[yy,xx]
    ix,iy=np.floor(coord+.5).astype(int).T
    return z[iy,ix],value


def observe(ref,axes,mask,axis,selected,lines,expected,exact_segments=True):
    points=np.concatenate([v['xy704'] for v in axes.values()])
    tangents=np.concatenate([v['tangentes'] for v in axes.values()])
    grid,view=ref['grid'],ref['view']
    xy=work_to(points,view,grid)
    bins=dict(masque=mask>=0,axe=axis>=0,intersection=(mask>=0)&(axis>=0),**selected)
    near={stage:grid_distance(on,points,grid,view) for stage,on in bins.items()}
    for stage in ('pca','interieur','final'):
        seg=work_to(lines[stage],grid,view) if lines[stage] else np.empty((0,2,2))
        if stage=='final':
            source=np.asarray(expected).reshape(-1,2,2)
            if source.shape!=seg.shape:
                raise ValueError('Nombre de segments instrumentés divergent')
            if exact_segments and not np.array_equal(source,seg):
                raise ValueError('Extrémités instrumentées divergentes')
            if not exact_segments and not np.allclose(source,seg,atol=1.e-9,rtol=0):
                raise ValueError('Écart des extrémités au rejeu sur CPU')
            final=probe_rows(points,tangents,expected)
            continue
        if len(seg):
            near[stage]=distances(points,seg)[0].min(axis=1)
        else:
            near[stage]=np.full(len(points),np.inf)
    m,mb=read_map(mask,xy)
    z,zb=read_map(axis,xy)
    rows={}
    for i,n in enumerate(axes):
        indices=range(21*i,21*(i+1))
        stages={s:[bool(near[s][j]<=6.) for j in indices] for s in (*SUPPORTS,'pca','interieur')}
        stages['segment']=[final[j][-2] for j in indices]
        stages['oriente']=[final[j][-1] for j in indices]
        detail=[]
        for j in indices:
            # Le logit de la cellule la plus proche est la valeur float32 exacte.
            detail.append([float(z[j]),rounded(zb[j]),float(m[j]),rounded(mb[j]),
                           *[rounded(near[s][j]) for s in (*SUPPORTS,'pca','interieur')]])
        rows[n]=dict(comptes={s:sum(v) for s,v in stages.items()},sondes=detail,
                     bits_proches={s:sum(int(ok)<<j for j,ok in enumerate(v)) for s,v in stages.items()})
    return rows,final


SAMPLE_COLUMNS=['logit_axe_cellule','logit_axe_bilineaire','logit_masque_cellule','logit_masque_bilineaire',
                *['distance_'+s for s in (*SUPPORTS,'pca','interieur')]]
