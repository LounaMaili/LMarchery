"""Aggregate paired results without reading any test pixels or test predictions."""
import argparse
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

import numpy as np
from lab_data import fingerprint, write_json
from r8.corpus import read
from r8.experiment import measure
from r9.sampling import SEEDS


def stable(curve, key):
    last = [step[key]["all"] for step in curve[-3:]]
    nums = [v["unmatched"] for v in last]
    return len({v["matched"] for v in last})==1 and max(nums)-min(nums)<=max(1,max(nums)*.1)


def bank_hits(train, bank, peaks):
    sizes={r["id"]:np.asarray(r["size"]) for r in train}
    counts=defaultdict(lambda:dict(n=0,at050=0,at090=0))
    for item in bank:
        size=sizes[item["id"]]
        scale=np.rint(size*min(1,704/max(size)))/size
        close=[s for p,s in peaks[item["id"]] if np.linalg.norm((np.array(p)-item["point"])*scale)<=10]
        stats=counts[item["category"]]
        stats["n"]+=1
        stats["at050"]+=int(max(close,default=0)>=.50)
        stats["at090"]+=int(max(close,default=0)>=.90)
    return dict(counts)


def counts(rows):
    return dict(photos=len(rows),provenances=len({r["group"] for r in rows}),
                supports=len({b for r in rows for b in r["boards"]}),
                visibles=sum(m["entry"] is not None for r in rows for m in r["marks"]),
                uncertain=sum(m["entry"] is None for r in rows for m in r["marks"]),
                empty=sum("empty" in r["flags"] for r in rows))


def first_contact(rows, peaks):
    scores=[]
    for row in rows:
        truth=np.array([m["entry"] for m in row["marks"] if m["entry"] is not None]).reshape(-1,2)
        size=np.array(row["size"])
        scale=np.rint(size*min(1,704/max(size)))/size
        for p,s in peaks[row["id"]]:
            if np.any(np.linalg.norm((truth-p)*scale,axis=1)<=10):
                scores.append(s)
    if not scores:
        return None
    t=max(scores)
    report=measure([(row,peaks[row["id"]]) for row in rows],t)
    return dict(threshold=t,**report["all"])


def report(root,out,r8):
    dev=read(root/"dev.json")
    train=[r for r in read(r8/"data/r8/corpus.json") if r["split"]=="train"]
    bank=read(root/"bank/verified.json")
    reserved=read(root/"reserved.json")
    trials=out/"train"
    result=dict(parent="f70a5bce9248dc675f94fd3a9dc71877e7fb1810",seal_sha256=fingerprint(root/"seal.json"),
                train=counts(train),dev=counts(dev),new_dev=counts([r for r in dev if r["origin"]=="new"]),
                reserved=dict(photos=len(reserved),groups=len({r["group"] for r in reserved}),
                              supports=len({b for r in reserved for b in r["boards"]})),
                categories=dict(Counter(r["category"] for r in bank)),bank_by_group={},runs={})
    for group in sorted({r["group"] for r in bank}):
        result["bank_by_group"][group]=dict(Counter(r["category"] for r in bank if r["group"]==group))
    deltas=[]
    empty_delta=[]
    per_group=Counter()
    for seed in SEEDS:
        pair={}
        for arm in ("ordinary","targeted"):
            name=f"{arm}-{seed}"
            r=read(trials/(name+".json"))
            if fingerprint(trials/(name+".pt"))!=r["checkpoint_sha256"]:
                raise ValueError("Checkpoint differs from trial")
            pair[arm]=r
            peak=read(trials/(name+"-train-peaks.json"))
            result["runs"][name]=dict(threshold=r["threshold"],sha256=r["checkpoint_sha256"],seconds=r["duration_s"],
                first_dev_contact=first_contact(dev,read(trials/(name+"-dev-peaks.json"))),
                positive_sha256=r["positive_sha256"],plan_sha256=r["plan_sha256"],bank=bank_hits(train,bank,peak),
                stable_train=stable(r["curve"],"train050"),stable_dev=stable(r["curve"],"dev050"),
                learning=[dict(step=s["step"],train050=s["train050"]["all"],dev050=s["dev050"]["all"],
                               devlocked=s["devlocked"]["all"],threshold=s["threshold"]) for s in r["curve"]],
                train050=r["reports"]["train"]["at050"]["all"],dev050=r["reports"]["dev"]["at050"]["all"],
                devlocked={k:v for k,v in r["reports"]["dev"]["locked"].items() if k!="photos"},
                trainlocked={k:v for k,v in r["reports"]["train"]["locked"].items() if k!="photos"})
        if pair["ordinary"]["positive_sha256"]!=pair["targeted"]["positive_sha256"]:
            raise ValueError("Different positive samples")
        a,b=[pair[k]["reports"]["dev"]["locked"] for k in ("ordinary","targeted")]
        deltas.append(b["all"]["matched"]-a["all"]["matched"])
        empty_delta.append(b["all"]["empty_with_points"]-a["all"]["empty_with_points"])
        for group,v in b["per_group"].items():
            per_group[group]+=v["matched"]-a["per_group"][group]["matched"]
    coherent=sum(d>0 for d in deltas)>=2 and min(deltas)>=0 and max(empty_delta)<=0 and sum(v>0 for v in per_group.values())>=2
    all_off=all(r["devlocked"]["all"]["proposals"]==0 for r in result["runs"].values())
    result["decision"]=dict(deltas=deltas,empty_deltas=empty_delta,deltas_by_group=dict(per_group),
                            coherent=coherent,all_abstained=all_off,
                            action="prepare_independent_review" if coherent else "keep_future_test_closed")
    write_json(out/"mesures.json",result)
    return result


def pack(root,out):
    notice=("Photos publiques Flickr : crédit, licence, source et empreintes individuels dans sources.json.\n"
            "Photos et annotations de séquences Crigges : CC BY 4.0, provenance dans bank/provenance.json.\n"
            "Les lectures R8/R9 et les sélections visuelles supplémentaires sont des annotations de recherche locales.\n"
            "Les observations initiales du futur test servent à réserver les acquisitions. Leurs références finales "
            "doivent faire l’objet d’une lecture indépendante avant toute évaluation.\n")
    with ZipFile(out/"corpus-banque.zip","w",ZIP_DEFLATED) as z:
        z.writestr("DROITS.txt",notice)
        for name in ("reading.json","sources.json","dev.json","reserved.json","seal.json","protocol-sha.json"):
            z.write(root/name,name)
        for dirname in ("bank","plans"):
            for p in sorted((root/dirname).glob("*")):
                z.write(p,str(p.relative_to(root)))
        for p in sorted(root.glob("*.json")):
            if p.stem.startswith(("catalog-","extra","last","candidates")):
                z.write(p,"discovery/"+p.name)
    with ZipFile(out/"sources-publiques.zip","w",ZIP_DEFLATED) as z:
        z.writestr("DROITS.txt",notice)
        z.write(root/"sources.json","sources.json")
        for a in read(root/"sources.json"):
            for p,sha in ((root/a["image"],a["sha256"]),(root/"media"/(a["id"]+".html"),a["notice_sha256"])):
                if fingerprint(p)!=sha:
                    raise ValueError("Changed source or rights notice")
                z.write(p,"media/"+p.name)
    with ZipFile(out/"experiences.zip","w",ZIP_DEFLATED) as z:
        for p in sorted((out/"train").glob("*")):
            z.write(p,"train/"+p.name)
        for p in [out/"mesures.json",out/"training.log",root/"seal.json"]:
            z.write(p,p.name)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","out","r8"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    report(a.root,a.out,a.r8)
    pack(a.root,a.out)
