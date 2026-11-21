"""Image conventions and window fusion shared by offline inference backends."""
from dataclasses import dataclass

import cv2 as cv
import numpy as np

WINDOW = (192, 160)


def tensor_image(frame):
    return cv.cvtColor(frame, cv.COLOR_BGR2RGB).transpose(2, 0, 1).astype(np.float32) / 255.


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


def tiled_maps(frame, infer):
    h, w = frame.shape[:2]
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
        outputs = infer(np.stack(patches))
        for (x, y), maps in zip(batch, outputs, strict=True):
            sums[:, y:y+th, x:x+tw] += maps * blend
            weights[y:y+th, x:x+tw] += blend
    return (sums / weights)[:, :h, :w]
