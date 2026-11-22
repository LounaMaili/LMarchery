"""Replay only development photos at locked thresholds and for direct R7 comparison."""
import argparse
from pathlib import Path
import cv2 as cv
import torch
from lab_data import fingerprint, write_json
from r7.model import ContactNet
from r8.corpus import read
from r8.experiment import Trial, evaluate, measure

def run(root,old,out):
    torch.set_num_threads(4)
    cv.setNumThreads(1)
    trial=Trial(root,old)
    sources=[("R7",trial.checkpoint)]+[(p.stem,p) for p in sorted(out.glob("*.pt"))]
    results={}
    for name,path in sources:
        model=ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(path,weights_only=True,map_location="cpu"))
        items=evaluate(model,trial.dev,trial)
        grid=[(t,measure(items,t)) for t in [i/20 for i in range(1,20)]+[2.]]
        threshold,r=max(((t,r) for t,r in grid if r["all"]["unmatched"]<=.25*len(items)),
                        key=lambda g:(g[1]["all"]["matched"],-g[1]["all"]["unmatched"],g[0]))
        if name!="R7":
            original=read(out/(name+".json"))
            if original["dev_threshold"]!=threshold or original["dev"]!=r or original["checkpoint_sha256"]!=fingerprint(path):
                raise ValueError("Different outcome from original locked selection")
        write_json(out/(name+"-dev-peaks.json"),{r["id"]:p for r,p in items})
        results[name]=dict(locked=threshold,operating=r,at050=measure(items,.5),at090=measure(items,.9))
    write_json(out/"development.json",results)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","r7","out"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    run(a.root,a.r7,a.out)
