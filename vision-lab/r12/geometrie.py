"""Indices d'un segment visible dans la photographie de la cible après la volée."""
import cv2 as cv
import numpy as np


def repere(image, shaft):
    size = np.array(image.shape[1::-1])
    shape = np.rint(size * min(1., 704 / max(size))).astype(int)
    board = cv.resize(image, tuple(shape), interpolation=cv.INTER_AREA)
    mask = cv.resize((shaft >= .5).astype(np.uint8), tuple(shape), interpolation=cv.INTER_NEAREST)
    yy, xx = np.ogrid[-3:4, -3:4]
    supported = cv.dilate(mask, ((xx * xx + yy * yy) <= 9).astype(np.uint8))
    return shape / size, board, mask, supported


def samples(axis):
    return np.linspace(axis[0], axis[1], 21)


def continuity(axis, supported):
    xy = np.clip(np.rint(samples(axis)).astype(int), 0, np.array(supported.shape[1::-1])-1)
    return float(supported[xy[:, 1], xy[:, 0]].mean())


def direction(axis):
    vector = axis[1] - axis[0]
    return vector / max(1.e-12, np.linalg.norm(vector))


def surface(end, outward, board):
    ahead = end + 8 * outward
    normal = np.array([-outward[1], outward[0]])
    xy = np.rint([ahead, ahead + 6 * normal, ahead - 6 * normal]).astype(int)
    if (xy < 0).any() or (xy >= board.shape[1::-1]).any():
        return .5
    colors = board[xy[:, 1], xy[:, 0]].astype(float)
    return float(np.exp(-np.abs(colors[1:] - colors[0]).mean() / 40))


def geometry_index(points, axes, board, supported, annotated=False):
    best = np.zeros(len(points))
    for axis in axes:
        if np.linalg.norm(axis[1] - axis[0]) < 1.e-8:
            continue
        unit = direction(axis)
        normal = np.array([-unit[1], unit[0]])
        across = (points - axis[0]) @ normal
        support = 1. if annotated else continuity(axis, supported)
        for end, outward in zip(axis, (-unit, unit), strict=True):
            dist2 = ((points - end) ** 2).sum(axis=1)
            kernel = np.exp(-.5 * ((across / 5) ** 2 + dist2 / 14 ** 2))
            best = np.maximum(best, kernel * support * (.5 + .5 * surface(end, outward, board)))
    return best


def axis_diagnostics(row, detected, scale, mask, supported):
    # A drawn axis is a centerline. Pixels outside it have no general negative label.
    marks = []
    ends = np.asarray(detected).reshape(-1, 2)
    for mark in row['marks']:
        axis = np.array(mark['axis']) * scale
        pts = samples(axis)
        unit = direction(axis)
        best = 0
        for segment in detected:
            d = direction(segment)
            if abs(d @ unit) < np.cos(np.deg2rad(20)):
                continue
            length = np.linalg.norm(segment[1] - segment[0])
            along = np.clip((pts-segment[0]) @ d, 0., length)
            nearest = segment[0] + along[:, None] * d
            best = max(best, int((np.linalg.norm(pts-nearest, axis=1) <= 6).sum()))
        entry = mark['entry']
        rec = dict(visible=entry is not None, mask_hits=int(round(21*continuity(axis,supported))),
                   aligned_hits=best, axis_recovered=best >= 11)
        if entry is not None:
            gt = np.array(entry) * scale
            other = axis[np.argmax(np.linalg.norm(axis-gt,axis=1))]
            for key, pt in [('entry',gt),('opposite_trace_end',other)]:
                distance = float(np.linalg.norm(ends-pt,axis=1).min()) if len(ends) else None
                rec[key+'_distance'] = distance
                rec[key+'_near'] = distance is not None and distance <= 10
        marks.append(rec)
    return dict(reference_axes=marks, detected_axes=len(detected), mask_pixels=int(mask.sum()),
                pixels=mask.size, grouped='grouped' in row['flags'], worn='worn' in row['flags'])
