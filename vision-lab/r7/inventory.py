"""Reconcile all local originals, splits, histories and targeted human reading packets."""
import argparse
from itertools import combinations
import json
from pathlib import Path
from zipfile import is_zipfile

import cv2 as cv
import numpy as np

from lab_data import fingerprint, write_json
from r5.review import reserved_hashes
from r7.freeze import blind


def dhash(path):
    im=cv.imread(str(path),cv.IMREAD_GRAYSCALE)
    reduced=cv.resize(im,(9,8),interpolation=cv.INTER_AREA)
    return int.from_bytes(np.packbits(reduced[:,1:]>reduced[:,:-1]).tobytes(),"big")


def run(root,archive):
    train=json.loads((root/"pilot/annotations.json").read_text())
    extern=json.loads((root/"external.json").read_text())
    originals=json.loads((root/"crigges/inventory.json").read_text())["files"]
    training=[r|dict(source_file="crigges/originals/"+r["source_file"]) for r in train]
    rows=training+extern
    historical=reserved_hashes()
    r5=Path(__file__).resolve().parents[1]/"r5/public-candidates.json"
    historical.update(r["sha256"] for r in json.loads(r5.read_text())["frames"])
    old=[]
    for directory in archive:
        for path in sorted(directory.iterdir()):
            if path.is_file() and path.suffix.lower() in {".jpg",".png",".jpeg"}:
                if is_zipfile(path):  # R6 kept both a download container and its decoded original.
                    continue
                historical.add(fingerprint(path))
                old.append((path.name,"historical",dhash(path)))
    hashes={r["source_sha256"] for r in rows}
    if hashes & historical or len(hashes)!=len(rows):
        raise ValueError("Repeated original across inventories")
    sketches=[(r["id"],r["split"],dhash(root/r["source_file"])) for r in rows]+old
    review=[]
    for (a,s,x),(b,t,y) in combinations(sketches,2):
        if s!=t and (x^y).bit_count()<=4:
            review.append(dict(a=a,b=b,split_a=s,split_b=t,hamming=(x^y).bit_count()))
    write_json(root/"near-duplicates.json",dict(method="64-bit dHash, whole image, Hamming <=4",
               historical_originals=len(old),candidates=review))
    if review:
        print("Review cross-split visual candidates:",review)
    files=["crigges/source.json","crigges/inventory.json","pilot/annotations.json","pilot/reading.json",
           "pilot/exclusions.json","commons-source.json","external.json","external-reading.json",
           "external-exclusions.json","near-duplicates.json"]
    splits={s:[r["id"] for r in rows if r["split"]==s] for s in ("train","dev","test")}
    write_json(root/"partitions.json",dict(splits=splits,groups={s:sorted({r["group"] for r in rows
               if r["split"]==s}) for s in splits}))
    files.append("partitions.json")
    commons=[dict(path="commons/"+p.name,bytes=p.stat().st_size,sha256=fingerprint(p))
             for p in sorted((root/"commons").glob("*.jpg"))]
    write_json(root/"manifest.json",dict(schema=1,public_originals=dict(
               kaggle=sum(i["path"].endswith(".png") for i in originals),commons=len(commons)),
               dvids_published_derivatives=sum(r["source_file"].startswith("dvids/") for r in extern),
               commons_files=commons, documents={f:fingerprint(root/f) for f in files},
               training_records=len(train), external_records=len(extern),splits=splits))
    uncertain=[r for r in training if r["frame"] and r["entry"] is None]
    blind(root,uncertain,root/"blind-train-uncertain")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("--historical",nargs="*",type=Path,default=[])
    a=p.parse_args()
    run(a.root,a.historical)
