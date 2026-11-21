"""Measure R2 on the frozen synthetic test frames, with no new fit."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import cv2 as cv

from benchmark import frame_at_work_size
from lab_data import read_manifest
from r2_detector import detect
from r2_evaluate import evaluate, load_ranker, summarize


def test_synthetic(root, ranker):
    results = defaultdict(list)
    for row in read_manifest(root):
        if row["split"] != "test":
            continue
        frame, scale = frame_at_work_size(root, row, "image")
        before, _ = frame_at_work_size(root, row, "before")
        group = "group" if row["scenario"] == "grouped" else None
        marks = [mark | {"group": group, "state": "new", "axis": None} for mark in row["arrows"]]
        reference = row | {"complete": True, "arrows": marks}
        for mode in ("single", "pair"):
            points, _ = detect(frame, before if mode == "pair" else None, row["family"], ranker)
            result = evaluate(reference, points, scale, mode == "pair")
            for tag in ("all", row["scenario"], row["condition"]):
                results[f"{mode}/{tag}"].append(result)
    return {name: summarize(items) for name, items in results.items()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cv.setNumThreads(1)
    cv.setRNGSeed(20261121)
    result = test_synthetic(args.dataset, load_ranker(args.model))
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2, sort_keys=True, allow_nan=False)
        output.write("\n")
