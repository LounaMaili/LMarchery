"""Single-photo predictions with global and native-resolution window modes."""

import cv2 as cv
import numpy as np
import torch

from detector import Tube
from r3.windows import EntryCandidate, WINDOW, offsets, tensor_image, tiled_maps


@torch.inference_mode()
def predict(model, frame, tiled, device):
    h, w = frame.shape[:2]
    if not tiled:
        scaled = cv.resize(frame, WINDOW, interpolation=cv.INTER_AREA)
        tensor = torch.as_tensor(tensor_image(scaled)[None], device=device)
        return np.stack([cv.resize(channel, (w, h), interpolation=cv.INTER_LINEAR)
                        for channel in model(tensor).sigmoid().cpu().numpy()[0]])
    def infer(patches):
        data = torch.as_tensor(patches, device=device)
        return model(data).sigmoid().cpu().numpy()

    return tiled_maps(frame, infer)


def propose(maps, *, multiple_per_component=False):
    shaft, heat, vis = maps
    h, w = shaft.shape
    mask = (shaft >= .48).astype(np.uint8)
    count, labels, stats, _ = cv.connectedComponentsWithStats(mask, connectivity=8)
    local = cv.dilate(heat, np.ones((9, 9), np.uint8))
    peak_rows, peak_cols = np.where((heat == local) & (heat >= .11))
    all_peaks = sorted(zip(peak_rows.tolist(), peak_cols.tolist()), key=lambda p: -heat[p])[:96]
    entries, tubes, reasons = [], [], []
    for label in np.argsort(-stats[1:, cv.CC_STAT_AREA])[:48] + 1:
        if label >= count or stats[label, cv.CC_STAT_AREA] < 14:
            continue
        ys, xs = np.where(labels == label)
        points = np.column_stack((xs, ys))
        center = points.mean(axis=0)
        variances, axes = np.linalg.eigh(np.cov(points.T))
        axis = axes[:, -1]
        ends = center + np.quantile((points-center) @ axis, [.01, .99])[:, None] * axis
        if np.linalg.norm(ends[0]-ends[1]) < 15 or variances[-1] < 4*max(1., variances[0]):
            continue
        tubes.append(Tube(ends, np.empty((0, 0))))
        possibilities = []
        for y, x in all_peaks:
            point = np.array([x, y], dtype=float)
            if min(x, w-1-x, y, h-1-y) < 4:
                continue
            distance = np.square(points - point).sum(axis=1)
            nearby = distance <= 49
            if not nearby.any():
                continue
            visibility = float(np.mean(vis[ys[nearby], xs[nearby]]))
            if visibility < .50:
                continue
            possibilities.append(EntryCandidate(point, float(heat[y,x]), visibility))
        possibilities.sort(key=lambda entry: -entry.confidence)
        if not possibilities:
            reasons.append("no_supported_visible_contact")
        elif multiple_per_component:
            # R6 ablation: retain the historical peak, geometry and duplicate filters.
            for candidate in possibilities:
                if all(np.linalg.norm(item.point-candidate.point) > 5 for item in entries):
                    entries.append(candidate)
        elif (len(possibilities) > 1 and possibilities[1].confidence >= .88 * possibilities[0].confidence
              and np.linalg.norm(possibilities[0].point - possibilities[1].point) > 9):
            reasons.append("competing_peaks")
        else:
            candidate = possibilities[0]
            if any(np.linalg.norm(item.point-candidate.point) <= 5 for item in entries):
                reasons.append("shared_peak")
            else:
                entries.append(candidate)
    return entries, tubes, reasons
