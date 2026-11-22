"""Train both arms on the same sealed sequence of crops and the same masked losses."""
import argparse
from collections import Counter
from functools import lru_cache
import json
from pathlib import Path
import platform
import time

import cv2 as cv
import numpy as np
import torch
from torch.nn import functional as F
import torchvision

from lab_data import fingerprint, write_json
from r7.model import ContactNet, TILE, tensor
from r7.prepare import project

SEED = 7103
GRID_Y, GRID_X = np.ogrid[:TILE,:TILE]


class Sampler:
    def __init__(self, root):
        self.root = root
        self.sampled = Counter()
        self.rows = json.loads((root/"annotations.json").read_text())
        if any(r["split"] != "train" or r["group"] != "crigges-lg-g4" for r in self.rows):
            raise ValueError("Unexpected acquisition in training set")
        self.points = [r for r in self.rows if r["entry"] is not None]
        self.tubes = [r for r in self.rows if r["polygon"]]
        self.empty = [r for r in self.rows if r["coverage"] == "empty"]
        if not (self.points and self.tubes and self.empty):
            raise ValueError("Missing supervised stratum")

    @lru_cache(maxsize=180)
    def image(self, name, digest):
        path = self.root/name
        if fingerprint(path) != digest:
            raise ValueError("Changed work image")
        return cv.imread(str(path))

    def crop(self, rng, index):
        pool = (self.points,self.points,self.tubes,self.empty)[index%4]
        r = pool[int(rng.integers(len(pool)))]
        self.sampled[r["id"]] += 1
        im = self.image(r["work_file"],r["work_sha256"])
        h,w = im.shape[:2]
        scale = np.array([w,h])/r["oriented_size"]
        poly = project(r["polygon"],scale) if r["polygon"] else None
        entry = project(r["entry"],scale) if r["entry"] is not None else None
        if index%4 < 2:
            center = entry
        elif poly is not None:
            center = poly[rng.integers(len(poly))]
        else:
            center = rng.uniform([0,0],[w,h])
        x,y = np.clip(np.rint(center+rng.uniform(-116,116,2)-TILE/2),0,[w-TILE,h-TILE]).astype(int)
        patch = im[y:y+TILE,x:x+TILE]
        target, known = supervision(r["coverage"], entry, poly, x,y)
        if rng.random() < .5:
            patch,target,known = patch[:,::-1],target[:,:,::-1],known[:,:,::-1]
        return patch,target,known


def supervision(coverage, entry, polygon, x,y):
    target = np.zeros((2,TILE,TILE),np.float32)
    known = np.zeros_like(target)
    if coverage == "empty":
        known[:] = 1
    if polygon is not None:
        cv.fillPoly(target[1],[np.rint(polygon-[x,y]).astype(np.int32)],1)
        known[1] = 1 if coverage == "single" else target[1]
    if entry is not None:
        px,py = entry-[x,y]
        heat = np.exp(-((GRID_X-px)**2+(GRID_Y-py)**2)/32.)
        target[0] = np.where(heat >= .01,heat,0)
        known[0] = 1 if coverage == "single" else (heat >= .25)
    return target,known


def loss_fn(logits, target, known):
    # Balance supervised positive and negative pixels per head, ignoring the unknown region.
    error = F.binary_cross_entropy_with_logits(logits,target,reduction="none")
    pos = known * target
    neg = known * (target==0)
    dims = (0,2,3)
    return ((error*pos).sum(dims)/pos.sum(dims).clamp_min(1)
            + (error*neg).sum(dims)/neg.sum(dims).clamp_min(1)).mean()


def run(root, output, imagenet):
    output.mkdir(parents=True, exist_ok=True)
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    sampler = Sampler(root)
    for arm in ("imagenet","scratch"):
        sampler.sampled.clear()
        torch.manual_seed(SEED)
        rng = np.random.default_rng(SEED)
        model = ContactNet(imagenet if arm=="imagenet" else None).to(memory_format=torch.channels_last)
        opt = torch.optim.AdamW(model.parameters(),lr=.001)
        history = []
        start = time.monotonic()
        for epoch in range(6):
            losses=[]
            for step in range(32):
                photos, labels, masks = zip(*(sampler.crop(rng,i) for i in range(8)))
                opt.zero_grad()
                pred = model(tensor(photos))
                loss = loss_fn(pred,torch.from_numpy(np.stack(labels)),torch.from_numpy(np.stack(masks)))
                if not torch.isfinite(loss):
                    raise ValueError("Non-finite loss")
                loss.backward()
                opt.step()
                losses.append(loss.item())
            history.append(sum(losses)/len(losses))
            print(arm,epoch+1,history[-1],round(time.monotonic()-start,1),flush=True)
        exposure=dict(images=len(sampler.sampled),crops=sum(sampler.sampled.values()),counts=dict(sampler.sampled))
        if arm=="imagenet":
            write_json(output/"sampled.json",exposure)
        elif json.loads((output/"sampled.json").read_text())!=exposure:
            raise ValueError("Unpaired crop stream")
        dest = output/(arm+".pt")
        torch.save(model.state_dict(),dest)
        write_json(output/(arm+".json"),dict(arm=arm, seed=SEED, epochs=6, steps=192,
                   batch=8, threads=4, loss=history, duration_s=time.monotonic()-start,
                   parameters=sum(p.numel() for p in model.parameters()), bytes=dest.stat().st_size,
                   checkpoint_sha256=fingerprint(dest), annotations_sha256=fingerprint(root/"annotations.json"),
                   initial_weights_sha256=fingerprint(imagenet) if arm=="imagenet" else None,
                   python=platform.python_version(),torch=str(torch.__version__),
                   torchvision=str(torchvision.__version__),opencv=cv.__version__,numpy=np.__version__))


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("root","output","imagenet"):
        p.add_argument(name,type=Path)
    a=p.parse_args()
    run(a.root,a.output,a.imagenet)
