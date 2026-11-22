"""Extract candidate hard windows from the sealed, exhaustively read training partition."""
import argparse
from math import ceil, floor
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import fingerprint, write_json
from r7.model import TILE
from r8.corpus import read


def train_data(r7, r8):
    if fingerprint(r8/"data/r8/corpus.json") != "e6bf88fa2a465078d855493a66d667d739efc5d68081e166a2a4124fa81d877f":
        raise ValueError("R8 corpus changed")
    rows = [r for r in read(r8/"data/r8/corpus.json") if r["split"] == "train" and r["exhaustive"]]
    images = {}
    for r in rows:
        directory = r7/"data/r7/pilot" if r["pool"] == "control" else r8/"data/r8"
        path = directory/r["image"]
        if fingerprint(path) != r["sha256"]:
            raise ValueError("Changed training image")
        images[r["id"]] = cv.imread(str(path))
    return rows, images


def clear_window(row, x, y):
    """Exclude even Gaussian tails around known entries and every uncertain rectangle."""
    if not row["exhaustive"] or row["split"] != "train":
        return False
    for m in row["marks"]:
        if m["entry"] is not None:
            px, py = m["entry"]
            if x-14 <= px < x+TILE+14 and y-14 <= py < y+TILE+14:
                return False
    return not any(floor(a[0]) < x+TILE and ceil(b[0]) >= x and floor(a[1]) < y+TILE and ceil(b[1]) >= y
                   for a,b in row["ignore"])


def placements(row, point):
    # Keep the verified negative at least 12 pixels inside each sampled window when possible.
    px, py = point
    w,h = row["size"]
    options = set()
    for dx in (16,80,160,240,304):
        for dy in (16,80,160,240,304):
            x = max(0,min(w-TILE,px-dx))
            y = max(0,min(h-TILE,py-dy))
            if clear_window(row,x,y):
                options.add((x,y))
    return sorted(options)


def mine(r7, r8, out):
    import torch
    from r7.model import ContactNet, decode, full_maps
    from r8.audit import CHECKPOINT
    rows, images = train_data(r7,r8)
    checkpoint = r7/"artifacts/r7/imagenet.pt"
    if fingerprint(checkpoint) != CHECKPOINT:
        raise ValueError("Changed starting checkpoint")
    torch.set_num_threads(4)
    cv.setNumThreads(1)
    model = ContactNet().to(memory_format=torch.channels_last)
    model.load_state_dict(torch.load(checkpoint,weights_only=True,map_location="cpu"))
    model.eval()
    candidates = []
    out.mkdir(parents=True,exist_ok=True)
    for row in rows:
        photo = images[row["id"]]
        selected = []
        for point,score in decode(full_maps(model,photo))[0]:
            if any(np.linalg.norm(np.subtract(point,p)) < 50 for p in selected):
                continue
            windows = placements(row,point)
            if windows:
                selected.append(point)
                candidates.append(dict(index=len(candidates)+1,id=row["id"],group=row["group"],
                       boards=row["boards"],sha256=row["sha256"],point=point,score=score,windows=windows))
            if len(selected)==10:
                break
    write_json(out/"candidates.json",candidates)
    for start in range(0,len(candidates),25):
        sheet=np.full((5*132,5*176,3),250,np.uint8)
        for k,c in enumerate(candidates[start:start+25]):
            photo=images[c["id"]]
            crop=cv.getRectSubPix(photo,(164,100),tuple(map(float,c["point"])))
            cv.circle(crop,(82,50),6,(235,0,230),1)
            x,y=k%5*176,k//5*132
            sheet[y:y+100,x:x+164]=crop
            cv.putText(sheet,f"{c['index']} {c['id']} {c['score']:.2f}",(x,y+117),0,.43,(20,20,20),1)
        cv.imwrite(str(out/f"page-{start//25}.jpg"),sheet)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("r7","r8","out"):
        p.add_argument(name,type=Path)
    a=p.parse_args()
    mine(a.r7,a.r8,a.out)
