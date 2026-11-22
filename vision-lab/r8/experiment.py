"""Matched continuation of the frozen R7 model on single post-volley photographs."""
import argparse
import hashlib
from pathlib import Path
import time

import cv2 as cv
import numpy as np
import torch

from benchmark import maximum_matches
from lab_data import fingerprint, write_json
from r7.model import ContactNet, TILE, decode, full_maps, tensor
from r7.train import GRID_X, GRID_Y, loss_fn, supervision
from r8.audit import CHECKPOINT
from r8.corpus import read


def complete_heat(marks,ignore,x,y):
    target=np.zeros((TILE,TILE),np.float32)
    known=np.ones_like(target)
    for lo,hi in ignore:
        a,b=np.maximum(np.floor(np.subtract(lo,[x,y])).astype(int),0)
        c,d=np.minimum(np.ceil(np.subtract(hi,[x,y])).astype(int)+1,TILE)
        if a<c and b<d:
            known[b:d,a:c]=0
    for mark in marks:
        if mark["entry"] is None:
            continue
        px,py=np.subtract(mark["entry"],[x,y])
        heat=np.exp(-((GRID_X-px)**2+(GRID_Y-py)**2)/32.)
        target=np.maximum(target,np.where(heat>=.01,heat,0).astype(np.float32))
    return target,known


class Trial:
    def __init__(self,root,old):
        self.rows=read(root/"corpus.json")
        self.root=root
        self.checkpoint=old/"artifacts/r7/imagenet.pt"
        if fingerprint(self.checkpoint)!=CHECKPOINT:
            raise ValueError("Mismatch in R7 checkpoint")
        self.images={}
        for r in self.rows:
            if r["split"]=="test":
                continue
            p=(old/"data/r7/pilot" if r["pool"]=="control" else root)/r["image"]
            if fingerprint(p)!=r["sha256"]:
                raise ValueError("Mismatch in work pixels")
            self.images[r["id"]]=cv.imread(str(p))
        self.control=[r for r in self.rows if r["pool"]=="control" and r["split"]=="train"]
        self.new=[r for r in self.rows if r["pool"]=="new" and r["split"]=="train"]
        self.dev=[r for r in self.rows if r["split"]=="dev"]

    def crop(self,arm,index,rng):
        extra=index>=6
        pool=self.new if extra and arm=="C" else self.control
        if index in (2,5,7):
            pool=[r for r in pool if "empty" in r["flags"]]
        elif extra:
            pool=[r for r in pool if len(r["marks"])>1]
        else:
            pool=[r for r in pool if r["frame"]==(1 if index in (0,3) else 3)]
        r=pool[int(rng.integers(len(pool)))]
        marks=r["marks"]
        im=self.images[r["id"]]
        h,w=im.shape[:2]
        if not marks:
            center=rng.uniform([0,0],[w,h])
        elif r["pool"]=="control":
            center=np.array(marks[-1]["source_center"])
        else:
            visible=[m["entry"] for m in marks if m["entry"] is not None]
            center=np.array(visible[rng.integers(len(visible))])
        x,y=np.clip(np.rint(center+rng.uniform(-116,116,2)-TILE/2),0,[w-TILE,h-TILE]).astype(int)
        patch=im[y:y+TILE,x:x+TILE]
        if r["pool"]=="control":
            cover="empty" if not marks else "single" if len(marks)==1 else "latest_only"
            last=marks[-1] if marks else {}
            entry=last.get("entry")
            poly=last.get("polygon")
            target,known=supervision(cover,np.array(entry) if entry is not None else None,
                                     np.array(poly) if poly else None,x,y)
        else:
            target=np.zeros((2,TILE,TILE),np.float32)
            known=np.zeros_like(target)
        if arm!="A":
            target[0],known[0]=complete_heat(marks,r["ignore"],x,y)
        if extra:
            known[1]=0
        flip=rng.random()<.5
        if flip:
            patch,target,known=patch[:,::-1],target[:,:,::-1],known[:,:,::-1]
        return patch,target,known,f"{r['id']}:{x}:{y}:{int(flip)}\n"


def observe(r,peaks,t):
    pts=np.array([p for p,s in peaks if s>=t]).reshape(-1,2)
    truth=np.array([m["entry"] for m in r["marks"] if m["entry"] is not None]).reshape(-1,2)
    size=np.array(r["size"])
    scale=np.rint(size*min(1,704/max(size)))/size
    pairs=maximum_matches(truth*scale,pts*scale,10)
    used={j for i,j in pairs}
    unresolved=0
    for j,p in enumerate(pts):
        if j not in used and (not r["exhaustive"] or any(
                np.all(p>=lo) and np.all(p<=hi) for lo,hi in r["ignore"])):
            unresolved+=1
    return dict(id=r["id"],group=r["group"],boards=r["boards"],flags=r["flags"],
                photos=1,known=len(truth),matched=len(pairs),proposals=len(pts),
                unmatched=len(pts)-len(pairs),false=len(pts)-len(pairs)-unresolved,
                unresolved=unresolved,silent=int(len(pts)==0),empty_with_points=int(not r["marks"] and len(pts)>0))


