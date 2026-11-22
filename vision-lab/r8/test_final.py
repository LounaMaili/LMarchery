"""Apply the locked operating policy to adjudicated post-volley photos."""
import argparse
from datetime import date
from pathlib import Path

import cv2 as cv
import numpy as np
import torch

from lab_data import fingerprint, write_json
from r7.model import ContactNet, decode, full_maps
from r7.prepare import project
from r8.audit import CHECKPOINT
from r8.corpus import read
from r8.experiment import measure


def adjudicated_rows(root, comparison, verdict_path):
    verdict = read(verdict_path)
    date.fromisoformat(verdict["date"])
    if (verdict.get("comparison_sha256") != fingerprint(comparison)
            or not verdict["confirmed_by"].strip() or not verdict["decision"].strip()):
        raise ValueError("Missing human decision or changed comparison")
    records = read(comparison)["photos"]
    decisions = verdict["photos"]
    key = {r["alias"]: r["id"] for r in read(root / "cle-test.json")}
    reserved = {r["id"]: r for r in read(root / "corpus.json") if r["split"] == "test"}
    if (set(decisions) != set(key) or len(records) != len(key)
            or {r["photo"] for r in records} != set(key) or set(reserved) != set(key.values())):
        raise ValueError("Adjudication differs from sealed test roster")
    accepted, excluded = [], []
    for row in records:
        alias = row["photo"]
        source = reserved[key[alias]]
        decision = decisions[alias]
        if fingerprint(root / "lecture-test" / (alias + ".png")) != row["sha256"]:
            raise ValueError("Blind photo identity changed")
        if decision == "invalid" and row.get("status") == "invalid_by_reader":
            excluded.append(alias)
            continue
        if decision == "empty" and row.get("status") == "reported_empty":
            marks, ignore, complete = [], [], True
        elif decision in ("accept_complete", "accept_partial") and "independent" in row:
            form = row["independent"]
            scale = np.array(source["size"]) / form["taille"]
            marks = [dict(axis=project(m["axe"], scale).tolist(),
                          entry=project(m["entree"], scale).tolist() if m["entree"] is not None else None,
                          visibility=m["statut"]) for m in form["tubes"]]
            complete = decision == "accept_complete"
            ignore = [project(box, scale).tolist() for box in form["zones_illisibles"]]
        else:
            raise ValueError("Missing resolution for " + alias)
        accepted.append(source | dict(alias=alias, marks=marks, exhaustive=complete, ignore=ignore))
    return accepted, excluded


def run(root, old, train, aggregate, comparison, verdict, out):
    if out.exists():
        raise ValueError("Use a fresh test output directory")
    frozen = read(aggregate)
    choice = frozen["choice"]
    if (frozen["corpus_sha256"] != fingerprint(root / "corpus.json")
            or fingerprint(Path(__file__).with_name("protocole.md")) != frozen["protocol_sha256"]
            or choice != read(train / "selection.json")):
        raise ValueError("Experimental protocol, corpus or selection changed")
    rows, excluded = adjudicated_rows(root, comparison, verdict)
    candidate = choice["arm"] + "-" + str(choice["seeds"][0])
    sources = [("R7", old / "artifacts/r7/imagenet.pt", CHECKPOINT),
               (candidate, train / (candidate + ".pt"), frozen["runs"][candidate]["checkpoint_sha256"])]
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    images = {}
    for row in rows:
        path = root / row["image"]
        if fingerprint(path) != row["sha256"]:
            raise ValueError("Work photo identity changed")
        images[row["id"]] = cv.imread(str(path))
    results, local_peaks = {}, {}
    for name, path, checksum in sources:
        if fingerprint(path) != checksum:
            raise ValueError("Changed model " + name)
        threshold = frozen["dev"][name]["threshold"]
        model = ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(path, weights_only=True, map_location="cpu"))
        model.eval()
        items = [(row, decode(full_maps(model, images[row["id"]]))[0]) for row in rows]
        results[name] = dict(threshold=threshold, checkpoint_sha256=checksum, **measure(items,threshold))
        local_peaks[name] = {row["alias"]: peaks for row, peaks in items}
    out.mkdir(parents=True)
    write_json(out / "resultats.json", dict(verdict_sha256=fingerprint(verdict), models=results,
               comparison_sha256=fingerprint(comparison), exclusions=excluded, reserved=len(rows)+len(excluded)))
    write_json(out / "pics.json",local_peaks)
    aggregate_results = {}
    for name, r in results.items():
        aggregate_results[name] = {k: r[k] for k in (
            "threshold", "checkpoint_sha256", "all", "worn", "grouped", "per_group")}
    write_json(out / "synthese.json", dict(
        selection_sha256=fingerprint(aggregate), reserved_photos=len(rows)+len(excluded),
        excluded_photos=len(excluded), evaluated_supports=len({b for r in rows for b in r["boards"]}),
        positive_groups=len({r["group"] for r in rows if r["marks"]}), models=aggregate_results))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "old", "train", "aggregate", "comparison", "verdict", "out"):
        p.add_argument(name, type=Path)
    a = p.parse_args()
    run(a.root, a.old, a.train, a.aggregate, a.comparison, a.verdict, a.out)
