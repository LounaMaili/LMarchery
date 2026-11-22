"""Run the six preregistered post-volley scoring experiments sequentially."""
import argparse
import hashlib
from pathlib import Path
import time

import cv2 as cv
import numpy as np
import torch

from lab_data import fingerprint, write_json
from r7.model import ContactNet, decode, full_maps, tensor
from r7.train import loss_fn
from r8.audit import CHECKPOINT
from r8.corpus import read
from r8.experiment import measure
from r9.bank import train_data
from r9.sampling import SEEDS, STEPS, batch_arrays, make_plan


def threshold(items):
    best = (0, 0, 2.0)
    scores = sorted({s for _,peaks in items for _,s in peaks},reverse=True)
    for s in scores:
        total = measure(items,s)["all"]
        # Adding peaks can increase maximal matching by at most the number of new peaks.
        if total["unmatched"] > len(items)/4:
            break
        best = max(best,(total["matched"],-total["unmatched"],s))
    return best[2]


def predictions(model, rows, images):
    model.eval()
    return [(r,decode(full_maps(model,images[r["id"]]))[0]) for r in rows]


def compact(items, t):
    metrics = measure(items,t)
    return {k:v for k,v in metrics.items() if k!="photos"}


def train_one(arm, seed, plan, plan_sha, train, dev, images, lines, initial, output):
    torch.manual_seed(seed)
    model = ContactNet().to(memory_format=torch.channels_last)
    model.load_state_dict(torch.load(initial,weights_only=True,map_location="cpu"))
    optimizer = torch.optim.AdamW(model.parameters(),lr=.001)
    lookup = {r["id"]:r for r in train}
    digest = hashlib.sha256()
    losses, curve = [], []
    start = time.monotonic()
    for step in range(STEPS+1):
        if step%24==0:
            train_items = predictions(model,train,images)
            dev_items = predictions(model,dev,images)
            locked = threshold(dev_items)
            curve.append(dict(step=step,threshold=locked,train050=compact(train_items,.5),
                              dev050=compact(dev_items,.5),devlocked=compact(dev_items,locked)))
        if step==STEPS:
            break
        model.train()
        photos,targets,masks = batch_arrays(plan[step],arm,lookup,images,lines,digest)
        optimizer.zero_grad()
        loss = loss_fn(model(tensor(photos)),torch.from_numpy(targets),torch.from_numpy(masks))
        if not torch.isfinite(loss):
            raise ValueError("Nonfinite loss")
        loss.backward()
        optimizer.step()
        losses.append(loss.item())
    name = f"{arm}-{seed}"
    path = output/(name+".pt")
    torch.save(model.state_dict(),path)
    reports = {}
    for split,items in (("train",train_items),("dev",dev_items)):
        reports[split] = dict(locked=measure(items,locked),at050=measure(items,.5),
            curve=[dict(t=t,**compact(items,t)) for t in sorted({i/20 for i in range(1,20)}|{.975,.99,2.,locked})])
        write_json(output/(name+"-"+split+"-peaks.json"),{r["id"]:p for r,p in items})
    result = dict(arm=arm,seed=seed,steps=STEPS,checkpoint_sha256=fingerprint(path),initial_sha256=CHECKPOINT,
                  plan_sha256=plan_sha,positive_sha256=digest.hexdigest(),
                  duration_s=time.monotonic()-start,losses=losses,curve=curve,threshold=locked,reports=reports)
    write_json(output/(name+".json"),result)
    print(name,locked,reports["dev"]["locked"]["all"],flush=True)
    return result


def prepare_plans(root, r7, r8, out):
    train,_ = train_data(r7,r8)
    bank = read(root/"bank/verified.json")
    lookup = {r["id"]:r for r in train}
    from r8.experiment import complete_heat
    out.mkdir(parents=True,exist_ok=False)
    for seed in SEEDS:
        plan=make_plan(train,bank,seed)
        for batch in plan:
            for item in batch[4:]:
                row=lookup[item["id"]]
                for arm in ("ordinary","targeted"):
                    target,known=complete_heat(row["marks"],row["ignore"],*item[arm])
                    if target.any() or not known.all():
                        raise ValueError("Unsafe negative window in preflight")
        write_json(out/(str(seed)+".json"),plan)


def run(root, r7, r8, out):
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    cv.setNumThreads(1)
    seals = read(root/"seal.json")
    for name,expected in seals.items():
        if fingerprint(root/name)!=expected:
            raise ValueError("Sealed input changed: "+name)
    if (fingerprint(Path(__file__).with_name("protocole.md"))!=read(root/"protocol-sha.json")
            or fingerprint(r7/"artifacts/r7/imagenet.pt")!=CHECKPOINT):
        raise ValueError("Protocol or R7 checkpoint changed")
    train,images = train_data(r7,r8)
    dev = read(root/"dev.json")
    reserved = read(root/"reserved.json")
    pools = [train,dev,reserved]
    for i,rows in enumerate(pools):
        for other in pools[i+1:]:
            for attr in ("id","group","sha256","boards"):
                def values(group):
                    return {v for r in group for v in (r[attr] if attr=="boards" else [r[attr]])}
                if values(rows)&values(other):
                    raise ValueError("Overlap between phases: "+attr)
    for row in dev:
        path = (r8/"data/r8" if row["origin"]=="r8-development" else root)/row["image"]
        if fingerprint(path)!=row["sha256"]:
            raise ValueError("Changed development image")
        images[row["id"]] = cv.imread(str(path))
    out.mkdir(parents=True,exist_ok=False)
    for seed in SEEDS:
        path=root/"plans"/(str(seed)+".json")
        plan=read(path)
        if len(plan)!=STEPS or fingerprint(path)!=seals["plans/"+path.name]:
            raise ValueError("Changed paired plan")
        pair=[train_one(arm,seed,plan,seals["plans/"+path.name],train,dev,images,read(root/"bank/shaft-lines.json"),
                        r7/"artifacts/r7/imagenet.pt",out) for arm in ("ordinary","targeted")]
        if pair[0]["positive_sha256"]!=pair[1]["positive_sha256"]:
            raise ValueError("Positive crops, labels or masks differ in paired arms")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode",choices=["plans","train"])
    for n in ("root","r7","r8","out"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    (prepare_plans if a.mode=="plans" else run)(a.root,a.r7,a.r8,a.out)
