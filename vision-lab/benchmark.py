"""Fit on grouped synthetic training scenes and benchmark read-only proposals."""
from __future__ import annotations

import argparse
from collections import defaultdict
from functools import lru_cache
import json
from pathlib import Path
import platform
import resource
import time

import cv2 as cv
import numpy as np

from detector import WORK_SIDE, LogisticOrientation, extract, proposals, rectify_from_controls
from lab_data import project, read_manifest, safe_file, write_image, write_json, xy


def frame_at_work_size(root, row, field):
    path = safe_file(root, row[field])
    data = cv.imread(str(path))
    if data is None or tuple(data.shape[1::-1]) != tuple(row["size"]):
        raise ValueError(f"Unreadable or dimension mismatch: {row['id']} ({field})")
    w, h = row["size"]
    factor = min(1., WORK_SIDE/max(w, h))
    if factor < 1:
        data = cv.resize(data, (round(w*factor), round(h*factor)), interpolation=cv.INTER_AREA)
    return data, np.array([data.shape[1]/w, data.shape[0]/h])


def maximum_matches(reference, proposed, max_distance):
    """One-to-one matches. Prefer maximum count, then minimum sum of distances."""
    if not len(reference) or not len(proposed):
        return ()
    a = np.array([p / max_distance for p in reference])
    b = np.array([p / max_distance for p in proposed])
    distances = np.linalg.norm(a[None, :, :] - b[:, None, :], axis=2)
    return match_costs(distances)


def match_costs(distances):
    choices = [np.flatnonzero(row <= 1).tolist() for row in distances]

    @lru_cache(None)
    def solve(i, used):
        if i == len(choices):
            return 0, 0., ()
        best = solve(i+1, used)
        for j in choices[i]:
            if used & (1 << j):
                continue
            n, cost, pairs = solve(i+1, used | (1 << j))
            candidate = n+1, cost+float(distances[i, j]), ((j, i),) + pairs
            if (-candidate[0], candidate[1]) < (-best[0], best[1]):
                best = candidate
        return best

    return solve(0, 0)[2]


def tube_matches(references, tubes):
    if not len(references) or not len(tubes):
        return ()
    costs = np.full((len(tubes), len(references)), np.inf)
    for j, reference in enumerate(references):
        point, tail = reference
        length = np.linalg.norm(tail-point)
        if length < 12:
            continue
        direction = (tail-point)/length
        normal = np.array([-direction[1], direction[0]])
        for i, tube in enumerate(tubes):
            segment = tube.ends-point
            span = np.linalg.norm(segment[1]-segment[0])
            if span <= 0:
                continue
            sine = abs((segment[1]-segment[0]) @ normal)/span
            offset = np.abs(segment @ normal).mean()
            low, high = sorted(segment @ direction)
            overlap = (min(length, high)-max(0, low))/length
            if sine <= .30 and offset <= 8 and overlap >= .5:
                costs[i, j] = (sine/.30 + offset/8 + 1-min(1., overlap))/3
    return match_costs(costs)


def fit(root, rows):
    features, orientation, used_groups = [], [], set()
    for row in rows:
        if row["split"] != "train" or not row["before"]:
            continue
        frame, scale = frame_at_work_size(root, row, "image")
        reference, _ = frame_at_work_size(root, row, "before")
        truth = np.array([mark["entry"] for mark in row["arrows"] if mark["entry"] is not None])
        if not len(truth):
            continue
        truth *= scale
        for tube in extract(frame, row["family"], reference).tubes:
            distances = np.linalg.norm(tube.ends[:, None, :]-truth[None, :, :], axis=2).min(axis=1)
            if distances.min() <= 10 and distances.max() >= 23:
                features.append(LogisticOrientation.features(tube))
                orientation.append(int(distances[0] < distances[1]))
                used_groups.add(row["source_group"])
    ranker = LogisticOrientation.fit(features, orientation)
    return ranker, len(features), sorted(used_groups)


