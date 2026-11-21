"""Read-only proposals from contrast, tube shape and physically distinct ends."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

import cv2 as cv
import numpy as np


WORK_SIDE = 704


@dataclass(frozen=True)
class Geometry:
    center: tuple[float, float]
    radius: float
    rings: tuple[str, ...]
    full_face: bool


@dataclass(frozen=True)
class Tube:
    ends: np.ndarray
    descriptors: np.ndarray


@dataclass(frozen=True)
class Evidence:
    tubes: list[Tube]
    geometry: Geometry | None
    method: str
    reason: str | None = None


class Ranker(Protocol):
    def choose(self, tube: Tube) -> tuple[int, float] | None: ...


def fit_geometry(frame: np.ndarray, family: str) -> Geometry | None:
    if family != "multicolor":
        # Yellow alone does not identify a second independent scale reference.
        return None
    hue, saturation, light = cv.split(cv.cvtColor(frame, cv.COLOR_BGR2HSV))
    vivid = (saturation >= 74) & (light >= 47)
    color_rings = (
        ("yellow", .2, (hue >= 16) & (hue <= 44)),
        ("red", .4, (hue <= 10) | (hue >= 171)),
        ("blue", .6, (hue >= 88) & (hue <= 130)),
    )
    h, w = hue.shape
    estimates = []
    for name, fraction, mask in color_rings:
        clear = cv.morphologyEx((mask & vivid).astype(np.uint8), cv.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        contours, _ = cv.findContours(clear, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_NONE)
        if not contours:
            continue
        contour = max(contours, key=cv.contourArea)
        x, y, cw, ch = cv.boundingRect(contour)
        if len(contour) < 20 or min(cw, ch) < 18 or min(x, y, w-x-cw, h-y-ch) < 3:
            continue
        center, axes, angle = cv.fitEllipse(contour)
        if min(axes) == 0 or max(axes)/min(axes) > 1.15:
            continue
        theta = np.deg2rad(angle)
        rotation = np.array([[np.cos(theta), -np.sin(theta)], [np.sin(theta), np.cos(theta)]])
        local = (contour[:, 0, :] - np.array(center)) @ rotation
        normalized = np.linalg.norm(local / (np.array(axes) / 2), axis=1)
        if float(np.quantile(np.abs(normalized - 1), .80)) > .08:
            continue
        estimates.append((name, np.array(center), sum(axes) / (4 * fraction)))
    if len(estimates) < 2:
        return None
    centers = np.array([e[1] for e in estimates])
    radii = np.array([e[2] for e in estimates])
    center, radius = np.median(centers, axis=0), float(np.median(radii))
    if max(np.linalg.norm(centers-center, axis=1)) > radius * .055 or np.ptp(radii) > radius * .13:
        return None
    cx, cy = map(float, center)
    if not 26 <= radius <= 2 * max(w, h):
        return None
    full = min(cx, cy, w-1-cx, h-1-cy) >= radius + 1.5
    return Geometry((cx, cy), radius, tuple(e[0] for e in estimates), full)


def rectify_from_controls(image_points, plane_points, image_shape):
    """Homography only from four explicit, visible and well-spread control points."""
    image, plane = np.asarray(image_points, np.float32), np.asarray(plane_points, np.float32)
    height, width = image_shape[:2]
    for points in (image, plane):
        if points.shape != (4, 2) or not np.isfinite(points).all():
            raise ValueError("Four finite control points required")
        shape = (points / np.maximum(np.ptp(points, axis=0), 1e-9)).astype(np.float32)
        if len(cv.convexHull(shape)) != 4 or not cv.isContourConvex(shape) or cv.contourArea(shape) < .12:
            raise ValueError("Control points must be well distributed in perimeter order")
    if ((image < 0) | (image >= [width, height])).any() or cv.contourArea(image) < .035 * width * height:
        raise ValueError("Image controls clipped or too close together")
    transform = cv.getPerspectiveTransform(image, plane)
    if not np.isfinite(transform).all() or abs(np.linalg.det(transform)) < 1e-10:
        raise ValueError("Degenerate projective transform")
    return transform


def foreground_pair(current: np.ndarray, before: np.ndarray) -> np.ndarray | None:
    if current.shape != before.shape:
        return None
    if np.array_equal(current, before):
        return np.zeros(current.shape[:2], np.uint8)
    first, second = [cv.cvtColor(x, cv.COLOR_BGR2GRAY) for x in (current, before)]
    try:
        score, warp = cv.findTransformECC(
            first, second, np.eye(2, 3, dtype=np.float32), cv.MOTION_TRANSLATION,
            (cv.TERM_CRITERIA_COUNT | cv.TERM_CRITERIA_EPS, 40, .0005), None, 5,
        )
    except cv.error:
        return None
    if not np.isfinite(score) or score < .91 or np.linalg.norm(warp[:, 2]) > 10:
        return None
    height, width = first.shape
    aligned = cv.warpAffine(before, warp, (width, height), flags=cv.INTER_LINEAR | cv.WARP_INVERSE_MAP,
                            borderMode=cv.BORDER_REFLECT_101)
    return (cv.absdiff(current, aligned).max(axis=2) > 31).astype(np.uint8)


def foreground_radial(frame: np.ndarray, geometry: Geometry) -> np.ndarray:
    h, w = frame.shape[:2]
    y, x = np.ogrid[:h, :w]
    distances = np.hypot(x-geometry.center[0], y-geometry.center[1]).astype(np.int32)
    # Sample around each radius, leaving substantial angular coverage for the background estimate.
    steps = np.arange(int(geometry.radius * .89) + 1)
    angles = np.linspace(0, 2*np.pi, 96, endpoint=False)
    u = np.rint(geometry.center[0] + steps[:, None] * np.cos(angles)).astype(int)
    v = np.rint(geometry.center[1] + steps[:, None] * np.sin(angles)).astype(int)
    available = (u >= 0) & (u < w) & (v >= 0) & (v < h)
    samples = frame[np.clip(v, 0, h-1), np.clip(u, 0, w-1)].astype(np.float32)
    samples[~available] = np.nan
    use = available.sum(axis=1) >= 32
    profile = np.zeros((len(steps), 3), np.float32)
    profile[use] = np.nanmedian(samples[use], axis=1)
    indices = np.minimum(distances, len(steps)-1)
    residual = np.abs(frame.astype(np.float32) - profile[indices]).max(axis=2)
    # Color boundaries and dark ring strokes produce circular residuals; exclude narrow bands.
    radius = distances / geometry.radius
    margin = np.abs(radius * 10 - np.rint(radius * 10)) > .12
    return ((residual >= 46) & margin & (distances < len(steps)) & use[indices]).astype(np.uint8)


def _span(values):
    return float(np.ptp(np.quantile(values, [.10, .90]))) if len(values) >= 3 else 0.


def _tube(points: np.ndarray, dimensions) -> Tube | None:
    mean = points.mean(axis=0)
    cov = np.cov(points.T)
    eigenvalues, axes = np.linalg.eigh(cov)
    axis = axes[:, 1]
    if axis[0] < 0:
        axis = -axis
    if eigenvalues[0] < .10 or eigenvalues[1] / eigenvalues[0] < 6.25:
        return None
    normal = np.array([-axis[1], axis[0]])
    axial, lateral = (points-mean) @ axis, (points-mean) @ normal
    low, high = np.quantile(axial, [.012, .988])
    length = high-low
    h, w = dimensions
    if length < max(15, .035*max(w, h)) or length > .73*max(w, h):
        return None
    segment = (axial-low)/length
    mid = (segment >= .35) & (segment <= .65)
    body = max(1., _span(lateral[mid]))
    if body > .18*length:
        return None
    centerline = mean + normal*np.median(lateral[mid])
    ends = np.stack((centerline + axis*low, centerline + axis*high))
    if (ends < 3).any() or (ends >= [w-3, h-3]).any():
        return None
    descriptors = []
    for distance in (segment, 1-segment):
        # The vane is usually wider slightly inboard of the nock, not at the extremity itself.
        region = lateral[(distance >= .035) & (distance <= .24)]
        farther = lateral[(distance >= .24) & (distance <= .42)]
        edge = lateral[(distance >= 0) & (distance < .085)]
        descriptors.append([_span(region)/body, _span(farther)/body, _span(edge)/body,
                            len(region)/max(1, int(mid.sum()))])
    return Tube(ends, np.asarray(descriptors, np.float64))


def extract(frame: np.ndarray, family: str, before: np.ndarray | None = None) -> Evidence:
    if frame.ndim != 3 or frame.shape[2] != 3 or min(frame.shape[:2]) < 80:
        raise ValueError("Expected a BGR image of at least 80 pixels per side")
    geometry = fit_geometry(frame, family)
    if before is not None:
        mask = foreground_pair(frame, before)
        if mask is None:
            return Evidence([], geometry, "pair", "Pair registration refused")
        method = "pair"
    else:
        if geometry is None:
            return Evidence([], None, "single", "No reliable color-ring geometry")
        mask = foreground_radial(frame, geometry)
        method = "single"
    mask = cv.morphologyEx(mask, cv.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    count, labels, stats, _ = cv.connectedComponentsWithStats(mask, connectivity=8)
    tubes = []
    for index in np.argsort(-stats[1:, cv.CC_STAT_AREA])[:48] + 1:
        if index >= count or not 20 <= stats[index, cv.CC_STAT_AREA] < .025*mask.size:
            continue
        y, x = np.where(labels == index)
        candidate = _tube(np.column_stack((x, y)), mask.shape)
        if candidate is not None:
            tubes.append(candidate)
    return Evidence(tubes, geometry, method)


def heuristic(tube: Tube) -> tuple[int, float] | None:
    first, second = tube.descriptors[:, 0]
    large, small = max(first, second), min(first, second)
    if large < 1.5 or large/max(1., small) < 1.39 or large-small < .50:
        return None
    # Larger envelope indicates the tail. Report the opposite end.
    return int(first > second), float(1 - small / large)


def proposals(evidence: Evidence, ranker: Ranker | None = None):
    """An ambiguous tube is kept as a tube, with no invented entry point."""
    selected, ambiguous = [], []
    for tube in evidence.tubes:
        result = heuristic(tube) if ranker is None else ranker.choose(tube)
        if result is None:
            ambiguous.append(tube.ends)
        else:
            index, score = result
            selected.append((tube.ends[index], score))
    return selected, ambiguous


@dataclass(frozen=True)
class LogisticOrientation:
    """A small learned orientation baseline over the same OpenCV proposals."""
    weights: np.ndarray
    scale: np.ndarray
    threshold: float = .78

    @staticmethod
    def features(tube: Tube):
        return np.clip(tube.descriptors[1] - tube.descriptors[0], -12, 12)

    def choose(self, tube: Tube) -> tuple[int, float] | None:
        logit = float((self.features(tube) / self.scale) @ self.weights)
        probability_first_entry = float(1 / (1 + np.exp(-np.clip(logit, -40, 40))))
        confidence = max(probability_first_entry, 1-probability_first_entry)
        if confidence < self.threshold:
            return None
        return int(probability_first_entry < .5), confidence

    @classmethod
    def fit(cls, features, first_is_entry):
        x, y = np.asarray(features, np.float64), np.asarray(first_is_entry, np.float64)
        if x.ndim != 2 or x.shape[1] != 4 or len(x) < 12 or len(np.unique(y)) != 2:
            raise ValueError("Need opposite orientations and at least 12 labeled candidates")
        scale = np.maximum(.2, x.std(axis=0))
        x = x / scale
        w = np.zeros(x.shape[1])
        for _ in range(450):
            p = 1/(1+np.exp(-np.clip(x @ w, -40, 40)))
            w -= .25 * (x.T @ (p-y) / len(x) + .035*w)
        return cls(w, scale)
