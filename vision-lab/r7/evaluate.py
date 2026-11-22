"""Select on whole development photos, lock policies, then evaluate held-out photos."""
import argparse
import json
from pathlib import Path
import time

import cv2 as cv
import numpy as np
import torch

from benchmark import maximum_matches, tube_matches
from detector import Tube
from lab_data import fingerprint, write_json
from r3.inference import predict, propose
from r6.diagnose import frozen
from r6.measure import totals
from r7.model import ContactNet, decode, full_maps
from r7.prepare import project, resized

ARMS=("r3","imagenet","scratch")


def predict_one(model,arm,im):
    work=resized(im,704 if arm=="r3" else 1408)
    scale=np.array(work.shape[1::-1])/im.shape[1::-1]
    if arm=="r3":
        entries,tubes,_=propose(predict(model,work,True,"cpu"))
        peaks=[(p.point,p.confidence) for p in entries]
    else:
        peaks,tubes=decode(full_maps(model,work))
    # R3 has always reported p / scale. R7 uses the pixel-center resampling map.
    def original(p):
        return (np.asarray(p)/scale if arm=="r3" else project(p,1/scale)).tolist()
    return dict(peaks=[(original(p),score) for p,score in peaks],
                axes=[original(t.ends) for t in tubes])


def observation(row,pred,threshold):
    size=np.array(row["oriented_size"])
    scale=np.rint(size*min(1,704/max(size)))/size
    marks=row["instances"]
    truths=np.array([i["entry"] for i in marks if i["entry"] is not None]).reshape(-1,2)
    points=np.array([p for p,score in pred["peaks"] if score>=threshold]).reshape(-1,2)
    matches=maximum_matches(truths*scale,points*scale,10)
    errors=[float(np.linalg.norm(truths[i]-points[j])) for i,j in matches]
    used={j for _,j in matches}
    unknown,wrong,local_unknown=0,0,0
    for j,p in enumerate(points):
        if j in used:
            continue
        in_ignore=any(np.all(p>=np.min(box,axis=0)) and np.all(p<=np.max(box,axis=0))
                      for box in row["ignore_regions"])
        if in_ignore:
            local_unknown+=1
        elif not row["complete"] and not row.get("outside_ignore_complete", False):
            unknown+=1
        else:
            wrong+=1
    axes=[np.array(i["axis"])*scale for i in marks if i.get("axis") is not None]
    eligible=[a for a in axes if np.linalg.norm(a[1]-a[0]) >=12]
    tubes=[Tube(np.array(a)*scale,np.empty((0,0))) for a in pred["axes"]]
    return dict(id=row["id"],group=row["group"],complete=row["complete"],flags=row["flags"],
                known=len(truths),matched=len(matches),proposals=len(points),present=len(marks),
                errors_px=errors,negative="empty" in row["flags"],tubes_known=len(eligible),
                tubes_matched=len(tube_matches(eligible,tubes)),unassigned=dict(false=wrong,
                uncertain_region=local_unknown,unknown=unknown),
                close={str(d):len(maximum_matches(truths*scale,points*scale,d)) for d in (3,5,10)})


def summary(rows):
    return dict(all=totals(rows),scenarios={f:totals([r for r in rows if f in r["flags"]])
               for f in sorted({f for r in rows for f in r["flags"]})},
               by_group={g:totals([r for r in rows if r["group"]==g])
                         for g in sorted({r["group"] for r in rows})})


def select(items):
    grid=[]
    for t in [i/20 for i in range(1,20)]+[2.]:
        rows=[observation(r,p,t) for r,p in items]
        hits=sum(r["matched"] for r in rows)
        unmatched=sum(r["proposals"]-r["matched"] for r in rows)
        grid.append(dict(threshold=t,matched=hits,unmatched=unmatched,photos=len(rows)))
    eligible=[g for g in grid if g["unmatched"]<=.25*g["photos"]]
    best=max(eligible,key=lambda g:(g["matched"],-g["unmatched"],g["threshold"]))
    return best["threshold"],grid


