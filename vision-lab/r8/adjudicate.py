"""Validate an independent human reading and prepare a local discrepancy dossier."""
import argparse
from datetime import date
from pathlib import Path

import numpy as np
from lab_data import fingerprint, write_json
from r7.prepare import project
from r8.corpus import read


def pair(value,size):
    a=np.asarray(value,dtype=float)
    if a.shape!=(2,) or not np.isfinite(a).all() or (a<0).any() or (a>=size).any():
        raise ValueError("Point outside the oriented photo")
    return a.tolist()


def compare(root,submission,output,reader,day,count_return=None):
    if not reader.strip():
        raise ValueError("Missing reader identity")
    date.fromisoformat(day)
    blank=read(root/"lecture-test/lecture.json")
    received=read(submission)
    if not isinstance(received,list) or len(received)!=len(blank):
        raise ValueError("Incomplete independent packet")
    lookup={r["id"]:r for r in received}
    key={r["alias"]:r["id"] for r in read(root/"cle-test.json")}
    corpus={r["id"]:r for r in read(root/"corpus.json")}
    prior={}
    if count_return is not None:
        record=read(count_return)
        raw=count_return.parent/Path(record["source"]).name
        if fingerprint(raw)!=record["sha256"]:
            raise ValueError("Original count return has changed")
        prior={r["photo"]:r for r in record["photos"]}
    results=[]
    for original in blank:
        alias=original["id"]
        r=lookup.get(alias,{})
        if (any(r.get(k)!=original[k] for k in ("id","sha256","taille"))
                or fingerprint(root/"lecture-test"/(alias+".png"))!=r["sha256"]):
            raise ValueError("Changed or missing photo identity")
        disposition=prior.get(alias,{}).get("status")
        if disposition in ("invalid_by_reader","reported_empty"):
            results.append(dict(photo=alias,sha256=original["sha256"],first_reading=prior[alias],
                                status=disposition,arbitrage=None))
            continue
        if type(r.get("inventaire_complet")) is not bool:
            raise ValueError("Unanswered completeness for "+alias)
        if not isinstance(r.get("tubes"),list) or not isinstance(r.get("zones_illisibles"),list):
            raise ValueError("Missing instance or uncertainty inventory")
        size=np.array(original["taille"])
        for m in r["tubes"]:
            if m.get("statut") not in {"visible","occulte","indeterminable","hors_champ"}:
                raise ValueError("Unknown visibility")
            if (m.get("entree") is None)!=(m["statut"]!="visible"):
                raise ValueError("Visibility requires a point only for visible contacts")
            if m["statut"]=="visible":
                pair(m["entree"],size)
            if len(m["axe"])!=2 or np.array_equal(pair(m["axe"][0],size),pair(m["axe"][1],size)):
                raise ValueError("Missing nondegenerate visible axis")
        for region in r["zones_illisibles"]:
            if len(region)!=2 or (np.array(pair(region[1],size))<=pair(region[0],size)).any():
                raise ValueError("Invalid uncertainty rectangle")
        ref=corpus[key[alias]]
        factor=size/np.array(ref["size"])
        assisted=[dict(axis=project(m["axis"],factor).tolist(),visibility=m["visibility"],
                       entry=project(m["entry"],factor).tolist() if m["entry"] is not None else None)
                  for m in ref["marks"]]
        results.append(dict(photo=alias,sha256=original["sha256"],independent=r,assisted=assisted,
                            first_reading=prior.get(alias),
                            assisted_ignore=[project(box,factor).tolist() for box in ref["ignore"]],
                            arbitrage=None,commentaire_arbitre=None))
    if output.exists():
        raise ValueError("Use a fresh path for this independent reading")
    write_json(output,dict(reader=reader,reading_date=day,reading_sha256=fingerprint(submission),
                           status="awaiting_human_adjudication",photos=results))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("root","submission","output"):
        p.add_argument(name,type=Path)
    p.add_argument("--reader",required=True)
    p.add_argument("--date",required=True)
    a=p.parse_args()
    compare(a.root,a.submission,a.output,a.reader,a.date)
