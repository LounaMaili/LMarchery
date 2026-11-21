"""Generate independent stress references for worn paper and background clutter."""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import (
    FAMILIES, MATERIALS_BGR, PLANAR_CENTER, PLANAR_RADIUS, background,
    fingerprint, project, write_image, write_json, xy,
)
from r3.synth import _photo, _plane, _shafts

SCENES = ("clean-single", "holes-only", "tear-only", "clutter-only",
          "worn-single", "worn-grouped", "worn-masked", "worn-bright")


def mark_wear(paper, rng, scenario):
    nuisances = []
    if scenario == "clean-single":
        return nuisances
    for _ in range(310):
        angle, radius = rng.uniform(0, 2*np.pi), rng.uniform(0, 350)
        point = PLANAR_CENTER + np.array([np.cos(angle),np.sin(angle)]) * radius
        size = int(rng.integers(2, 9))
        cv.circle(paper, xy(point+[2, 1]), size+1, (178, 186, 194), -1, cv.LINE_AA)
        cv.circle(paper, xy(point), size, (37, 34, 29), -1, cv.LINE_AA)
        nuisances.append(("old-hole", point, size))
    if scenario != "holes-only":
        for _ in range(9):
            point = PLANAR_CENTER + rng.uniform(-190, 190, 2)
            corners = np.rint(point + rng.uniform(-22, 28, (7, 2))).astype(np.int32)
            cv.fillPoly(paper, [corners], (127, 131, 143), cv.LINE_AA)
            cv.polylines(paper, [corners], True, (211, 218, 225), 1, cv.LINE_AA)
            nuisances.append(("tear", point, 24))
    if scenario in {"clutter-only", "worn-masked"}:
        for y in (95, 265, 570, 735):
            start = np.array([25, y]) + rng.uniform(-9, 9, 2)
            end = start + np.array([rng.uniform(70, 160), rng.uniform(-85, 75)])
            cv.line(paper, xy(start), xy(end), (56, 52, 48), 6, cv.LINE_AA)
            nuisances.extend(("lookalike", point, 10) for point in (start,end))
    return nuisances


def generate(root, groups_per_scene=5):
    root.mkdir(parents=True, exist_ok=False)
    (root / "frames").mkdir()
    colors = [MATERIALS_BGR[key] for key in ("carbon", "aluminium", "red-coated")]
    rows = []
    for i in range(groups_per_scene * len(SCENES)):
        scenario = SCENES[i % len(SCENES)]
        family = FAMILIES[(i // len(SCENES)) % len(FAMILIES)]
        group = f"r4-wear-{i:03}"
        rng = np.random.default_rng(495000+i)
        paper = background(rng, family, worn=False)
        nuisance = mark_wear(paper, rng, scenario)
        arrows = "grouped" if scenario == "worn-grouped" else (
            "empty" if scenario.endswith("-only") else "single"
        )
        materials = [MATERIALS_BGR["white"]] if scenario == "worn-bright" else colors
        shafts = _shafts(rng, arrows, materials, family)
        matrix = _plane(rng)
        mask_contact = scenario == "worn-masked"
        picture, bare, _ = _photo(paper, shafts, matrix, mask_contact, rng)
        height, width = picture.shape[:2]
        marks, artefacts = [], []
        for start, tail, _, _, boundary in shafts:
            delta = tail-start
            visible_start = start + delta*42/np.linalg.norm(delta) if mask_contact else start
            point, end = project(matrix, [visible_start, tail])
            in_frame, first, last = cv.clipLine((0, 0, width, height), xy(point), xy(end))
            if not in_frame:
                continue
            marks.append({"id": f"arrow-{len(marks)+1}", "axis":[list(first),list(last)], "tail":list(last),
                          "entry":None if mask_contact else point.tolist(),
                          "visibility":"occluded" if mask_contact else "visible",
                          "near_boundary":None if mask_contact else boundary, "uncertainty_px":None if mask_contact else 0.,
                          "group":group if arrows=="grouped" else None, "state":"new"})
        for kind, site, radius in nuisance:
            location, border = project(matrix, [site, site+[radius,0]])
            if np.all(location >= 0) and np.all(location < [width, height]):
                artefacts.append({"kind":kind,"point":location.tolist(),"radius_px":float(np.linalg.norm(border-location))})
        image, before = f"frames/{group}.png", f"frames/{group}-before.png"
        write_image(root / image, picture)
        write_image(root / before, bare)
        rows.append({"id":group, "source":"synthetic", "source_group":group, "split":"test",
                     "scenario":scenario,"family":family,"image":image,"before":before,"size":[width,height],
                     "sha256":fingerprint(root/image),"before_sha256":fingerprint(root/before),
                     "complete":True,"arrows":marks,"nuisances":artefacts,
                     "geometry":{"plane_to_image":matrix.tolist(),"center_plane":PLANAR_CENTER.tolist(),
                                 "radius_plane":PLANAR_RADIUS,"diameter_mm":400 if family=="multicolor" else 800}})
    write_json(root / "manifest.json", {"schema":1,"annotated_by":"r4-wear-renderer","frames":rows})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    options = parser.parse_args()
    cv.setNumThreads(1)
    generate(options.output)
