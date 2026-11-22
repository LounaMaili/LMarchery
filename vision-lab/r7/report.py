"""Produce publishable aggregate counts, plus a portable local annotation packet."""
import argparse
from collections import Counter
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from lab_data import fingerprint, write_json


def report(root,exp,dest):
    read=lambda p:json.loads(p.read_text())
    train=read(root/"pilot/annotations.json")
    external=read(root/"external.json")
    src=read(root/"crigges/inventory.json")["files"]
    models={a:read(exp/(a+".json")) for a in ("imagenet","scratch")}
    sampled=read(exp/"sampled.json")
    performances={}
    for split in ("dev","test","fit"):
        performances[split]={}
        for arm,r in read(exp/(split+"-results.json")).items():
            performances[split][arm]=dict(threshold=r["threshold"], totals=r["all"],
                                         flags=r["scenarios"])
    out=dict(parent="63e8d3d6d03643348c8fd27846ddb8cb12abbd8a",
             protocol_commit="cd2ddf0",kaggle_bytes=sum(r["bytes"] for r in src),
             raw_png=sum(r["path"].endswith(".png") for r in src),
             raw_labelme=sum(r["path"].endswith(".json") for r in src),
             kaggle_sequences=len({r["sequence"] for r in train}),
             sampled_images=sampled["images"],crops_per_arm=sampled["crops"],train_images=len(train),train_coverage=Counter(r["coverage"] for r in train),
             contacts=Counter(r["entry_status"] for r in train),
             excluded_train=Counter(r["reason"] for r in read(root/"pilot/exclusions.json")),
             extern_photos=Counter(r["split"] for r in external), models=models,
             graph=read(exp/"graph/graph.json"),policies=read(exp/"policies.json"),
             metrics=performances,inventory_sha256=fingerprint(root/"manifest.json"))
    write_json(dest,out)
    # Put decision files next to their manifest paths for an exact reconstruction without pixels.
    manifest=read(root/"manifest.json")
    with ZipFile(exp/"corpus-et-lectures.zip","w",compression=ZIP_DEFLATED) as archive:
        for name in ["manifest.json",*manifest["documents"],"pilot/candidates.json",
                     "blind-external-cle.json","blind-train-uncertain-cle.json",
                     "dvids/570904.html","dvids/4194279.html"]:
            archive.write(root/name,name)
        for folder in ("blind-external","blind-train-uncertain"):
            for filename in ("lecture-vierge.json","CONSIGNE.txt"):
                archive.write(root/folder/filename,folder+"/"+filename)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","exp","aggregate"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    report(a.root,a.exp,a.aggregate)
