"""Synthetic development corpus with explicit visibility and mask references."""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import FAMILIES, MATERIALS_BGR, PLANAR_CENTER, PLANAR_RADIUS
from lab_data import background, fingerprint, project, write_image, write_json, xy

SIZE = (384, 320)
SCENARIOS = ("empty", "single", "separate", "grouped", "masked", "boundary")
SPLITS = (("train", 120, 3), ("dev", 24, 2), ("test", 30, 2))


def _plane(rng):
    width, height = SIZE
    corners = np.float32([[.06, .02], [.94, .02], [.94, .98], [.06, .98]])
    corners += rng.uniform(-.055, .055, (4, 2)).astype(np.float32)
    standard = np.float32([[0, 0], [959, 0], [959, 959], [0, 959]])
    return cv.getPerspectiveTransform(standard, (corners * [width, height]).astype(np.float32))


def _shafts(rng, scenario, colors, family):
    if scenario == "empty":
        return []
    count = 1 if scenario in {"single", "masked"} else 3
    angle = rng.uniform(-np.pi, np.pi)
    shared = PLANAR_CENTER + rng.uniform(-105, 105, 2)
    marks = []
    for i in range(count):
        if scenario == "grouped":
            entry = shared + rng.uniform(-21, 21, 2)
            heading = angle + (i-1)*.12
        elif scenario == "boundary":
            heading = angle + 2.08*i
            entry = PLANAR_CENTER + np.array([np.cos(heading), np.sin(heading)]) * PLANAR_RADIUS * .4
        elif count == 3:
            heading = angle + 2.08*i
            entry = PLANAR_CENTER + np.array([np.cos(heading), np.sin(heading)]) * rng.uniform(90, 150)
        else:
            heading = angle
            entry = shared
        vec = np.array([np.cos(heading), np.sin(heading)])
        tail = entry + vec * rng.uniform(150, 310)
        width = int(rng.integers(6, 15))
        color = colors[int(rng.integers(len(colors)))]
        radial = float(np.linalg.norm(entry - PLANAR_CENTER) / PLANAR_RADIUS)
        rings = np.arange(1, 11)*.1 if family == "multicolor" else [.1, .2, .4, .6, .8, 1.]
        marks.append((entry, tail, width, color, bool(np.min(np.abs(np.asarray(rings)-radial)) <= .012)))
    return marks


def _photo(paper, shafts, matrix, masked, rng):
    raw = paper.copy()
    masks = []
    for entry, tail, width, color, _ in shafts:
        unit = (tail - entry) / np.linalg.norm(tail - entry)
        normal = np.array([-unit[1], unit[0]])
        shadow = rng.uniform(4, 13, 2)
        cv.line(raw, xy(entry + shadow), xy(tail + shadow), (81, 78, 70), width + 1, cv.LINE_AA)
        cv.line(raw, xy(entry), xy(tail), color, width, cv.LINE_AA)
        vane_color = tuple(int(value) for value in rng.integers(50, 245, 3))
        for side in (-1, 1):
            polygon = np.stack([tail - unit*46, tail - unit*12 + normal*side*14, tail])
            cv.fillConvexPoly(raw, np.rint(polygon).astype(np.int32), vane_color, cv.LINE_AA)
        mask = np.zeros(raw.shape[:2], np.uint8)
        cv.line(mask, xy(entry), xy(tail), 255, width)
        if masked:
            cover = np.zeros_like(mask)
            cv.circle(cover, xy(entry), 42, 255, -1)
            raw[cover > 0] = (104, 111, 128)
            mask[cover > 0] = 0
        masks.append(cv.warpPerspective(mask, matrix, SIZE, flags=cv.INTER_NEAREST))
    photo = cv.warpPerspective(raw, matrix, SIZE, flags=cv.INTER_AREA, borderValue=(105, 111, 117))
    before = cv.warpPerspective(paper, matrix, SIZE, flags=cv.INTER_AREA, borderValue=(105, 111, 117))
    factor, offset = rng.uniform(.57, 1.26), rng.uniform(-9, 18)
    light = np.linspace(rng.uniform(.68, 1.1), rng.uniform(.7, 1.12), SIZE[0])[None, :, None]
    sigma = rng.uniform(0, 1.1)
    def finish(frame):
        value = np.clip(frame * light * factor + offset, 0, 255).astype(np.uint8)
        return cv.GaussianBlur(value, (5, 5), sigma) if sigma > .32 else value
    return finish(photo), finish(before), masks


