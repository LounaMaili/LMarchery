"""R2 contrast proposals with separate registration and endpoint abstention."""
from __future__ import annotations

import cv2 as cv
import numpy as np

from detector import _tube, fit_geometry, heuristic


def _gray(frame):
    return cv.cvtColor(frame, cv.COLOR_BGR2GRAY)


def _controls(current, before):
    """Return spatially separated tracks. Half are withheld from the fit."""
    first, second = _gray(current), _gray(before)
    points = cv.goodFeaturesToTrack(first, 360, .008, 8)
    if points is None:
        return None
    target, found, _ = cv.calcOpticalFlowPyrLK(first, second, points, None)
    if target is None:
        return None
    back, returned, _ = cv.calcOpticalFlowPyrLK(second, first, target, None)
    if back is None:
        return None
    error = np.linalg.norm((points - back).reshape(-1, 2), axis=1)
    ok = (found.ravel() == 1) & (returned.ravel() == 1) & (error <= 1)
    return points.reshape(-1, 2)[ok], target.reshape(-1, 2)[ok]


def register(current, before):
    if before.shape != current.shape:
        return None, {"reason": "incompatible_dimensions"}
    controls = _controls(current, before)
    if controls is None or len(controls[0]) < 24:
        return None, {"reason": "insufficient_tracks"}
    after_points, before_points = controls
    warp, _ = cv.estimateAffine2D(np.ascontiguousarray(before_points[::2]),
                                  np.ascontiguousarray(after_points[::2]),
                                  ransacReprojThreshold=1.8, maxIters=1200)
    if warp is None:
        return None, {"reason": "affine_fit_failed"}
    h, w = current.shape[:2]
    corners = np.float32([[0, 0], [w-1, 0], [0, h-1], [w-1, h-1]])
    movement = np.linalg.norm(cv.transform(corners[None], warp)[0]-corners, axis=1)
    if np.linalg.det(warp[:, :2]) <= 0 or movement.max() > .12*min(w, h):
        return None, {"reason": "framing_changed"}
    held = after_points[1::2]
    discrepancy = np.linalg.norm(cv.transform(before_points[None, 1::2], warp)[0]-held, axis=1)
    good = discrepancy <= 1.8
    quadrants = set((int(x >= w/2), int(y >= h/2)) for x, y in held[good])
    if good.mean() < .75 or len(quadrants) < 3:
        return None, {"reason": "held_out_tracks_disagree"}
    aligned = cv.warpAffine(before, warp, (w, h), flags=cv.INTER_LINEAR)
    valid = cv.warpAffine(np.ones((h, w), np.uint8), warp, (w, h),
                          flags=cv.INTER_NEAREST).astype(bool)
    valid = cv.erode(valid.astype(np.uint8), np.ones((11, 11), np.uint8)).astype(bool)
    if valid.mean() < .80:
        return None, {"reason": "insufficient_overlap"}
    # Fit per-channel brightness and contrast on valid pixels, ignoring changed objects
    # through the global percentiles. Thresholds remain fixed across the real pilot.
    low_before, high_before = np.percentile(aligned[valid][::19], [20, 80], axis=0)
    low_after, high_after = np.percentile(current[valid][::19], [20, 80], axis=0)
    gain = np.clip((high_after-low_after) / np.maximum(high_before-low_before, 8), .55, 1.8)
    fitted = np.clip((aligned.astype(float)-low_before)*gain + low_after, 0, 255)
    residual = np.abs(current-fitted).max(axis=2)
    median, upper = np.percentile(residual[valid], [50, 85])
    if upper > 44:
        return None, {"reason": "large_background_change"}
    score = np.where(valid, residual, 0)
    return score, {"reason": None, "held_out_tracks": int(len(held)),
                   "held_out_fraction": round(float(good.mean()), 4),
                   "background_median": float(median),
                   "background_p85": float(upper)}


def _components(mask):
    mask = cv.morphologyEx(mask.astype(np.uint8), cv.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    count, labels, stats, _ = cv.connectedComponentsWithStats(mask, connectivity=8)
    tubes = []
    for label in np.argsort(-stats[1:, cv.CC_STAT_AREA])[:48] + 1:
        if label < count and 20 <= stats[label, cv.CC_STAT_AREA] < .025*mask.size:
            y, x = np.where(labels == label)
            tube = _tube(np.column_stack((x, y)), mask.shape)
            if tube is not None:
                tubes.append(tube)
    return tubes


def stable_entries(primary, check, ranker, shape):
    """Require distinct orientation cues and an isolated, repeatable endpoint."""
    results, reasons = [], []
    for tube in primary:
        a, b = heuristic(tube), ranker.choose(tube)
        if a is None or b is None or a[0] != b[0]:
            reasons.append("orientation_uncertain")
            continue
        entry, tail = tube.ends[a[0]], tube.ends[1-a[0]]
        height, width = shape[:2]
        if min(entry[0], entry[1], width-1-entry[0], height-1-entry[1]) < 9:
            reasons.append("frame_edge")
            continue
        nearby = []
        for alternative in check:
            choice = heuristic(alternative)
            if choice is None:
                continue
            distances = np.linalg.norm(alternative.ends[[choice[0], 1-choice[0]]] - [entry, tail], axis=1)
            if distances[0] <= 5 and distances[1] <= 10:
                nearby.append(alternative)
        if len(nearby) != 1:
            reasons.append("endpoint_unstable")
            continue
        if any(other is not tube and np.min(np.linalg.norm(other.ends-entry, axis=1)) < 10
               for other in primary):
            reasons.append("competing_endpoints")
            continue
        results.append(entry)
    return results, reasons


def detect(current, before, family, ranker):
    if before is None:
        # Contrast is independent of tube color polarity and of a known target radius.
        local = cv.absdiff(current, cv.medianBlur(current, 11)).max(axis=2)
        check = cv.absdiff(current, cv.medianBlur(current, 15)).max(axis=2)
        primary, alternatives = _components(local > 22), _components(check > 27)
        diagnostics = {"reason": None, "ring_geometry_found": fit_geometry(current, family) is not None}
    else:
        residual, diagnostics = register(current, before)
        if residual is None:
            return [], diagnostics | {"tubes": 0, "abstentions": []}
        threshold = max(16, diagnostics["background_p85"] + 10)
        primary, alternatives = _components(residual > threshold), _components(residual > threshold + 6)
    entries, reasons = stable_entries(primary, alternatives, ranker, current.shape)
    return entries, diagnostics | {"tubes": len(primary), "abstentions": reasons}