def measure(items,t):
    rows=[observe(r,p,t) for r,p in items]
    keys=("photos","known","matched","proposals","unmatched","false","unresolved","silent","empty_with_points")
    def total(rr):
        return {k:sum(r[k] for r in rr) for k in keys}
    return dict(all=total(rows),grouped=total([r for r in rows if "grouped" in r["flags"]]),
                worn=total([r for r in rows if "worn" in r["flags"]]),
                per_group={g:total([r for r in rows if r["group"]==g]) for g in sorted({r["group"] for r in rows})},
                photos=rows)


def evaluate(model,rows,trial):
    model.eval()
    items=[(r,decode(full_maps(model,trial.images[r["id"]]))[0]) for r in rows]
    return items


def run(trial,out,arm,seed):
    out.mkdir(parents=True,exist_ok=True)
    model=ContactNet().to(memory_format=torch.channels_last)
    model.load_state_dict(torch.load(trial.checkpoint,map_location="cpu",weights_only=True))
    streams=[np.random.default_rng(s) for s in np.random.SeedSequence(seed).spawn(2)]
    opt=torch.optim.AdamW(model.parameters(),lr=.001)
    pixel=np.zeros((2,3),np.int64)
    mass=np.zeros(2,np.float64)
    draws=[hashlib.sha256(),hashlib.sha256()]
    losses=[]
    curve=[]
    start=time.monotonic()
    for step in range(65):
        if step%16==0:
            items=evaluate(model,trial.control,trial)
            curve.append(dict(step=step,at050=measure(items,.5),at090=measure(items,.9)))
            print(arm,seed,step,curve[-1]["at090"]["all"],flush=True)
        if step==64:
            break
        model.train()
        batch=[trial.crop(arm,i,streams[int(i>=6)]) for i in range(8)]
        photos,labels,masks,ids=zip(*batch)
        for i,key in enumerate(ids):
            draws[int(i>=6)].update(key.encode())
        target,known=np.stack(labels),np.stack(masks)
        for ch in (0,1):
            valid=known[:,ch]>0
            pos=target[:,ch]>0
            pixel[ch]+=[np.count_nonzero(valid&pos),np.count_nonzero(valid&~pos),np.count_nonzero(~valid)]
            mass[ch]+=(target[:,ch]*known[:,ch]).sum(dtype=np.float64)
        opt.zero_grad()
        loss=loss_fn(model(tensor(photos)),torch.from_numpy(target),torch.from_numpy(known))
        if not torch.isfinite(loss):
            raise ValueError("Non-finite training loss")
        loss.backward()
        opt.step()
        losses.append(loss.item())
    cp=out/f"{arm}-{seed}.pt"
    torch.save(model.state_dict(),cp)
    dev=evaluate(model,trial.dev,trial)
    options=[(t,measure(dev,t)) for t in [i/20 for i in range(1,20)]+[2.]]
    t,report=max(((t,r) for t,r in options if r["all"]["unmatched"]<=.25*len(dev)),
                 key=lambda item:(item[1]["all"]["matched"],-item[1]["all"]["unmatched"],item[0]))
    record=dict(arm=arm,seed=seed,steps=64,curve=curve,dev=report,dev_threshold=t,
                dev_grid=[dict(t=v,**r["all"]) for v,r in options],losses=losses,
                pixels_positive_negative_ignored=pixel.tolist(),positive_mass=mass.tolist(),
                checkpoint_sha256=fingerprint(cp),initial_sha256=CHECKPOINT,
                corpus_sha256=fingerprint(trial.root/"corpus.json"),crop_digests=[h.hexdigest() for h in draws],
                wall_s=time.monotonic()-start)
    write_json(out/f"{arm}-{seed}.json",record)
    return record


def main(root,old,out):
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    trial=Trial(root,old)
    runs=[run(trial,out,a,8301) for a in ("A","B","C")]
    if not (runs[0]["crop_digests"]==runs[1]["crop_digests"] and
            runs[0]["crop_digests"][0]==runs[2]["crop_digests"][0]):
        raise ValueError("Unpaired inputs in ablation")
    def rank(r):
        latest=r["curve"][-1]["at090"]
        return (r["dev"]["all"]["matched"],-r["dev"]["all"]["unmatched"],
                latest["grouped"]["matched"],-latest["all"]["false"])
    winner=max(runs,key=rank)["arm"]
    write_json(out/"selection.json",dict(arm=winner,rankings={r["arm"]:rank(r) for r in runs},
               corpus_sha256=fingerprint(root/"corpus.json"),seeds=[8301,8302,8303]))
    for seed in (8302,8303):
        run(trial,out,winner,seed)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","old","out"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    main(a.root,a.old,a.out)