def geometry_diagnostics(row, evidence, scale):
    truth = row["geometry"]
    out = {"identified": evidence.geometry is not None, "control_homography": "unavailable"}
    if truth is None:
        return out
    h = np.asarray(truth["plane_to_image"])
    center = np.asarray(truth["center_plane"])
    radius = truth["radius_plane"]
    if evidence.geometry is not None:
        expected = project(h, [center, center+[radius, 0], center+[0, radius]])
        radius_px = np.linalg.norm(expected[1:]-expected[0], axis=1).mean()
        out["center_error_px"] = float(np.linalg.norm(np.asarray(evidence.geometry.center)/scale-expected[0]))
        out["radius_error_px"] = float(abs(evidence.geometry.radius/scale.mean()-radius_px))
    # Known synthetic control points are independent of the detector and distorted by small annotation errors.
    controls = center+np.array([[-.60, -.58], [.60, -.58], [.60, .58], [-.60, .58]])*radius
    image_controls = project(h, controls) + np.array([[.7,-.4], [-.5,.2], [-.25,.65], [.48,.31]])
    width, height = row["size"]
    try:
        estimated = rectify_from_controls(image_controls, controls, (height, width))
    except ValueError:
        out["control_homography"] = "refused"
        return out
    heldout = center + np.array([[-.33,0], [0,.16], [.44,-.23], [.02,.4], [.35,.31]])*radius
    distance = np.linalg.norm(project(estimated, project(h, heldout)) - heldout, axis=1)
    out["control_homography"] = "four-synthetic-controls"
    out["control_probe_p95_mm"] = float(np.quantile(distance, .95)*truth["diameter_mm"]/(2*radius))
    return out


def evaluate_frame(row, selected, ambiguous, evidence, scale, elapsed_ms):
    visible = [m for m in row["arrows"] if m["visibility"] == "visible"]
    unresolved = sum(m["visibility"] in {"occluded","ambiguous"} for m in row["arrows"])
    points = [point/scale for point, score in selected]
    truth = [np.array(m["entry"]) for m in visible]
    matches = maximum_matches(truth, points, 10/scale)
    shafts = [np.array([mark["entry"], mark["tail"]])*scale for mark in visible
              if mark["tail"] is not None and np.linalg.norm((np.array(mark["entry"])-mark["tail"])*scale) >= 12]
    located_shafts = tube_matches(shafts, evidence.tubes)
    physical = row["geometry"]
    effects = np.linalg.inv(physical["plane_to_image"]) if physical else None
    by_ref = {}
    for ref, pred in matches:
        px = float(np.linalg.norm(points[pred]-truth[ref]))
        mm = None
        if effects is not None:
            a, b = project(effects, [points[pred], truth[ref]])
            mm = float(np.linalg.norm(a-b)*physical["diameter_mm"]/(2*physical["radius_plane"]))
        by_ref[ref] = {"error_px": px, "error_mm": mm}
    unmatched = len(points)-len(matches)
    return {
        "id": row["id"], "split": row["split"], "source_group": row["source_group"], "condition": row["condition"],
        "family": row["family"], "material": row["material"], "scenario": row["scenario"],
        "mode": evidence.method, "with_geometry": evidence.geometry is not None,
        "refusal": evidence.reason, "elapsed_ms": elapsed_ms, "ambiguous_tubes": len(ambiguous),
        "unresolved_reference_arrows": unresolved,
        "shafts_known": len(shafts), "shafts_detected": len(located_shafts), "shaft_candidates": len(evidence.tubes),
        "fp": unmatched if not unresolved else 0, "unassigned": unmatched if unresolved else 0,
        "predictions": [{"xy": point.tolist(), "rank": score} for point, (_,score) in zip(points, selected)],
        "labels": [{"xy": mark["entry"], "near_boundary": mark["near_boundary"], "error": by_ref.get(i),
                    "hand_label_uncertainty_px": mark["uncertainty_px"]} for i, mark in enumerate(visible)],
    }


def series(values, unit):
    if not values:
        return None
    return {
        "unit": unit, "n": len(values), "median": float(np.median(values)),
        "p95": float(np.quantile(values, .95)), "max": float(max(values)),
    }


def summarize(rows):
    labels = [label for row in rows for label in row["labels"]]
    good = [label["error"] for label in labels if label["error"] is not None]
    got, total = len(good), len(labels)
    fp = sum(row["fp"] for row in rows)
    unassigned = sum(row["unassigned"] for row in rows)
    return {
        "images": len(rows), "known_positive_entries": total, "detected": got, "missed": total-got,
        "recall": got/total if total else None, "fp": fp, "unassigned": unassigned,
        "shafts_known": sum(row["shafts_known"] for row in rows),
        "shafts_detected": sum(row["shafts_detected"] for row in rows),
        "shaft_candidates": sum(row["shaft_candidates"] for row in rows),
        "false_positives_on_negative_frames": sum(row["fp"] for row in rows if row["scenario"] == "empty"),
        "ambiguous_tubes": sum(row["ambiguous_tubes"] for row in rows),
        "unresolved_reference_arrows": sum(row["unresolved_reference_arrows"] for row in rows),
        "refused_images": sum(row["refusal"] is not None for row in rows),
        "geometries": sum(row["with_geometry"] for row in rows),
        "errors_px": series([r["error_px"] for r in good], "px"),
        "errors_mm": series([r["error_mm"] for r in good if r["error_mm"] is not None], "mm"),
        "latency": series([row["elapsed_ms"] for row in rows], "ms"),
        "near_boundary": {
            "known": sum(label["near_boundary"] is True for label in labels),
            "detected": sum(label["near_boundary"] is True and label["error"] is not None for label in labels),
        },
    }


