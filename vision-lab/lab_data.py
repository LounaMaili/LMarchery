"""Synthetic scenes and a read-only, allowlisted public-photo manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from shutil import copyfile

import cv2 as cv
import numpy as np


FAMILIES = ("multicolor", "field-yellow-black", "field-yellow-teal")
CONDITIONS = (
    "front", "inclined", "close-rings", "insufficient-crop", "blur",
    "glare", "dim", "small", "optical-proxy", "digital-crop", "shifted-pair",
)
PLANAR_CENTER = np.array([480.0, 480.0])
PLANAR_RADIUS = 375.0
RGB_NAMES_BGR = {
    "yellow": (29, 222, 242), "red": (28, 37, 219), "blue": (212, 125, 29),
    "black": (30, 29, 28), "white": (231, 232, 225), "teal": (100, 100, 24),
}
LAYOUT = {
    "multicolor": ((1.0, "white"), (.8, "black"), (.6, "blue"), (.4, "red"), (.2, "yellow")),
    "field-yellow-black": ((1.0, "black"), (.2, "yellow")),
    "field-yellow-teal": ((1.0, "teal"), (.2, "yellow")),
}
MATERIALS_BGR = {"carbon": (44, 49, 54), "red-coated": (116, 42, 185),
                 "aluminium": (189, 179, 155), "heldout-green-metal": (59, 178, 120),
                 "white": (235, 238, 241)}


def project(matrix, points):
    arr = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    return cv.perspectiveTransform(arr[:, None, :], np.asarray(matrix, dtype=np.float64))[:, 0, :]


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def fingerprint(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_image(path: Path, image) -> None:
    if not cv.imwrite(str(path), image, [cv.IMWRITE_PNG_COMPRESSION, 2]):
        raise OSError(f"Cannot write image {path.name}")


def xy(point):
    return tuple(np.rint(point).astype(int))


def background(rng, family, worn):
    paper = np.full((960, 960, 3), (154, 146, 130), dtype=np.uint8)
    paper = np.clip(paper.astype(np.float32) + rng.normal(0, 2.5, (960, 960, 1)), 0, 255).astype(np.uint8)
    for radius, name in LAYOUT[family]:
        cv.circle(paper, xy(PLANAR_CENTER), round(PLANAR_RADIUS * radius), RGB_NAMES_BGR[name], -1, cv.LINE_AA)
    if family == "multicolor":
        for radius in (.1, .3, .5, .7, .9):
            cv.circle(paper, xy(PLANAR_CENTER), round(PLANAR_RADIUS * radius), (64, 62, 58), 1, cv.LINE_AA)
    else:
        for radius in (.4, .6, .8):
            cv.circle(paper, xy(PLANAR_CENTER), round(PLANAR_RADIUS * radius), (207, 203, 185), 1, cv.LINE_AA)
        cv.circle(paper, xy(PLANAR_CENTER), round(PLANAR_RADIUS * .1), (42, 42, 39), 1, cv.LINE_AA)
    if worn:
        for _ in range(105):
            p = PLANAR_CENTER + rng.uniform(-.6, .6, 2) * PLANAR_RADIUS
            cv.circle(paper, xy(p), int(rng.integers(1, 6)), (98, 93, 84), -1, cv.LINE_AA)
        cv.line(paper, (230, 260), (302, 307), (164, 155, 139), 2, cv.LINE_AA)
    return paper


def paint_shafts(paper, rng, situation, material):
    if situation == "empty":
        return []
    count = 1 if situation == "single" else 3
    anchor = PLANAR_CENTER + rng.uniform(-.26, .26, 2) * PLANAR_RADIUS
    entries = []
    for index in range(count):
        if situation == "cordon":
            theta = .8 + index * 1.9
            point = PLANAR_CENTER + np.array([np.cos(theta), np.sin(theta)]) * (.4 + .004 * (index - 1)) * PLANAR_RADIUS
        elif situation == "grouped":
            point = anchor + np.array([index * 22 - 22, (index % 2) * 14])
        else:
            point = anchor + rng.uniform(-40, 40, 2)
        direction = rng.uniform(-np.pi, np.pi) if situation != "grouped" else .5 + .46 * index
        unit = np.array([np.cos(direction), np.sin(direction)])
        perpendicular = np.array([-unit[1], unit[0]])
        tail = point + unit * rng.uniform(175, 280)
        width = int(rng.integers(5, 10))
        if situation == "grouped":
            cv.line(paper, xy(point + 11), xy(tail + 14), (101, 93, 80), width + 1, cv.LINE_AA)
        cv.line(paper, xy(point), xy(tail), MATERIALS_BGR[material], width, cv.LINE_AA)
        # Vane is deliberately near the nock and distinct from the face entry.
        for side in (-1, 1):
            vane = np.stack((tail - unit * 46, tail - unit * 18 + perpendicular * side * 14, tail - unit * 4))
            cv.fillConvexPoly(paper, np.rint(vane).astype(np.int32), (228, 202, 143), cv.LINE_AA)
        cv.circle(paper, xy(tail), width // 2, (118, 190, 235), -1, cv.LINE_AA)
        entries.append((point, tail))
    return entries


def homography(condition):
    width, height = (704, 568)
    if condition == "small":
        width, height = (352, 284)
    normal = np.array([[.13, .04], [.87, .04], [.87, .96], [.13, .96]], dtype=np.float32)
    if condition == "inclined":
        normal = np.array([[.29, .08], [.81, .17], [.90, .87], [.12, .89]], dtype=np.float32)
    elif condition == "close-rings":
        normal = (normal - .5) * 1.75 + .5
    elif condition in {"optical-proxy", "digital-crop"}:
        normal = (normal - .5) * 1.35 + .5
    elif condition == "insufficient-crop":
        normal = (normal - .5) * 1.8 + [.12, .44]
    plane = np.float32([[0, 0], [959, 0], [959, 959], [0, 959]])
    return cv.getPerspectiveTransform(plane, (normal * [width, height]).astype(np.float32)), (width, height)


def render(paper, matrix, size, condition):
    image = cv.warpPerspective(paper, matrix, size, flags=cv.INTER_AREA, borderMode=cv.BORDER_CONSTANT,
                               borderValue=(152, 144, 128))
    if condition == "blur":
        image = cv.GaussianBlur(image, (19, 19), 4.0)
    elif condition == "dim":
        image = np.rint(image * .51 + 8).astype(np.uint8)
    elif condition == "glare":
        h, w = image.shape[:2]
        y, x = np.ogrid[:h, :w]
        alpha = .98 * np.exp(-((x/w - .51)**2 + (y/h - .44)**2) / .11)
        image = np.rint(image * (1 - alpha[:, :, None]) + 255 * alpha[:, :, None]).astype(np.uint8)
    elif condition == "digital-crop":
        # The camera acquired the normal frame; interpolation cannot create optical samples.
        low_matrix, (w, h) = homography("front")
        low = cv.warpPerspective(paper, low_matrix, (w, h), flags=cv.INTER_AREA)
        image = cv.warpPerspective(low, matrix @ np.linalg.inv(low_matrix), size, flags=cv.INTER_LINEAR)
    return image


def generate(root: Path):
    root.mkdir(parents=True, exist_ok=False)
    (root / "frames").mkdir()
    rows = []
    ordinal = 0
    for split, group_count, variants in (
        ("train", 12, ("front", "inclined", "dim")),
        ("dev", 6, ("front", "close-rings", "blur")),
        ("test", 12, CONDITIONS),
    ):
        for group in range(group_count):
            ordinal += 1
            family = FAMILIES[group % 3]
            scenario = ("single", "grouped", "cordon", "empty")[group // 3 % 4]
            material = ("carbon", "red-coated", "aluminium")[group % 3]
            if split == "test" and (group // 3 + group % 3) % 3 == 1:
                material = "heldout-green-metal"
            rng = np.random.default_rng(61200 + ordinal)
            paper = background(rng, family, worn=group % 2 == 1)
            marked = paper.copy()
            entries = paint_shafts(marked, rng, scenario, material)
            for condition in variants:
                matrix, (width, height) = homography(condition)
                bare = render(paper, matrix, (width, height), condition)
                picture = render(marked, matrix, (width, height), condition)
                if condition == "shifted-pair":
                    bare = cv.warpAffine(bare, np.float32([[1, 0, 4], [0, 1, -3]]), (width, height),
                                         borderMode=cv.BORDER_REFLECT_101)
                key = f"scene-{ordinal:02d}-{condition}"
                rel, before_rel = f"frames/{key}.png", f"frames/{key}-before.png"
                write_image(root / rel, picture)
                write_image(root / before_rel, bare)
                marks = []
                for a, b in entries:
                    entry, tail = project(matrix, [a, b])
                    visible_start = bool(0 <= entry[0] < width and 0 <= entry[1] < height)
                    visible_tail = bool(0 <= tail[0] < width and 0 <= tail[1] < height)
                    radius = float(np.linalg.norm(a - PLANAR_CENTER) / PLANAR_RADIUS)
                    boundaries = tuple(k/10 for k in range(1, 11)) if family == "multicolor" else (.1, .2, .4, .6, .8, 1.)
                    marks.append({"entry": entry.tolist() if visible_start else None,
                                  "tail": tail.tolist() if visible_tail else None,
                                  "visibility": "visible" if visible_start else "out_of_frame",
                                  "uncertainty_px": 0.0 if visible_start else None,
                                  "near_boundary": min(abs(radius - boundary) for boundary in boundaries) <= .015})
                rows.append({
                    "id": key, "source_group": f"synthetic-board-{ordinal:02d}", "split": split,
                    "source": "synthetic", "condition": condition, "scenario": scenario, "family": family,
                    "material": material, "size": [width, height], "image": rel, "before": before_rel,
                    "sha256": fingerprint(root / rel), "before_sha256": fingerprint(root / before_rel),
                    "geometry": {"plane_to_image": matrix.tolist(), "center_plane": PLANAR_CENTER.tolist(),
                                 "radius_plane": PLANAR_RADIUS, "diameter_mm": 400 if family == "multicolor" else 800},
                    "arrows": marks,
                })
    write_json(root / "manifest.json", {"schema": 1, "annotated_by": "synthetic-renderer-v1", "frames": rows})


def prepare_real(originals: Path, label_file: Path, root: Path):
    registry = json.loads(Path(__file__).with_name("public-sources.json").read_text(encoding="utf-8"))
    root.mkdir(parents=True, exist_ok=False)
    (root / "frames").mkdir()
    labels = json.loads(label_file.read_text(encoding="utf-8"))
    if set(labels) != {asset["filename"] for asset in registry}:
        raise ValueError("Public annotations must cover the pinned catalog exactly")
    frames = []
    for asset in registry:
        name = asset["filename"]
        assert re.fullmatch(r"[a-z0-9-]+\.jpg", name)
        source = originals / name
        if not source.is_file() or source.is_symlink() or fingerprint(source) != asset["sha256"]:
            raise ValueError(f"Local public original absent or hash mismatch: {name}")
        relative = f"frames/{name}"
        copyfile(source, root / relative)
        picture = cv.imread(str(root / relative))
        if picture is None:
            raise ValueError(f"Image not decodable: {name}")
        height, width = picture.shape[:2]
        marks = []
        for mark in labels[name]:
            entry = mark["entry_relative"]
            tail = mark.get("tail_relative")
            marks.append({"entry": [entry[0]*width, entry[1]*height] if entry is not None else None,
                          "tail": [tail[0]*width, tail[1]*height] if tail is not None else None,
                          "visibility": mark["visibility"],
                          "uncertainty_px": max(width, height) * mark["uncertainty_fraction"] if entry is not None else None,
                          "near_boundary": mark.get("near_boundary")})
        frames.append({
            "id": Path(name).stem, "source_group": asset["group"], "split": "test",
            "source": "public-photo", "condition": asset["condition"], "family": asset["family"],
            "scenario": asset["scenario"], "material": "unknown", "size": [width, height], "image": relative,
            "before": None, "sha256": asset["sha256"], "before_sha256": None,
            "geometry": None, "arrows": marks, "attribution": asset["credit"], "source_url": asset["page"],
        })
    write_json(root / "manifest.json", {"schema": 1, "annotated_by": "independent-photo-review-20261120", "frames": frames})


def safe_file(root: Path, name: str) -> Path:
    candidate = PurePosixPath(name)
    if not name or candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError("Dataset path escapes its root")
    path = root.joinpath(*candidate.parts)
    if not path.resolve().is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("Dataset path does not identify a contained file")
    return path


def read_manifest(root: Path):
    obj = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if obj["schema"] != 1 or not obj["annotated_by"]:
        raise ValueError("Unknown annotation contract")
    groups, identities, digests = {}, set(), {}
    for row in obj["frames"]:
        name, group, split = row["id"], row["source_group"], row["split"]
        if name in identities or not re.fullmatch(r"[a-z0-9-]+", name):
            raise ValueError("Duplicate or invalid image identifier")
        identities.add(name)
        if not group or split not in {"train", "dev", "test"} or groups.setdefault(group, split) != split:
            raise ValueError("A board/source group crosses splits")
        if row["source"] != "synthetic" and split != "test":
            raise ValueError("Public photos are evaluation only in this pilot")
        if (row["before"] is None) != (row["before_sha256"] is None):
            raise ValueError("A before frame and its hash must be present together")
        for path_key, sha_key in (("image", "sha256"), ("before", "before_sha256")):
            rel = row[path_key]
            if rel is not None:
                actual = fingerprint(safe_file(root, rel))
                if actual != row[sha_key] or digests.setdefault(actual, split) != split:
                    raise ValueError("Image hash mismatch or duplicate crosses splits")
        width, height = row["size"]
        if width <= 0 or height <= 0 or len(row["arrows"]) > 12:
            raise ValueError("Frame size or arrow count is outside pilot bounds")
        geometry = row["geometry"]
        if geometry is not None:
            matrix = np.asarray(geometry["plane_to_image"], dtype=float)
            center = np.asarray(geometry["center_plane"], dtype=float)
            sizes = np.asarray([geometry["radius_plane"], geometry["diameter_mm"]], dtype=float)
            if (matrix.shape != (3, 3) or not np.isfinite(matrix).all() or abs(np.linalg.det(matrix)) < 1e-10
                or center.shape != (2,) or not np.isfinite(center).all()
                or not np.isfinite(sizes).all() or (sizes <= 0).any()):
                raise ValueError("Invalid planar reference")
        for mark in row["arrows"]:
            visibility, entry, tail = mark["visibility"], mark["entry"], mark["tail"]
            if visibility not in {"visible", "ambiguous", "occluded", "out_of_frame"}:
                raise ValueError("Invalid visibility")
            if (visibility == "visible") != (entry is not None):
                raise ValueError("Only a visible, independently labeled entry may carry coordinates")
            for point in (entry, tail):
                if point is not None and not (len(point) == 2 and 0 <= point[0] < width and 0 <= point[1] < height):
                    raise ValueError("Annotation is outside the oriented image")
            uncertainty = mark["uncertainty_px"]
            if entry is not None and (uncertainty is None or not np.isfinite(uncertainty) or uncertainty < 0):
                raise ValueError("Visible entries require a finite annotation uncertainty")
    return obj["frames"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--public-originals", type=Path)
    parser.add_argument("--public-labels", type=Path)
    args = parser.parse_args()
    cv.setNumThreads(1)
    if bool(args.public_originals) != bool(args.public_labels):
        parser.error("Public evaluation requires the pinned originals and separate reviewed labels")
    if args.public_originals:
        prepare_real(args.public_originals, args.public_labels, args.output)
    else:
        generate(args.output)
    print(f"{len(read_manifest(args.output))} validated frames")


if __name__ == "__main__":
    main()
