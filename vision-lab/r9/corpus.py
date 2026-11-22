"""Freeze new development and future test acquisitions before opening model outputs."""
import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import fingerprint, write_json
from r5.review import reserved_hashes
from r7.inventory import dhash
from r7.prepare import project, resized
from r8.corpus import read
from r9.sampling import SEEDS


def freeze(root,r7,r8):
    decisions=read(root/"reading.json")
    assets={a["id"]:a for f in ("candidates","extra","last") for a in read(root/(f+"-verified.json"))}
    write_json(root/"sources.json",list(assets.values()))
    old=read(r8/"data/r8/corpus.json")
    dev=[r|dict(origin="r8-development") for r in old if r["split"]=="dev"]
    reserved=[]
    invalid_hash=reserved_hashes()|{a["sha256"] for a in read(Path(__file__).resolve().parents[1]/"r5/public-candidates.json")["frames"]}
    prior=read(r7/"data/r7/external.json")
    historical=[r7/"data/r7"/r["source_file"] for r in prior]
    historical += [r7/"data/r7/pilot"/r["work_file"] for r in read(r7/"data/r7/pilot/annotations.json")]
    historical += [r8/"data/r8"/r["image"] for r in read(r8/"data/r8/inventory.json")["assets"]]
    invalid_hash.update(fingerprint(p) for p in historical)
    near=[(p.name,"historical",dhash(p)) for p in historical]
    old_groups={r["group"].casefold() for r in old+prior}
    work=root/"work"
    work.mkdir(exist_ok=True)
    for name,decision in decisions.items():
        a=assets[name]
        split=decision["split"]
        path=root/a["image"]
        digest=fingerprint(path)
        notice=root/"media"/(name+".html")
        if (digest!=a["sha256"] or digest in invalid_hash or a["credit"].casefold() in old_groups
                or fingerprint(notice)!=a["notice_sha256"]):
            raise ValueError("Historical, changed or unlicensed source: "+name)
        invalid_hash.add(digest)
        sketch=dhash(path)
        for other,other_split,other_hash in near:
            if other_split!=split and (other_hash^sketch).bit_count()<=4:
                raise ValueError("Cross-partition near duplicate: "+other+" and "+name)
        near.append((name,split,sketch))
        row=dict(id=name,source=a,sha256=digest,group=a["credit"],boards=decision["boards"],split=split)
        if split=="test":
            reserved.append(row|dict(role=decision["role"]))
            continue
        if split!="dev":
            raise ValueError("Unexpected new training source")
        im=resized(cv.imread(str(path)))
        size=list(im.shape[1::-1])
        output=work/(name+".png")
        if not cv.imwrite(str(output),im,[cv.IMWRITE_PNG_COMPRESSION,1]):
            raise OSError(output)
        scale=np.array(size)/decision["display"]
        marks=[]
        for start,end,entry in decision["marks"]:
            marks.append(dict(axis=project([start,end],scale).tolist(),
                              entry=project(entry,scale).tolist() if entry is not None else None))
        dev.append(row|dict(size=size,marks=marks,flags=decision["flags"],exhaustive=decision["complete"],
                ignore=[project(box,scale).tolist() for box in decision["ignore"]],
                origin="new",sha256=fingerprint(output),image="work/"+output.name))
    write_json(root/"dev.json",dev)
    write_json(root/"reserved.json",reserved)
    write_json(root/"protocol-sha.json",fingerprint(Path(__file__).with_name("protocole.md")))
    names=["dev.json","reserved.json","protocol-sha.json","reading.json","bank/verified.json","bank/shaft-lines.json"]
    names += ["plans/"+str(s)+".json" for s in SEEDS]
    write_json(root/"seal.json",{n:fingerprint(root/n) for n in names})


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","r7","r8"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    freeze(a.root,a.r7,a.r8)
