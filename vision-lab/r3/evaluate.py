"""Freeze abstention thresholds on dev, then compare on held-out synthetic and real groups."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import resource
import time

import cv2 as cv
import numpy as np
import torch

from benchmark import frame_at_work_size, series, tube_matches
from detector import extract, proposals
from lab_data import fingerprint, read_manifest, write_json
from r2_corpus import read_corpus
from r2_detector import detect, _components
from r2_evaluate import evaluate, load_ranker, summarize
from r3.inference import predict, propose
from r3.model import EntryNet
from verify_r1 import R1_MODEL

THRESHOLDS = (.08, .12, .18, .25, .33, .42, .52, .65, .80)
MODES = ("global", "native")
FP_BUDGET = .25


def metrics(rows):
    result = summarize(rows)
    misses = result["known"]-result["matched"]
    unmatched = result["proposals"]-result["matched"]
    group = [row for row in rows if row["grouped"]]
    masked = [row for row in rows if row["occluded"]]
    result.update(missed=misses, recall=result["matched"]/result["known"] if result["known"] else None,
                  precision=result["matched"]/result["proposals"] if result["proposals"] else None,
                  unmatched_per_image=unmatched/len(rows),
                  masked_images=len(masked), masked_images_with_proposals=sum(r["proposals"] > 0 for r in masked),
                  grouped_known=sum(r["known"] for r in group), grouped_matched=sum(r["matched"] for r in group),
                  tubes_known=sum(r["tubes_known"] for r in rows) if "tubes_known" in rows[0] else None,
                  tubes_matched=sum(r["tubes_matched"] for r in rows) if "tubes_matched" in rows[0] else None,
                  tubes_proposed=sum(r["tubes_proposed"] for r in rows) if "tubes_proposed" in rows[0] else None,
                  misses_on_silent_positive_images=sum(r["known"] for r in rows if r["proposals"] == 0))
    return result


def select_threshold(curve):
    eligible = [row for row in curve if row["unmatched_per_image"] <= FP_BUDGET]
    if not eligible:
        return 1.01
    preferred = max(eligible, key=lambda r: (r["matched"], -r["unmatched_per_image"], r["threshold"]))
    return preferred["threshold"]


def row_metrics(row, entries, tubes, scale, pair=False):
    result = evaluate(row, entries, scale, pair)
    segments = [np.array(mark["axis"])*scale for mark in row["arrows"]
                if mark["axis"] is not None and (not pair or mark["state"] == "new")]
    eligible = [segment for segment in segments if np.linalg.norm(segment[0]-segment[1]) >= 12]
    found = tube_matches(eligible, tubes)
    return result | {"tubes_known": len(eligible), "tubes_matched": len(found), "tubes_proposed": len(tubes)}


def curve_for(items, thresholds):
    return [metrics([row_metrics(row, [item.point for item in candidates if item.confidence >= threshold],
                                 tubes, scale) for row, candidates, tubes, scale in items])
            | {"threshold": threshold} for threshold in thresholds]


def baseline(frame, before, row, ranker, scale):
    reference = row["family"]
    r1 = extract(frame, reference)
    r2, _ = detect(frame, None, reference, ranker)
    # R2 does not expose its shaft axes. Reproduce its primary single-image mask unchanged.
    primary = cv.absdiff(frame, cv.medianBlur(frame, 11)).max(axis=2) > 22
    comparisons = {
        "R1-A-single": row_metrics(row, [p for p, _ in proposals(r1)[0]], r1.tubes, scale),
        "R1-B-single": row_metrics(row, [p for p, _ in proposals(r1, ranker)[0]], r1.tubes, scale),
        "R2-single": row_metrics(row, r2, _components(primary), scale),
    }
    if before is not None:
        pair = extract(frame, reference, before)
        r2pair, _ = detect(frame, before, reference, ranker)
        comparisons["R1-B-pair"] = row_metrics(row, [p for p, _ in proposals(pair, ranker)[0]], pair.tubes, scale, True)
        comparisons["R2-pair"] = evaluate(row, r2pair, scale, True)
    return comparisons


def run(args):
    cv.setNumThreads(1)
    cv.setRNGSeed(20261121)
    torch.set_num_threads(2)
    if args.device == "cuda" and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    if args.split == "real":
        if args.select:
            raise ValueError("Real images are held out from selection")
        _, rows = read_corpus(args.dataset)
    else:
        rows = [row for row in read_manifest(args.dataset) if row["split"] == args.split]
        if not rows or any(not row["source_group"].startswith("r3-board-") for row in rows):
            raise ValueError("Expected R3 synthetic groups")
    model_sha = fingerprint(args.model)
    manifest_sha = fingerprint(args.dataset / "manifest.json")
    policy = None
    if not args.select:
        policy = json.loads(args.policy.read_text(encoding="utf-8"))
        if policy["model_sha256"] != model_sha or (args.split == "test" and policy["manifest_sha256"] != manifest_sha):
            raise ValueError("The frozen operating points apply to a different model or corpus")
    model = EntryNet().to(args.device)
    model.load_state_dict(torch.load(args.model, map_location=args.device, weights_only=True))
    model.eval()
    if fingerprint(args.r1_model) != R1_MODEL:
        raise ValueError("Expected original frozen Fedora R1 model")
    ranker = load_ranker(args.r1_model)
    observations = {mode: [] for mode in MODES}
    timings = {mode: [] for mode in MODES}
    abstentions = {mode: [] for mode in MODES}
    baselines = {}
    for row in rows:
        frame, factor = frame_at_work_size(args.dataset, row, "image")
        for mode in MODES:
            start = time.perf_counter()
            maps = predict(model, frame, mode == "native", args.device)
            entries, tubes, reasons = propose(maps)
            abstentions[mode].append(reasons)
            timings[mode].append((time.perf_counter()-start)*1000)
            observations[mode].append((row, entries, tubes, factor))
        if not args.select:
            before = frame_at_work_size(args.dataset, row, "before")[0] if row["before"] else None
            for method, result in baseline(frame, before, row, ranker, factor).items():
                baselines.setdefault(method, []).append(result)
    report = {"split": args.split, "sample_count": len(rows),
              "source_groups": len({r["source_group"] for r in rows}), "manifest_sha256": manifest_sha,
              "model_sha256": model_sha, "model_bytes": args.model.stat().st_size,
              "device": args.device, "threads": torch.get_num_threads(),
              "rss_peak_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              "cuda_peak_bytes": torch.cuda.max_memory_allocated() if args.device == "cuda" else None,
              "methods": {name: metrics(items) for name, items in baselines.items()}, "R3": {}}
    frozen = {}
    decisions = {}
    for mode in MODES:
        curve = curve_for(observations[mode], THRESHOLDS)
        chosen = select_threshold(curve) if args.select else policy["thresholds"][mode]
        frozen[mode] = chosen
        frame_decisions = []
        for (row, candidates, tubes, scale), rejected in zip(observations[mode], abstentions[mode], strict=True):
            accepted = [item for item in candidates if item.confidence >= chosen]
            reasons = Counter(rejected)
            if not tubes:
                reasons["no_shaft"] += 1
            reasons["confidence_below_threshold"] += len(candidates)-len(accepted)
            frame_decisions.append({"id": row["id"], "decision": "proposed" if accepted else "abstained",
                                    "reasons": {key: count for key,count in reasons.items() if count},
                                    "points": [{"pixel": (item.point/scale).tolist(), "heat": item.heat,
                                                "visibility": item.visibility, "rank": item.confidence} for item in accepted]})
        decisions[mode] = frame_decisions
        report["R3"][mode] = {"curve": curve, "operating_point": curve_for(observations[mode], [chosen])[0],
                              "latency_warm": series(timings[mode][2:], "ms")}
    args.output.mkdir(parents=True, exist_ok=False)
    write_json(args.output / "report.json", report)
    write_json(args.output / "decisions.json", decisions)
    if args.select:
        write_json(args.output / "policy.json", {"model_sha256": model_sha, "manifest_sha256": manifest_sha,
                                                "thresholds": frozen, "dev_unmatched_budget": FP_BUDGET})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("model", type=Path)
    parser.add_argument("r1_model", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--select", action="store_true")
    parser.add_argument("--split", choices=("dev", "test", "real"), required=True)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    selected = parser.parse_args()
    if selected.select != (selected.split == "dev") or (not selected.select and selected.policy is None):
        parser.error("Select on dev or supply a frozen policy for test/real")
    run(selected)
