"""Compare frozen R3 to the R4 geometric check on annotated, held-out rows."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import resource
import time

import cv2 as cv
import numpy as np
import torch

from benchmark import frame_at_work_size, maximum_matches, series
from lab_data import fingerprint, read_manifest, safe_file, write_json
from r3.evaluate import metrics, row_metrics
from r3.inference import offsets, predict, propose
from r3.model import EntryNet
from r3.train import WINDOW
from r4.contact import filter_candidates
from r4.trial import MODEL_SHA256, POLICY_SHA256


def near_nuisances(row, candidates, scale):
    references = [np.asarray(mark["entry"])*scale for mark in row["arrows"] if mark["entry"] is not None]
    linked = {i for _, i in maximum_matches(references, candidates, 10)}
    counts = Counter()
    for i, point in enumerate(candidates):
        if i in linked:
            continue
        proximity = []
        for mark in row.get("nuisances", []):
            distance = np.linalg.norm(point - np.asarray(mark["point"])*scale)
            if distance <= max(5., mark["radius_px"]*float(np.mean(scale))+3):
                proximity.append((distance, mark["kind"]))
        if proximity:
            counts[min(proximity)[1]] += 1
    return dict(counts)


def summary(rows):
    counts = Counter()
    for row in rows:
        counts.update(row["unmatched_near_annotated_nuisance"])
    return metrics(rows) | {"unmatched_near_annotated_nuisance": dict(counts)}


def original_tiles(root, row):
    width, height = row["size"]
    tile_width, tile_height = WINDOW
    count = len(offsets(width, tile_width, 48)) * len(offsets(height, tile_height, 48))
    if count > 300:
        return None, count
    original = cv.imread(str(safe_file(root, row["image"])))
    if original is None or original.shape[:2] != (height, width):
        raise ValueError(f"Original dimensions changed: {row['id']}")
    return original, count


def run(root, checkpoint, policy_path, output, original_native=False):
    if fingerprint(checkpoint) != MODEL_SHA256 or fingerprint(policy_path) != POLICY_SHA256:
        raise ValueError("Expected the exact frozen R3 checkpoint and policy")
    rows = read_manifest(root)
    if not rows or any(row["split"] != "test" or "complete" not in row for row in rows):
        raise ValueError("The comparison requires a held-out labelled corpus")
    cv.setNumThreads(1)
    torch.set_num_threads(2)
    model = EntryNet()
    model.load_state_dict(torch.load(checkpoint, weights_only=True, map_location="cpu"))
    model.eval()
    policy = json.loads(policy_path.read_text(encoding="utf-8"))["thresholds"]
    output.mkdir(parents=True, exist_ok=False)
    by_condition = defaultdict(list)
    timings = defaultdict(list)
    decisions = []
    excluded = []
    if original_native:
        policy["original-native"] = policy["native"]
    for row in rows:
        frame, scale = frame_at_work_size(root, row, "image")
        for mode, threshold in policy.items():
            source = frame
            if mode == "original-native":
                source, tile_count = original_tiles(root, row)
                if source is None:
                    excluded.append({"id": row["id"], "tiles": tile_count})
                    continue
            start = time.perf_counter()
            maps = predict(model, source, mode != "global", "cpu")
            if mode == "original-native":
                # Keep the decision geometry at the same working scale in both arms.
                maps = np.stack([cv.resize(channel, frame.shape[1::-1], interpolation=cv.INTER_AREA)
                                 for channel in maps])
            entries, tubes, _ = propose(maps)
            proposed = [entry for entry in entries if entry.confidence >= threshold]
            timings[mode, "R3"].append((time.perf_counter()-start)*1000)
            start = time.perf_counter()
            accepted, rejected = filter_candidates(proposed, tubes, maps[0])
            timings[mode, "R4-gate"].append((time.perf_counter()-start)*1000)
            for name, items in (("R3", proposed), ("R4", accepted)):
                candidates = [entry.point for entry in items]
                result = row_metrics(row, candidates, tubes, scale)
                result.update(unmatched_near_annotated_nuisance=near_nuisances(row, candidates, scale))
                by_condition[mode, name, row["scenario"]].append(result)
                by_condition[mode, name, "all"].append(result)
            decisions.append({
                "id": row["id"], "mode": mode,
                "proposed": [(entry.point / scale).tolist() for entry in proposed],
                "accepted": [(entry.point / scale).tolist() for entry in accepted],
                "rejected": [
                    {"px": (entry.point / scale).tolist(), "rank": entry.confidence, "reason": reason}
                    for entry, reason in rejected
                ],
            })
    write_json(output / "decisions.json", decisions)
    write_json(output / "report.json", {
        "dataset_sha256": fingerprint(root / "manifest.json"),
        "model_sha256": MODEL_SHA256,
        "policy_sha256": POLICY_SHA256,
        "model_bytes": checkpoint.stat().st_size,
        "images": len(rows),
        "groups": len({row["source_group"] for row in rows}),
        "original_native_excluded": excluded,
        "rss_peak_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "metrics": {"/".join(key): summary(items) for key, items in by_condition.items()},
        "time_ms": {
            "/".join(key): series(items[2:] if len(items) > 4 else items, "ms")
            for key, items in timings.items()
        },
        "rejection_reasons": dict(Counter(item["reason"] for row in decisions for item in row["rejected"])),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("dataset", "checkpoint", "policy", "output"):
        parser.add_argument(key, type=Path)
    parser.add_argument("--original-native", action="store_true")
    args = parser.parse_args()
    run(args.dataset, args.checkpoint, args.policy, args.output, args.original_native)
