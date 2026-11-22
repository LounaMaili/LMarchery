"""Inspect the stored post-volley predictions before any new learning."""
import argparse
import json
from pathlib import Path

import cv2 as cv
import numpy as np
from lab_data import fingerprint, write_json
from r7.prepare import project

CHECKPOINT="672d6b0b24c9713e6b8aa4df34a2cf3d5718f182de777e6032e3156fa06c8b27"


def full_peaks(heat):
    local=(heat==cv.dilate(heat,np.ones((9,9),np.uint8))) & (heat>=.05)
    count,labels,stats,_=cv.connectedComponentsWithStats(local.astype(np.uint8),connectivity=8)
    peaks=[]
    for label in range(1,count):
        x,y,w,h,_=stats[label]
        yy,xx=np.where(labels[y:y+h,x:x+w]==label)
        k=np.argmax(heat[y+yy,x+xx])
        px,py=int(x+xx[k]),int(y+yy[k])
        peaks.append(([px,py],float(heat[py,px])))
    return sorted(peaks,key=lambda p:(-p[1],p[0]))


def classify(row,peaks,threshold):
    selected=[p for p in peaks if p[1]>=threshold]
    distances=(np.linalg.norm((np.array([p[0] for p in selected])-row["entry"])
               *(np.rint(np.array(row["oriented_size"])*704/max(row["oriented_size"]))/row["oriented_size"]),
               axis=1) if selected and row["entry"] is not None else [])
    match=int(np.argmin(distances)) if len(distances) and min(distances)<=10 else None
    return match,len(selected)-int(match is not None)


def audit(old,out,replay=False):
    rows=[r for r in json.loads((old/"data/r7/pilot/annotations.json").read_text()) if r["frame"] in (0,1)]
    stored=json.loads((old/"artifacts/r7/fit-imagenet-predictions.json").read_text())
    if len(rows)!=40 or len({r["id"] for r in rows})!=40:
        raise ValueError("Expected twenty complete pairs")
    out.mkdir(parents=True,exist_ok=True)
    selected=[]
    scores={"true":[],"false":[]}
    counts={}
    strata={"empty":{},"single":{}}
    separated={"empty":[],"single":[]}
    pages=[]
    if replay:
        import torch
        from r7.model import ContactNet, full_maps
        torch.set_num_threads(4)
        cv.setNumThreads(1)
        cp=old/"artifacts/r7/imagenet.pt"
        if fingerprint(cp)!=CHECKPOINT:
            raise ValueError("Reference checkpoint changed")
        model=ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(cp,weights_only=True,map_location="cpu"))
        model.eval()
    for j,row in enumerate(rows):
        im=cv.imread(str(old/"data/r7/pilot"/row["work_file"]))
        scale=np.array(row["work_size"])/row["oriented_size"]
        peaks=stored[row["id"]]["peaks"]
        m,_=classify(row,peaks,.05)
        for i,p in enumerate(peaks):
            scores["true" if i==m else "false"].append(p[1])
            if i!=m:
                separated["empty" if row["frame"]==0 else "single"].append(p[1])
        # The first two saved peaks of each photo are inspected at the original ranking.
        for i,(pt,score) in enumerate(peaks[:2]):
            index=len(selected)
            if index%16==0:
                pages.append(np.full((4*164,4*218,3),245,np.uint8))
            x,y=project(pt,scale)
            patch=cv.getRectSubPix(im,(174,130),(float(x),float(y)))
            cv.drawMarker(patch,(87,65),(255,0,255),cv.MARKER_CROSS,8,1)
            u,v=(index%4)*218,(index%16//4)*164
            pages[-1][v:v+130,u:u+174]=patch
            status="V" if i==m else "F"
            cv.putText(pages[-1],f"{index+1:02} {row['id']} #{i+1} {score:.3f} {status}",(u+1,v+149),0,.45,(15,15,15),1)
            selected.append(dict(index=index+1,id=row["id"],rank=i+1,score=score,matched=i==m,original=pt))
        if replay:
            all_peaks=full_peaks(full_maps(model,im)[0])
            all_peaks=[(project(p,1/scale).tolist(),s) for p,s in all_peaks]
            if len(peaks)!=min(len(all_peaks),96) or not all(
                    np.allclose(p,a,rtol=0,atol=1e-5) and abs(s-b)<1e-6
                    for (p,s),(a,b) in zip(peaks,all_peaks)):
                raise ValueError("Replay mismatch: "+row["id"])
            for t in (.05,.50,.75,.90):
                for arm,seq in (("cap96",peaks),("unlimited",all_peaks)):
                    match,fp=classify(row,seq,t)
                    for acc in (counts,strata["empty" if row["frame"]==0 else "single"]):
                        a=acc.setdefault(f"{arm}@{t}",dict(matches=0,false=0,photos=0,saturated=0))
                        a["matches"]+=int(match is not None)
                        a["false"]+=fp
                        a["photos"]+=1
                        a["saturated"]+=int(sum(s>=t for _,s in all_peaks)>96)
    for i,page in enumerate(pages):
        cv.imwrite(str(out/f"planche-{i+1}.png"),page)
    write_json(out/"selection.json",selected)
    quant={k:dict(n=len(v),quantiles=np.percentile(v,[0,10,25,50,75,90,100]).tolist()) for k,v in scores.items()}
    write_json(out/("audit-replay.json" if replay else "audit-initial.json"),
               dict(scores=quant,false_score_by_scene={k:dict(n=len(v),quantiles=np.percentile(v,[0,10,25,50,75,90,100]).tolist()) for k,v in separated.items()},cap=counts,cap_by_scene=strata,stored_sha256=fingerprint(old/"artifacts/r7/fit-imagenet-predictions.json")))
    print(quant,counts,flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("r7",type=Path)
    p.add_argument("out",type=Path)
    p.add_argument("--replay",action="store_true")
    a=p.parse_args()
    audit(a.r7,a.out,a.replay)
