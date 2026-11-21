"""Evaluate frozen R1 baselines and R2 on the exact same blind references."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path

import cv2 as cv
import numpy as np

from benchmark import frame_at_work_size, maximum_matches, series
from detector import LogisticOrientation, extract, proposals
from lab_data import project, write_json
from r2_corpus import read_corpus
from r2_detector import detect


def near_segment(point, axis, scale):
    origin, end = np.array(axis) * scale
    direction = end - origin
    position = np.clip((point - origin) @ direction / (direction @ direction), 0, 1)
    return np.linalg.norm(point - (origin + position * direction)) <= 10


def evaluate(row, points, scale, pair):
    wanted = [mark for mark in row["arrows"] if not pair or mark["state"] == "new"]
    visible = [mark for mark in wanted if mark["entry"] is not None]
    unresolved = [mark for mark in wanted if mark["entry"] is None]
    old = [mark for mark in row["arrows"] if pair and mark["state"] == "existing"]
    truth = [np.asarray(mark["entry"]) * scale for mark in visible]
    matched = maximum_matches(truth, points, 10)
    used = {i for _, i in matched}
    raw = [np.asarray(p) / scale for p in points]
    unassigned = {"false": 0, "occluded_axis": 0, "other_unresolved_axis": 0, "unknown": 0, "existing": 0}
    for i, point in enumerate(points):
        if i in used:
            continue
        if any(np.linalg.norm(point - target) <= 10 for target in truth):
            unassigned["false"] += 1
            continue
        if any((mark["entry"] is not None and np.linalg.norm(point - np.array(mark["entry"])*scale) <= 10)
               or (mark["entry"] is None and mark["axis"] is not None and near_segment(point, mark["axis"], scale))
               for mark in old):
            unassigned["existing"] += 1
            continue
        supported = [mark for mark in unresolved if mark["axis"] is not None
                     and near_segment(point, mark["axis"], scale)]
        if len(supported) == 1:
            key = "occluded_axis" if supported[0]["visibility"] == "occluded" else "other_unresolved_axis"
        elif supported or unresolved or not row["complete"]:
            key = "unknown"
        else:
            key = "false"
        unassigned[key] += 1
    errors, near, mm = [], [], []
    for reference_index, prediction_index in matched:
        mark = visible[reference_index]
        error = float(np.linalg.norm(raw[prediction_index] - mark["entry"]))
        errors.append(error)
        if mark["near_boundary"] is True:
            near.append(error)
        if row["geometry"] is not None:
            geometry = row["geometry"]
            inverse = np.linalg.inv(geometry["plane_to_image"])
            targets = project(inverse, [raw[prediction_index], mark["entry"]])
            mm.append(float(np.linalg.norm(targets[0] - targets[1]) *
                            geometry["diameter_mm"] / (2 * geometry["radius_plane"])))
    return {"known": len(visible), "matched": len(matched), "proposals": len(points),
            "entries_px": [p.tolist() for p in raw], "unassigned": unassigned,
            "errors_px": errors, "errors_mm": mm, "boundary_errors_px": near,
            "near_boundary": sum(mark["near_boundary"] is True for mark in visible),
            "occluded": sum(mark["visibility"] == "occluded" for mark in wanted),
            "no_visible_entry": not visible and (row["complete"] or bool(wanted)),
            "negative": row["complete"] and not row["arrows"],
            "grouped": any(mark["group"] is not None for mark in row["arrows"])}


def summarize(rows):
    totals = Counter()
    for row in rows:
        totals.update(row["unassigned"])
    return {"images": len(rows), "known": sum(r["known"] for r in rows),
            "matched": sum(r["matched"] for r in rows),
            "proposals": sum(r["proposals"] for r in rows),
            "proposals_per_image": sum(r["proposals"] for r in rows) / len(rows),
            "unassigned": dict(totals), "false_per_image": totals["false"] / len(rows),
            "negatives": sum(r["negative"] for r in rows),
            "negatives_without_proposals": sum(r["negative"] and not r["proposals"] for r in rows),
            "no_visible_entry_images": sum(r["no_visible_entry"] for r in rows),
            "no_visible_entry_without_proposals": sum(r["no_visible_entry"] and not r["proposals"] for r in rows),
            "occluded": sum(r["occluded"] for r in rows),
            "near_boundary": sum(r["near_boundary"] for r in rows),
            **{key: series([v for r in rows for v in r[key]], "mm" if key.endswith("_mm") else "px")
               for key in ("errors_px", "errors_mm", "boundary_errors_px")}}


def load_ranker(model):
    data = json.loads(model.read_text(encoding="utf-8"))
    return LogisticOrientation(np.array(data["weights"]), np.array(data["scale"]), data["threshold"])


def run(root, model, output):
    _, rows = read_corpus(root)
    ranker = load_ranker(model)
    output.mkdir(parents=True, exist_ok=False)
    observations, summaries = [], {}
    for row in rows:
        frame, scale = frame_at_work_size(root, row, "image")
        before = frame_at_work_size(root, row, "before")[0] if row["before"] else None
        for mode in ("single", "pair") if before is not None else ("single",):
            reference = before if mode == "pair" else None
            evidence = extract(frame, row["family"], reference)
            r2, details = detect(frame, reference, row["family"], ranker)
            for name, points, diagnostics in (
                ("R1-A", [p for p, _ in proposals(evidence)[0]], {"reason": evidence.reason}),
                ("R1-B", [p for p, _ in proposals(evidence, ranker)[0]], {"reason": evidence.reason}),
                ("R2", r2, details),
            ):
                modes = (mode, "single-new-only") if mode == "single" and before is not None else (mode,)
                for metric_mode in modes:
                    observations.append({"id": row["id"], "source_group": row["source_group"],
                                         "mode": metric_mode, "method": name, "diagnostics": diagnostics,
                                         **evaluate(row, points, scale, metric_mode != "single")})
    for mode in ("single", "pair", "single-new-only"):
        for name in ("R1-A", "R1-B", "R2"):
            chosen = [r for r in observations if r["mode"] == mode and r["method"] == name]
            for label, subset in (("all", chosen), ("groups", [r for r in chosen if r["grouped"]])):
                if subset:
                    summaries[f"{mode}/{name}/{label}"] = summarize(subset)
    write_json(output / "observations.json", observations)
    write_json(output / "summary.json", summaries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cv.setNumThreads(1)
    cv.setRNGSeed(20261121)
    run(args.dataset, args.model, args.output)
