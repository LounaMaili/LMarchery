"""Single-photo predictions with global and native-resolution window modes."""
from dataclasses import dataclass

import cv2 as cv
import numpy as np
import torch

from detector import Tube
from r3.train import WINDOW, tensor_image


@dataclass(frozen=True)
class EntryCandidate:
    point: np.ndarray
    heat: float
    visibility: float

    @property
    def confidence(self):
        return self.heat * self.visibility


def offsets(length, patch, overlap):
    if length <= patch:
        return [0]
    values = list(range(0, length - patch + 1, patch - overlap))
    if values[-1] != length - patch:
        values.append(length - patch)
    return values


@torch.inference_mode()
def predict(model, frame, tiled, device):
    h, w = frame.shape[:2]
    if not tiled:
        scaled = cv.resize(frame, WINDOW, interpolation=cv.INTER_AREA)
        tensor = torch.as_tensor(tensor_image(scaled)[None], device=device)
        return np.stack([cv.resize(channel, (w, h), interpolation=cv.INTER_LINEAR)
                        for channel in model(tensor).sigmoid().cpu().numpy()[0]])
    tw, th = WINDOW
    padding = cv.copyMakeBorder(frame, 0, max(0, th-h), 0, max(0, tw-w), cv.BORDER_REPLICATE)
    ph, pw = padding.shape[:2]
    locations = [(x, y) for y in offsets(ph, th, 48) for x in offsets(pw, tw, 48)]
    if len(locations) > 300:
        raise ValueError("Frame exceeds the bounded native tile budget")
    blend = np.maximum(.12, np.outer(np.hanning(th), np.hanning(tw))).astype(np.float32)
    sums, weights = np.zeros((3, ph, pw), np.float32), np.zeros((ph, pw), np.float32)
    for start in range(0, len(locations), 8):
        batch = locations[start:start+8]
        patches = [tensor_image(padding[y:y+th, x:x+tw]) for x,y in batch]
        data = torch.as_tensor(np.stack(patches), device=device)
        outputs = model(data).sigmoid().cpu().numpy()
        for (x, y), maps in zip(batch, outputs, strict=True):
            sums[:, y:y+th, x:x+tw] += maps * blend
            weights[y:y+th, x:x+tw] += blend
    return (sums / weights)[:, :h, :w]


def propose(maps):
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
