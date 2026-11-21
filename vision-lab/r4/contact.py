"""A conservative geometric check for candidates from the unchanged R3 model."""
from collections import Counter

import cv2 as cv
import numpy as np

SHAFT_FLOOR = .48  # R3 segmentation foreground threshold.


def _support(shaft, point, axis, distances, half_width):
    normal = np.array([-axis[1], axis[0]])
    offsets = np.arange(-half_width, half_width + 1)
    pixels = point + distances[:, None, None] * axis + offsets[None, :, None] * normal
    x, y = pixels[..., 0], pixels[..., 1]
    h, w = shaft.shape
    if np.any((x < 0) | (x > w-1) | (y < 0) | (y > h-1)):
        return None
    values = cv.remap(shaft, x.astype(np.float32), y.astype(np.float32), cv.INTER_LINEAR)
    return np.max(values, axis=1) >= SHAFT_FLOOR


def verdict(point, tubes, shaft):
    """Require a nearby tube endpoint and support on the tube side only."""
    aligned, observed = 0, 0
    reasons = Counter()
    for tube in tubes:
        start, end = tube.ends
        if np.linalg.norm(point - end) < np.linalg.norm(point - start):
            start, end = end, start
        length = float(np.linalg.norm(end-start))
        if length < 18:
            continue
        axis = (end-start) / length
        position = float((point-start) @ axis)
        transverse = np.linalg.norm(point-start-position*axis)
        if transverse > 5 or abs(position) > min(14, max(6, .20*length)):
            continue
        aligned += 1
        forward = np.linspace(5, min(length*.68, 34), 9)
        inside = _support(shaft, point, axis, forward, half_width=2)
        outside = _support(shaft, point, axis, np.array([-10, -8, -6, -4.]), half_width=2)
        if inside is None or outside is None:
            reasons["insufficient_field_of_view"] += 1
        elif np.count_nonzero(inside) < 7 or np.count_nonzero(inside[:3]) < 2:
            reasons["shaft_support_discontinuous"] += 1
        elif np.count_nonzero(outside) > 1:
            reasons["support_continues_past_contact"] += 1
        else:
            observed += 1
    if observed > 1:
        return "competing_shaft_ends"
    if observed == 1:
        return None
    if not aligned:
        return "no_nearby_shaft_end"
    return reasons.most_common(1)[0][0]


def filter_candidates(candidates, tubes, shaft):
    accepted, rejected = [], []
    for candidate in candidates:
        reason = verdict(candidate.point, tubes, shaft)
        if reason is None:
            accepted.append(candidate)
        else:
            rejected.append((candidate, reason))
    return accepted, rejected