def generate(root, splits=SPLITS):
    root.mkdir(parents=True, exist_ok=False)
    (root / "frames").mkdir()
    rows = []
    colors = [MATERIALS_BGR[name] for name in ("carbon", "red-coated", "aluminium", "white")]
    ordinal = 0
    for split, groups, variants in splits:
        for group_index in range(groups):
            ordinal += 1
            group = f"r3-board-{split}-{group_index:03}"
            family = FAMILIES[(group_index // len(SCENARIOS)) % len(FAMILIES)]
            scenario = SCENARIOS[group_index % len(SCENARIOS)]
            rng = np.random.default_rng(811400 + ordinal)
            paper = background(rng, family, worn=ordinal % 3 != 0)
            shafts = _shafts(rng, scenario, colors + ([MATERIALS_BGR["heldout-green-metal"]] if split == "test" else []), family)
            for variant in range(variants):
                name = f"{group}-{variant}"
                matrix = _plane(rng)
                photo, before, masks = _photo(paper, shafts, matrix, scenario == "masked", rng)
                image = f"frames/{name}.png"
                before_name = f"frames/{name}-before.png"
                write_image(root / image, photo)
                write_image(root / before_name, before)
                mask_file = f"frames/{name}-mask.png"
                # Green = shaft pixels of visible contacts. Red = other shaft pixels.
                layers = np.zeros_like(photo)
                marks = []
                for (start, end, _, _, near), mask in zip(shafts, masks, strict=True):
                    entry, tail = project(matrix, [start, end])
                    height, width = photo.shape[:2]
                    bounds = (width-1, height-1)
                    visibility = "occluded" if scenario == "masked" else (
                        "visible" if np.all(entry >= 0) and np.all(entry <= bounds) else "out_of_frame"
                    )
                    axis_start = project(matrix, [start + (end-start)*42/np.linalg.norm(end-start)])[0] if (
                        visibility == "occluded"
                    ) else entry
                    ok, first, last = cv.clipLine((0, 0, width, height), xy(axis_start), xy(tail))
                    axis = [list(first), list(last)] if ok and np.linalg.norm(np.subtract(first,last)) >= 4 else None
                    marks.append({"entry": entry.tolist() if visibility == "visible" else None,
                                  "tail": tail.tolist() if np.all(tail >= 0) and np.all(tail <= bounds) else None,
                                  "axis": axis, "group": group if scenario == "grouped" else None,
                                  "state": "new", "visibility": visibility,
                                  "near_boundary": near if visibility == "visible" else None,
                                  "uncertainty_px": 0. if visibility == "visible" else None})
                    channel = 1 if visibility == "visible" else 2
                    layers[:, :, channel] = np.maximum(layers[:, :, channel], mask)
                write_image(root / mask_file, layers)
                rows.append({"id": name, "source": "synthetic", "source_group": group, "split": split,
                             "family": family, "scenario": scenario, "arrows": marks, "complete": True,
                             "image": image, "sha256": fingerprint(root / image),
                             "before": before_name, "before_sha256": fingerprint(root / before_name),
                             "mask": mask_file, "mask_sha256": fingerprint(root / mask_file), "size": list(SIZE),
                             "geometry": {"plane_to_image": matrix.tolist(), "center_plane": PLANAR_CENTER.tolist(),
                                          "radius_plane": PLANAR_RADIUS,
                                          "diameter_mm": 400 if family == "multicolor" else 800}})
    write_json(root / "manifest.json", {"schema": 1, "annotated_by": "r3-synthetic-renderer", "frames": rows})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    cv.setNumThreads(1)
    generate(args.output)
