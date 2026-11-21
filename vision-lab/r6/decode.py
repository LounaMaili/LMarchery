"""Contact peaks supported by visible shaft pixels. Components may share contacts."""
import cv2 as cv
import numpy as np

from r3.windows import EntryCandidate


def multiple(maps):
    shaft, heat, visibility = maps
    # Keep one actual maximum per connected plateau, including quantized flat peaks.
    local = cv.dilate(heat, np.ones((3, 3), np.uint8))
    peaks = ((heat >= .11) & (heat == local)).astype(np.uint8)
    count, labels, stats, centers = cv.connectedComponentsWithStats(peaks)
    h, w = heat.shape
    entries = []
    for label in range(1, count):
        x0, y0, width, height = stats[label, :4]
        ys, xs = np.where(labels[y0:y0+height, x0:x0+width] == label)
        xs, ys = xs+x0, ys+y0
        nearest = np.argmin((xs-centers[label, 0])**2 + (ys-centers[label, 1])**2)
        x, y = int(xs[nearest]), int(ys[nearest])
        if min(x, w-1-x, y, h-1-y) < 4:
            continue
        x1, x2 = max(0, x-7), min(w, x+8)
        y1, y2 = max(0, y-7), min(h, y+8)
        yy, xx = np.ogrid[y1:y2, x1:x2]
        support = (shaft[y1:y2, x1:x2] >= .48) & ((xx-x)**2+(yy-y)**2 <= 49)
        if not support.any():
            continue
        seen = float(visibility[y1:y2, x1:x2][support].mean())
        if seen >= .50:
            entries.append(EntryCandidate(np.array([x, y]), float(heat[y, x]), seen))
    return sorted(entries, key=lambda item: -item.confidence)


def oracle(mask, marks):
    """Use the renderer's shaft pixels, rasterized point and visible-shaft channel."""
    heat = np.zeros(mask.shape[:2], np.float32)
    for mark in marks:
        if mark["entry"] is not None:
            x, y = np.rint(mark["entry"]).astype(int)
            heat[y, x] = 1
    return np.stack([mask[:, :, 1:].max(axis=2) / 255., heat, mask[:, :, 1] / 255.]).astype(np.float32)