def detailed_summaries(rows):
    groups = defaultdict(list)
    for row in rows:
        parent = f"{row['split']}/{row['mode']}/{row['algorithm']}"
        groups[parent].append(row)
        for key in ("condition", "scenario", "material", "family"):
            groups[f"{parent}/{key}/{row[key]}"].append(row)
    return {k: summarize(v) for k, v in sorted(groups.items())}


def demo(path, image, row, selected, ambiguous, evidence, scale):
    drawn = image.copy()
    if evidence.geometry is not None:
        cv.circle(drawn, xy(evidence.geometry.center), round(evidence.geometry.radius), (210, 174, 86), 1, cv.LINE_AA)
    for mark in row["arrows"]:
        if mark["entry"] is not None:
            cv.drawMarker(drawn, xy(np.array(mark["entry"])*scale), (250,250,250), cv.MARKER_CROSS, 17, 2)
    for line in ambiguous:
        cv.line(drawn, xy(line[0]), xy(line[1]), (0,179,255), 2, cv.LINE_AA)
    for point, _ in selected:
        cv.circle(drawn, xy(point), 9, (67, 232, 119), 2, cv.LINE_AA)
    label = "reference +   proposal O   ambiguous --"
    cv.rectangle(drawn, (0,0), (drawn.shape[1],24), (25,25,25), -1)
    cv.putText(drawn, label, (7,17), cv.FONT_HERSHEY_SIMPLEX, .45, (244,244,244), 1, cv.LINE_AA)
    write_image(path, drawn)


def run(root, output, pretrained):
    rows = read_manifest(root)
    if pretrained:
        model = json.loads(pretrained.read_text(encoding="utf-8"))
        if model["schema"] != 1:
            raise ValueError("Unknown orientation features")
        ranker = LogisticOrientation(np.asarray(model["weights"]), np.asarray(model["scale"]), model["threshold"])
    else:
        ranker, count, groups = fit(root, rows)
        model = {"schema": 1, "weights": ranker.weights.tolist(), "scale": ranker.scale.tolist(),
                 "threshold": ranker.threshold, "train_candidates": count, "train_groups": groups}
    if set(model["train_groups"]) & {r["source_group"] for r in rows if r["split"] != "train"}:
        raise ValueError("Evaluation includes a model training source")
    output.mkdir(parents=True, exist_ok=False)
    write_json(output/"model.json", model)
    geometry, results = [], []
    demo_ids = {"scene-19-front", "scene-22-inclined", "scene-25-close-rings", "scene-28-front"}
    for row in rows:
        if row["split"] == "train":
            continue
        image, scale = frame_at_work_size(root, row, "image")
        before = frame_at_work_size(root, row,"before")[0] if row["before"] else None
        modes = [None, before] if before is not None else [None]
        for other in modes:
            t0 = time.perf_counter_ns()
            evidence = extract(image, row["family"], other)
            t_extract = time.perf_counter_ns()-t0
            if other is None:
                geometry.append({"id": row["id"], "split": row["split"], "condition": row["condition"],
                                 "family": row["family"], **geometry_diagnostics(row, evidence, scale)})
            for name, orientation in (("A-heuristic", None), ("B-learned", ranker)):
                t0 = time.perf_counter_ns()
                selected, ambiguous = proposals(evidence, orientation)
                milliseconds = (t_extract+time.perf_counter_ns()-t0)/1e6
                results.append({"algorithm": name, **evaluate_frame(row, selected, ambiguous, evidence, scale, milliseconds)})
                if row["id"] in demo_ids or row["source"] == "public-photo":
                    demo(output/f"{row['id']}-{evidence.method}-{name}.png", image, row, selected, ambiguous, evidence, scale)
    write_json(output/"observations.json", {"entries": results, "geometry": geometry})
    report = {
        "python": platform.python_version(), "numpy": np.__version__, "opencv": cv.__version__,
        "process_peak_rss_kib_linux": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "model_bytes": (output/"model.json").stat().st_size,
        "summaries": detailed_summaries(results),
    }
    write_json(output/"report.json", report)
    for k, entry in report["summaries"].items():
        if k.startswith("test/") and k.count("/") == 2:
            print(k, f"{entry['detected']}/{entry['known_positive_entries']}", "false positives", entry["fp"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path)
    args = parser.parse_args()
    cv.setNumThreads(1)
    cv.setRNGSeed(20261121)
    run(args.dataset, args.output, args.model)


if __name__ == "__main__":
    main()
