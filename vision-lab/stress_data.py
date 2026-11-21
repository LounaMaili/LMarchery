"""Generate a separate, paired holdout with white shafts and masked contacts."""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import FAMILIES, PLANAR_CENTER, PLANAR_RADIUS, background, fingerprint, homography, paint_shafts
from lab_data import project, read_manifest, render, write_image, write_json, xy


def generate_stress(root: Path):
    root.mkdir(parents=True, exist_ok=False)
    (root/"frames").mkdir()
    matrix, size = homography("front")
    rows = []
    for i in range(6):
        family = FAMILIES[i % len(FAMILIES)]
        rng = np.random.default_rng(88000+i)
        bare = background(rng, family, worn=i >= 3)
        visible = bare.copy()
        [(entry, tail)] = paint_shafts(visible, rng, "single", "white")
        unknown = visible.copy()
        cv.ellipse(unknown, xy(entry), (30, 16), 28, 0, 360, (86, 101, 118), -1, cv.LINE_AA)
        before = f"frames/white-{i+1:02d}-before.png"
        write_image(root/before, render(bare, matrix, size, "front"))
        a, b = project(matrix, [entry, tail])
        radius = float(np.linalg.norm(entry-PLANAR_CENTER)/PLANAR_RADIUS)
        boundaries = tuple(j/10 for j in range(1, 11)) if family == "multicolor" else (.1, .2, .4, .6, .8, 1.)
        for condition, frame, known in (("white-visible", visible, True), ("white-masked", unknown, False)):
            name = f"stress-{i+1:02d}-{condition}"
            image = f"frames/{name}.png"
            write_image(root/image, render(frame, matrix, size, "front"))
            rows.append({
                "id":name, "source_group":f"synthetic-stress-{i+1:02d}", "split":"test", "source":"synthetic",
                "condition":condition, "scenario":"single", "family":family, "material":"white",
                "size":list(size), "image":image, "before":before, "sha256":fingerprint(root/image),
                "before_sha256":fingerprint(root/before),
                "geometry":{"plane_to_image":matrix.tolist(), "center_plane":PLANAR_CENTER.tolist(),
                            "radius_plane":PLANAR_RADIUS, "diameter_mm":400 if family=="multicolor" else 800},
                "arrows":[{"entry":a.tolist() if known else None, "tail":b.tolist(),
                           "visibility":"visible" if known else "occluded", "uncertainty_px":0. if known else None,
                           "near_boundary":min(abs(radius-boundary) for boundary in boundaries)<=.015 if known else None}],
            })
    write_json(root/"manifest.json", {"schema":1, "annotated_by":"white-stress-renderer-v1", "frames":rows})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    cv.setNumThreads(1)
    generate_stress(args.output)
    print(f"{len(read_manifest(args.output))} validated holdout frames")
