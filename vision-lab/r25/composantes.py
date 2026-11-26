"""Observer les décisions de fragments() sans intervenir dans le décodage historique."""
import cv2 as cv
import numpy as np


def vec(a):
    return np.asarray(a,dtype=float).tolist()


def trace(on):
    n,labels,boxes,centroids=cv.connectedComponentsWithStats(on.astype(np.uint8),connectivity=8)
    stages={k:np.zeros(n,bool) for k in ('taille','elongation','portee')}
    pca,inside_runs,final=[],[],[]
    records=[]
    for i in range(1,n):
        x,y,w,h,area=boxes[i]
        row=dict(id=i,boite_xywh=[int(x),int(y),int(w),int(h)],cellules=int(area),
                 centre=vec(centroids[i]),decision='taille',pca=None)
        records.append(row)
        if area<3 or max(w,h)<3:
            continue
        stages['taille'][i]=True
        yy,xx=np.where(labels[y:y+h,x:x+w]==i)
        coords=np.column_stack((xx+x,yy+y))
        center=coords.mean(0)
        values,directions=np.linalg.eigh(np.cov(coords.T))
        direction=directions[:,-1]
        if direction[np.argmax(np.abs(direction))]<0:
            direction=-direction
        fit=dict(valeurs_propres=vec(values),direction=vec(direction),utilisee=False)
        row.update(centre=vec(center),pca=fit,decision='elongation')
        if values[-1]<3*max(1.e-9,values[0]):
            continue
        stages['elongation'][i]=True
        t=(coords-center)@direction
        span=t.max()-t.min()
        fit.update(utilisee=True,t_min=float(t.min()),t_max=float(t.max()))
        row['decision']='portee'
        if span<3:
            continue
        stages['portee'][i]=True
        line=center+np.linspace(t.min(),t.max(),int(np.ceil(2*span))+1)[:,None]*direction
        pca.append([line[0],line[-1]])
        pixels=np.floor(line+.5).astype(int)
        inside=((pixels>=0)&(pixels<np.array(on.shape[1::-1]))).all(axis=1)
        pixels=pixels.clip(0,np.array(on.shape[1::-1])-1)
        inside &= labels[pixels[:,1],pixels[:,0]]==i
        fit.update(echantillons=len(line),portions=[])
        switches=np.r_[0,np.flatnonzero(inside[1:]!=inside[:-1])+1,len(line)]
        row['decision']='aucun_segment'
        for lo,hi in zip(switches[:-1],switches[1:],strict=True):
            code='hors_composante'
            index=None
            if inside[lo]:
                endpoints=[line[lo],line[hi-1]]
                inside_runs.append(endpoints)
                code='trop_court'
                if np.linalg.norm(line[hi-1]-line[lo])>=2:
                    index=len(final)
                    final.append(endpoints)
                    code='conserve'
                    row['decision']='segments'
            fit['portions'].append([int(lo),int(hi),code,index])
    supports={k:keep[labels] for k,keep in stages.items()}
    return records,supports,dict(pca=pca,interieur=inside_runs,final=final)