def diagnostic(root):
    rows=[]
    for r in json.loads((root/"pilot/annotations.json").read_text()):
        if r["frame"] not in (0,1,7):
            continue
        inst=[]
        if r["polygon"]:
            pts=np.array(r["polygon"])
            center=pts.mean(0)
            axis=np.linalg.eigh(np.cov(pts.T))[1][:,-1]
            ends=center+np.quantile((pts-center)@axis,[0,1])[:,None]*axis
            inst=[dict(axis=ends.tolist(),entry=r["entry"])]
        flags=["sequence_empty" if not inst else "single" if r["frame"]==1 else "latest_only"]
        if not inst:
            flags.append("empty")
        rows.append(dict(id=r["id"], group=r["group"], oriented_size=r["oriented_size"],
                         source_file="crigges/originals/"+r["source_file"],source_sha256=r["source_sha256"],
                         instances=inst,ignore_regions=[],flags=flags,
                         complete=r["coverage"]=="empty" or (r["coverage"]=="single" and r["entry"] is not None)))
    return rows


def run(root,output,r3,split):
    torch.set_num_threads(4)
    cv.setNumThreads(1)
    cp={arm:(r3 if arm=="r3" else output/(arm+".pt")) for arm in ARMS}
    identity=dict(models={a:fingerprint(p) for a,p in cp.items()},
                  external_manifest=fingerprint(root/"external.json"),
                  train_manifest=fingerprint(root/"pilot/annotations.json"))
    policyfile=output/"policies.json"
    sealed={} if split=="dev" else json.loads(policyfile.read_text())
    if split!="dev" and sealed["identity"]!=identity:
        raise ValueError("Different corpus or checkpoint at selection time")
    rows=(diagnostic(root) if split=="fit" else
          [r for r in json.loads((root/"external.json").read_text()) if r["split"]==split])
    if not rows:
        raise ValueError("Empty split")
    metrics,policies,curves={},{},{}
    for arm in ARMS:
        model=frozen(r3) if arm=="r3" else ContactNet()
        if arm!="r3":
            model.load_state_dict(torch.load(cp[arm],weights_only=True,map_location="cpu"))
            model=model.to(memory_format=torch.channels_last)
        model.eval()
        items=[]
        for row in rows:
            file=root/row["source_file"]
            if fingerprint(file)!=row["source_sha256"]:
                raise ValueError("Changed original")
            start=time.monotonic()
            image=cv.imread(str(file))
            if image is None or list(image.shape[1::-1])!=row["oriented_size"]:
                raise ValueError("Unexpected oriented size")
            result=predict_one(model,arm,image)
            result["processing_s"]=time.monotonic()-start
            items.append((row,result))
        write_json(output/(split+"-"+arm+"-predictions.json"),{r["id"]:p for r,p in items})
        if split=="dev":
            policies[arm],curves[arm]=select(items)
        threshold=policies[arm] if split=="dev" else sealed["thresholds"][arm]
        thresholds=[(arm,threshold)]
        if split=="fit" and arm!="r3":
            thresholds.append((arm+"-fit-050",.50))
        if arm=="r3":
            thresholds.append(("r3-historical",.42))
        for name,t in thresholds:
            observations=[observation(r,p,t) for r,p in items]
            write_json(output/(split+"-"+name+"-observations.json"),observations)
            metrics[name]=summary(observations)|dict(threshold=t)
        print(split,arm,len(rows),"photos",threshold,flush=True)
    if split=="dev":
        write_json(policyfile,dict(identity=identity,thresholds=policies,dev_curves=curves))
    write_json(output/(split+"-results.json"),metrics)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for field in ("root","output","r3"):
        p.add_argument(field,type=Path)
    p.add_argument("split",choices=("dev","test","fit"))
    a=p.parse_args()
    run(a.root,a.output,a.r3,a.split)
