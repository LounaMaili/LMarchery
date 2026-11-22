"""Seal local visual decisions and produce an unlabeled packet of oriented originals."""
import argparse
from collections import Counter
import json
from pathlib import Path

import cv2 as cv

from lab_data import fingerprint, write_json
from r5.review import reserved_hashes


def freeze(root, decisions):
    rows = json.loads((root/"candidates.json").read_text())
    reading = json.loads(decisions.read_text())
    if reading["candidate_sha256"] != fingerprint(root/"candidates.json"):
        raise ValueError("Reading belongs to another inventory")
    historical = reserved_hashes()
    unresolved = reading["unresolved"]
    if set(unresolved) - {r["id"] for r in rows}:
        raise ValueError("Reading refers to an unknown photo")
    for r in rows:
        if r["source_sha256"] in historical:
            raise ValueError("Historical reference in train")
        r["entry"] = r["source_point"]
        r["entry_status"] = "source_and_assisted_visible" if r["entry"] else r["geometry_status"]
        if r["id"] in unresolved:
            r["entry_status"] = unresolved[r["id"]]
            r["entry"] = None
        r["coverage"] = ("empty" if r["frame"] == 0 else
                         "single" if r["frame"] == 1 else "latest_only")
        r["supervision"] = ("full reviewed negative" if r["coverage"] == "empty" else
                            "single visible source mask; heat only if resolved" if r["coverage"] == "single"
                            else "latest visible mask and resolved contact only; other pixels ignored")
    write_json(root/"annotations.json",rows)
    print(Counter(r["entry_status"] for r in rows),flush=True)


def blind(source, rows, dest):
    dest.mkdir(parents=True,exist_ok=True)
    key, templates = [], []
    for i,r in enumerate(sorted(rows,key=lambda r:r["source_sha256"])):
        alias = f"lecture-{i+1:03}"
        im=cv.imread(str(source/r["source_file"]))
        if im is None or list(im.shape[1::-1]) != r["oriented_size"]:
            raise ValueError("Packet image or orientation mismatch")
        file=dest/(alias+".png")
        if not cv.imwrite(str(file),im,[cv.IMWRITE_PNG_COMPRESSION,2]):
            raise OSError(file)
        templates.append(dict(alias=alias, pixels_sha256=fingerprint(file), size=r["oriented_size"],
                              instances=[], ignore_regions=[], coverage=None, notes=None))
        key.append(dict(alias=alias,id=r["id"], source_sha256=r["source_sha256"]))
    write_json(dest/"lecture-vierge.json",templates)
    write_json(dest.parent/(dest.name+"-cle.json"),key)
    (dest/"CONSIGNE.txt").write_text("Sur les pixels orientés, recenser les tubes visibles avec un segment sur le tube. "
        "Pour chacun distinguer jonction visible, occultée, ambiguë ou hors champ. Cliquer seulement "
        "les contacts observables, décrire les secteurs illisibles, l’usure et la complétude. "
        "Conserver une copie datée portant la référence du lecteur. Ne pas consulter les propositions.\n")


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("decisions",type=Path)
    a=p.parse_args()
    freeze(a.root,a.decisions)
