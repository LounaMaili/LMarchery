"""Audit du masque natif et mesures géométriques dans le repère de référence R12."""
from collections import Counter
from itertools import combinations, groupby

import cv2 as cv
import numpy as np

from r12.geometrie import axis_diagnostics, direction, samples
from r12.tubes import crosses


def components(shaft):
    n,labels,stats,_=cv.connectedComponentsWithStats((shaft>=.5).astype(np.uint8),8)
    reasons={}
    axes=[]
    for rank,i in enumerate(np.argsort(-stats[1:,cv.CC_STAT_AREA])+1):
        if rank>=48:
            reasons[int(i)]='rang'
            continue
        x,y,w,h,area=stats[i]
        if area<28:
            reasons[int(i)]='aire'
            continue
        yy,xx=np.where(labels[y:y+h,x:x+w]==i)
        pts=np.column_stack((xx+x,yy+y))
        center=pts.mean(0)
        var,v=np.linalg.eigh(np.cov(pts.T))
        along=(pts-center)@v[:,-1]
        ends=center+np.quantile(along,[.01,.99])[:,None]*v[:,-1]
        long=var[1]>=4*max(1,var[0])
        enough=np.linalg.norm(ends[1]-ends[0])>=25
        reasons[int(i)]=('retenue' if long and enough else 'deux_rejets' if not(long or enough) else
                         'longueur' if long else 'allongement')
        if long and enough:
            axes.append(ends)
    return axes,labels,reasons


def audit(row,scale,shaft,labels,reasons):
    h,w=shaft.shape
    refs=[]
    for mark in row['marks']:
        hits=Counter()
        maxima=[]
        for xy in samples(np.array(mark['axis'])):
            delta=3/scale
            low=np.maximum(0,np.floor(xy-delta).astype(int))
            high=np.minimum([w,h],np.ceil(xy+delta).astype(int)+1)
            x,y=low
            xx,yy=high
            v,u=np.ogrid[y:yy,x:xx]
            disk=((u-xy[0])*scale[0])**2+((v-xy[1])*scale[1])**2<=9
            pixels=shaft[y:yy,x:xx][disk]
            maxima.append(float(pixels.max()) if pixels.size else 0.)
            hits.update(int(i) for i in np.unique(labels[y:yy,x:xx][disk]) if i)
        refs.append(dict(maxima=maxima,composantes={str(i):[n,reasons[i]] for i,n in hits.items()}))
    return dict(rejets=dict(Counter(reasons.values())),references=refs)


def shortened(row,scale,distance,near):
    axes=[]
    removed=0
    for mark in row['marks']:
        l=np.array(mark['axis'])*scale
        if distance and mark['entry'] is not None:
            side=int(np.argmin(np.linalg.norm(l-np.array(mark['entry'])*scale,axis=1)))
            if not near:
                side=1-side
            if np.linalg.norm(l[1]-l[0])<=distance:
                removed+=1
                continue
            l[side]+=distance*direction(l if side==0 else l[::-1])
        axes.append(l)
    return axes,removed


def geometry(row,axes,scale,mask,supported):
    base=axis_diagnostics(row,axes,scale,mask,supported)
    truth=[np.array(m['axis'])*scale for m in row['marks']]
    reused=Counter()
    for i,(ref,record) in enumerate(zip(truth,base['reference_axes'])):
        pts=samples(ref)
        hit=np.zeros(21,dtype=bool)
        strict=[]
        for j,l in enumerate(axes):
            u=direction(l)
            if abs(u@direction(ref))<np.cos(np.deg2rad(20)):
                continue
            near=l[0]+np.clip((pts-l[0])@u,0,np.linalg.norm(l[1]-l[0]))[:,None]*u
            good=np.linalg.norm(pts-near,axis=1)<=6
            hit |= good
            if good.sum()>=11:
                strict.append(j)
                reused[j]+=1
        record.update(union_hits=int(hit.sum()),longest_union=max((len(list(g)) for k,g in groupby(hit) if k),default=0),
                      strict_ids=strict,crossing=any(crosses(ref,l) for j,l in enumerate(truth) if i!=j),
                      parallel=any(i!=j and abs(direction(ref)@direction(l))>=np.cos(np.deg2rad(15)) and
                      np.linalg.norm(pts[:,None]-samples(l),axis=2).min()<=12 for j,l in enumerate(truth)))
        assert bool(strict)==record['axis_recovered']
    base['segments_multi_reference']=sum(n>1 for n in reused.values())
    return base
