"""Check provenance, write blinded photo packets, compare independent readings."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from shutil import copyfile

import cv2 as cv
import numpy as np

from benchmark import tube_matches
from detector import Tube
from lab_data import fingerprint, safe_file, write_image, write_json
from r2_corpus import read_corpus

KEYS = ("source_group", "session_id", "board_id", "origin_id")
HERE = Path(__file__).resolve().parent


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def previous_images():
    return {row[key] for filename in ("public-sources.json", "r2-sources.json")
            for row in read_json(HERE.parent / filename) for key in ("sha256", "before_sha256")
            if row.get(key)}


def audit(rows, root):
    identities, partitions, identities_by_hash = set(), {}, {}
    previous = previous_images()
    if not rows:
        raise ValueError("Expected a nonempty catalog of originals")
    for row in rows:
        identity, split = row["id"], row["split"]
        if identity in identities or not re.fullmatch(r"[a-z0-9-]+", identity) or split not in {"train", "dev", "test"}:
            raise ValueError("Duplicate identifier or invalid split")
        identities.add(identity)
        for field in KEYS:
            value = row[field]
            if not value or partitions.setdefault((field, value), split) != split:
                raise ValueError(f"{field} is absent or crosses partitions")
        if not all(row.get(key) for key in ("license", "license_evidence", "source_url", "credit")):
            raise ValueError("Each image needs rights evidence and attribution")
        image_hash = row["sha256"]
        if image_hash in previous or identities_by_hash.setdefault(image_hash, identity) != identity:
            raise ValueError("Repeated image or reuse of an R1/R2 evaluation original")
        if split == "train" and (
            row.get("training_allowed") is not True or row.get("weights_redistribution_reviewed") is not True
        ):
            raise ValueError("Training use and planned weight distribution must be reviewed")
        photo = safe_file(root, row["image"])
        if fingerprint(photo) != image_hash:
            raise ValueError("Original changed")
    return rows


def blind(root, rows, output):
    """Only normalized pixels and identities. No other labels, predictions or EXIF."""
    audit(rows, root)
    output.mkdir(parents=True, exist_ok=False)
    for i, row in enumerate(rows, 1):
        alias = f"review-{i:03}"
        frame = cv.imread(str(safe_file(root, row["image"])))
        if frame is None or list(frame.shape[1::-1]) != row["size"]:
            raise ValueError(f"Cannot decode {row['id']}")
        photo = output / f"{alias}.png"
        write_image(photo, frame)
        write_json(output / f"{alias}.json", {"schema": 1, "id": alias, "size": row["size"],
                                                "original_sha256": row["sha256"], "photo_sha256": fingerprint(photo)})


def compare(first, second):
    for key in ("original_sha256", "photo_sha256", "size"):
        if first[key] != second[key]:
            raise ValueError("Use the same blinded original and coordinate frame")
    a, b = first["annotated_by"].strip(), second["annotated_by"].strip()
    if not a or not b or a.casefold() == b.casefold():
        raise ValueError("A second independent reader must sign the reading")
    left, right = first["arrows"], second["arrows"]
    scale = 704 / max(first["size"])
    tubes = [Tube(np.asarray(mark["axis"]) * scale, np.empty((0,0))) for mark in right]
    matches = tube_matches([np.asarray(mark["axis"]) * scale for mark in left], tubes)
    out = []
    for i, j in matches:
        x, y = left[i], right[j]
        distance = float(np.linalg.norm(np.subtract(x["entry"], y["entry"]))) if (
            x["entry"] is not None and y["entry"] is not None
        ) else None
        out.append({"first": x["id"], "second": y["id"], "first_visibility": x["visibility"],
                    "second_visibility": y["visibility"], "distance_px": distance})
    return {"pairs": out, "first_unmatched": [mark["id"] for i,mark in enumerate(left) if not any(i==x for x,_ in matches)],
            "second_unmatched": [mark["id"] for j,mark in enumerate(right) if not any(j==y for _,y in matches)]}


def prepare(root, rows, labels, output):
    audit(rows, root)
    marks = {value["original_sha256"]: value for value in labels}
    if set(marks) != {row["sha256"] for row in rows} or len(marks) != len(labels):
        raise ValueError("One complete reading per catalogued image is required")
    output.mkdir(parents=True, exist_ok=False)
    frames = []
    for row in rows:
        label = marks[row["sha256"]]
        if row["split"] != "test" or row["size"] != label["size"] or not label["annotated_by"].strip():
            raise ValueError("Expected a signed reading of this held-out original")
        landmarks = label.get("landmarks", [])
        names = set()
        for mark in landmarks:
            name, point = mark["id"], mark["point"]
            if (not name or name in names or len(point) != 2
                or not all(0 <= coord < extent for coord, extent in zip(point, row["size"], strict=True))):
                raise ValueError("Landmarks must be unique and in oriented image pixels")
            names.add(name)
        name = f"{row['id']}{Path(row['image']).suffix.lower()}"
        copyfile(safe_file(root, row["image"]), output / name)
        frames.append(row | {"image": name, "arrows": label["arrows"], "complete": label["complete"],
                             "landmarks": landmarks, "before": None, "before_sha256": None,
                             "source": "public-photo", "geometry": None})
    write_json(output / "manifest.json", {"schema": 1, "annotated_by": "local-readings", "frames": frames})
    read_corpus(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("audit", "blind", "prepare"):
        tool = sub.add_parser(command)
        tool.add_argument("catalog", type=Path)
        tool.add_argument("originals", type=Path)
        if command != "audit":
            tool.add_argument("output", type=Path)
        if command == "prepare":
            tool.add_argument("labels", nargs="+", type=Path)
    tool = sub.add_parser("compare")
    tool.add_argument("first", type=Path)
    tool.add_argument("second", type=Path)
    args = parser.parse_args()
    if args.command == "compare":
        print(json.dumps(compare(read_json(args.first), read_json(args.second)), indent=2))
    else:
        frames = read_json(args.catalog)["frames"]
        if args.command == "audit":
            print(f"{len(audit(frames, args.originals))} validated originals")
        elif args.command == "blind":
            blind(args.originals, frames, args.output)
        else:
            prepare(args.originals, frames, [read_json(path) for path in args.labels], args.output)
