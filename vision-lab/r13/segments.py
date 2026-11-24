"""Reconstruction sans annotations à partir des bords photographiques visibles."""
import cv2 as cv
import numpy as np

from r12.geometrie import continuity


def length(line):
    return np.linalg.norm(line[1]-line[0])


def frame(line):
    u=(line[1]-line[0])/length(line)
    return u,np.array([-u[1],u[0]])


def sample(gray, coords):
    p=np.clip(np.rint(coords).astype(int),0,np.array(gray.shape[::-1])-1)
    return gray[p[:,1],p[:,0]].astype(float)


def profile(gray,line,offset=2):
    _,normal=frame(line)
    pts=np.linspace(*line,11)[1:-1]
    return np.array([np.median(sample(gray,pts+sign*offset*normal)) for sign in (-1,1)])


def intervals(line,other):
    u,n=frame(line)
    a,b=np.sort((other-line[0])@u)
    overlap=min(length(line),b)-max(0.,a)
    return u,n,a,b,overlap


def reconstruct(board,supported):
    gray=cv.cvtColor(board,cv.COLOR_BGR2GRAY)
    raw=cv.createLineSegmentDetector(cv.LSD_REFINE_STD).detect(gray)[0]
    if raw is None:
        return []
    lines=sorted((l for l in raw.reshape(-1,2,2).astype(float) if length(l)>=12),key=length,reverse=True)[:200]
    # LSD's signed gradient determines the orientation. Canonicalize for comparing flank colors.
    lines=[l if tuple(l[0])<tuple(l[1]) else l[::-1] for l in lines]
    colors=[profile(gray,l) for l in lines]
    used=set()
    fragments=[]
    for i,line in enumerate(lines):
        if i in used:
            continue
        seed=line.copy()
        u,n=frame(seed)
        lo,hi=0.,length(seed)
        for _ in range(3):
            choices=[]
            for j,other in enumerate(lines):
                if j==i or j in used:
                    continue
                v,_=frame(other)
                reverse=u@v<0
                color=colors[j][::-1] if reverse else colors[j]
                if abs(u@v)<np.cos(np.deg2rad(5)) or max(abs((other-seed[0])@n))>2.5 or max(abs(color-colors[i]))>25:
                    continue
                a,b=np.sort((other-seed[0])@u)
                gap=max(lo-b,a-hi)
                if -3<=gap<=14:
                    choices.append((gap,j,a,b))
            if not choices:
                break
            _,j,a,b=min(choices)
            used.add(j)
            lo,hi=min(lo,a),max(hi,b)
        used.add(i)
        merged=seed[0]+np.array([lo,hi])[:,None]*u
        if length(merged)>=24:
            fragments.append(merged)
    # A pair must flank the same stripe over most of both spans. Independent orientations survive intersections.
    pairs=[]
    features=[(frame(l)[0],profile(gray,l)) for l in fragments]
    for i,l in enumerate(fragments):
        for j in range(i+1,len(fragments)):
            m=fragments[j]
            dot=features[i][0]@features[j][0]
            if abs(dot)<np.cos(np.deg2rad(4)):
                continue
            u,n,a,b,overlap=intervals(l,m)
            signed=(m-l[0])@n
            widths=abs(signed)
            if signed.prod()<=0 or widths.min()<2 or widths.max()>10 or overlap<max(.65*min(length(l),length(m)),.5*max(length(l),length(m))):
                continue
            c1,c2=features[i][1],features[j][1]
            if np.diff(c1).item()*np.diff(c2).item()*np.sign(dot)>=0:
                continue
            mid=l[0]+.5*signed.mean()*n
            axis=mid+np.array([min(0,a),max(length(l),b)])[:,None]*u
            pairs.append((overlap/ max(length(l),length(m)),i,j,axis))
    paired=set()
    candidates=[]
    for _,i,j,axis in sorted(pairs,key=lambda x:-x[0]):
        if not ({i,j}&paired):
            paired.update((i,j))
            candidates.append((axis,True))
    candidates.extend((l,False) for i,l in enumerate(fragments) if i not in paired)
    ranked=[]
    for axis,pair in candidates:
        support=continuity(axis,supported)
        flank=profile(gray,axis,6)
        center=np.median(sample(gray,np.linspace(*axis,13)))
        contrast=min(abs(flank-center))
        if not pair and (length(axis)<40 or (support<.35 and contrast<18)):
            continue
        score=length(axis)*(.2+support+.5*pair)*(.5+.5*np.exp(-abs(flank[1]-flank[0])/40))
        ranked.append((score,axis))
    result=[]
    for _,axis in sorted(ranked,key=lambda x:-x[0]):
        if any(abs(frame(l)[0]@frame(axis)[0])>.996 and max(abs((axis-l[0])@frame(l)[1]))<=3
               and intervals(l,axis)[-1]>=.65*min(length(l),length(axis)) for l in result):
            continue
        result.append(axis)
        if len(result)==32:
            break
    return result
