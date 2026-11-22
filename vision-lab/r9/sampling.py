"""Build and verify the paired window plans used by both arms before training."""
import cv2 as cv
import numpy as np

from r7.model import TILE
from r8.experiment import complete_heat
from r9.bank import clear_window, placements

SEEDS = (9301,9302,9303)
STEPS = 96


def choose(rng, items):
    return items[int(rng.integers(len(items)))]


def by_key(items, field):
    groups = {}
    for item in items:
        groups.setdefault(item[field], []).append(item)
    return groups


def uniform_negative(row, rng):
    w,h = row["size"]
    for _ in range(10000):
        x,y = int(rng.integers(w-TILE+1)),int(rng.integers(h-TILE+1))
        if clear_window(row,x,y):
            return [x,y]
    raise ValueError("No ordinary negative window found for " + row["id"])


def make_plan(rows, bank, seed):
    rng = np.random.default_rng(seed)
    lookup = {r["id"]:r for r in rows}
    for item in bank:
        r = lookup[item["id"]]
        if (r["sha256"]!=item["sha256"] or r["split"]!="train" or not r["exhaustive"]
                or sorted(map(tuple,item["windows"])) != placements(r,item["point"])):
            raise ValueError("Unverified negative window")
    positives = by_key([r for r in rows if any(m["entry"] is not None for m in r["marks"])],"group")
    negative_groups = by_key(bank,"group")
    batches = []
    for _ in range(STEPS):
        batch = []
        for _ in range(4):
            row = choose(rng,choose(rng,list(positives.values())))
            m = choose(rng,[m for m in row["marks"] if m["entry"] is not None])
            xy = np.clip(np.rint(np.array(m["entry"])-TILE/2+rng.uniform(-96,96,2)),
                         0,np.array(row["size"])-TILE).astype(int).tolist()
            batch.append(dict(id=row["id"],positive=True,xy=xy,flip=bool(rng.integers(2))))
        for _ in range(4):
            group = choose(rng,list(negative_groups.values()))
            photo = choose(rng,list(by_key(group,"id").values()))
            category = choose(rng,list(by_key(photo,"category").values()))
            item = choose(rng,category)
            row = lookup[item["id"]]
            batch.append(dict(id=row["id"],positive=False,ordinary=uniform_negative(row,rng),
                              targeted=choose(rng,item["windows"]),index=item["index"],flip=bool(rng.integers(2))))
        batches.append(batch)
    return batches


def targets(row, lines, x, y):
    truth = np.zeros((2,TILE,TILE),np.float32)
    known = np.zeros_like(truth)
    truth[0],known[0] = complete_heat(row["marks"],row["ignore"],x,y)
    for m in row["marks"]:
        if m.get("polygon"):
            polygon = np.rint(np.subtract(m["polygon"],[x,y])).astype(np.int32)
            cv.fillPoly(truth[1],[polygon],1)
    for a,b in lines.get(row["id"],[]):
        cv.line(truth[1],tuple(np.subtract(a,[x,y])),tuple(np.subtract(b,[x,y])),1,1)
    if not row["marks"] or (row["pool"]=="control" and len(row["marks"])==1):
        known[1] = 1
    else:
        known[1] = truth[1]
    known[1] *= known[0]
    return truth, known


def batch_arrays(batch, arm, rows, photos, lines, positive_digest):
    images, labels, masks = [], [], []
    for item in batch:
        name = item["id"]
        x,y = item["xy"] if item["positive"] else item[arm]
        image = photos[name][y:y+TILE,x:x+TILE]
        truth, known = targets(rows[name],lines,x,y)
        if not item["positive"] and (truth[0].any() or not known[0].all()):
            raise ValueError("Negative contains a contact or an uncertainty region")
        if item["flip"]:
            image, truth, known = image[:,::-1],truth[:,:,::-1],known[:,:,::-1]
        if item["positive"]:
            for a in (image,truth,known):
                positive_digest.update(a.tobytes())
        images.append(image)
        labels.append(truth)
        masks.append(known)
    return images,np.stack(labels),np.stack(masks)
