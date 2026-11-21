"""Generate the preregistered synthetic groups; one original per board."""
import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import FAMILIES, MATERIALS_BGR, background, fingerprint, project, write_image, write_json, xy
from r3.synth import _photo, _plane, _shafts
from r4.wear import SCENES, mark_wear

PARTITIONS = {"train": (6220000, 48), "dev": (6230000, 24), "test": (6240000, 32)}


def render(split, index, root):
    seed = PARTITIONS[split][0] + index
    rng = np.random.default_rng(seed)
    scene = SCENES[index % len(SCENES)]
    family = FAMILIES[(index // len(SCENES)) % len(FAMILIES)]
    paper = background(rng, family, worn=False)
    mark_wear(paper, rng, scene)
    layout = "empty" if scene.endswith("-only") else "grouped" if scene == "worn-grouped" else "single"
    colors = ["white"] if scene == "worn-bright" else ["carbon", "aluminium", "red-coated"]
    shafts = _shafts(rng, layout, [MATERIALS_BGR[c] for c in colors], family)
    matrix = _plane(rng)
    masked = scene == "worn-masked"
    photo, _, masks = _photo(paper, shafts, matrix, masked, rng)
    h, w = photo.shape[:2]
    group = f"r6-board-{seed}"
    instances = np.zeros((h, w), np.uint8)
    for number, mask in enumerate(masks, 1):
        instances[mask > 0] = number
    marks = []
    layers = np.zeros_like(photo)
    for number, (start, tail, _, _, boundary) in enumerate(shafts, 1):
        entry, end = project(matrix, [start, tail])
        x, y = xy(entry)
        seen = not masked and instances[y, x] == number
        pixels = instances == number
        if not pixels.any():
            continue
        layers[pixels, 1 if seen else 2] = 255
        # One contiguous visible axis fragment per instance for the historic presence metric.
        samples = np.linspace(entry, end, max(2, round(np.linalg.norm(end-entry))+1))
        coords = np.rint(samples).astype(int)
        inside = (coords >= 0).all(axis=1) & (coords < [w, h]).all(axis=1)
        active = np.zeros(len(coords), bool)
        active[inside] = pixels[coords[inside, 1], coords[inside, 0]]
        runs = np.flatnonzero(np.diff(np.r_[False, active, False])).reshape(-1, 2)
        axis = None
        if len(runs):
            left, right = max(runs, key=lambda run: run[1]-run[0])
            if right-left >= 4:
                axis = coords[[left, right-1]].tolist()
        marks.append(dict(id=f"shaft-{number}", instance=number, entry=entry.tolist() if seen else None,
                          tail=end.tolist() if np.all((end >= 0) & (end < [w, h])) else None,
                          axis=axis, visibility="visible" if seen else "occluded", state="new",
                          uncertainty_px=0. if seen else None, near_boundary=bool(boundary) if seen else None,
                          group=group if layout == "grouped" else None))
    row = dict(id=group, source_group=group, split=split, source="synthetic", scenario=scene,
               family=family, complete=True, arrows=marks, before=None, before_sha256=None,
               geometry=None, size=[w, h])
    for key, image in (("image", photo), ("mask", layers), ("instances", instances)):
        name = f"{group}-{key}.png"
        write_image(root / name, image)
        row[key] = name
        row["sha256" if key == "image" else key+"_sha256"] = fingerprint(root / name)
    return row


def generate(root):
    cv.setNumThreads(1)
    root.mkdir(parents=True, exist_ok=False)
    rows = [render(split, i, root) for split, (_, count) in PARTITIONS.items() for i in range(count)]
    write_json(root / "manifest.json", dict(schema=1, annotated_by="r6-renderer", frames=rows))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output)
