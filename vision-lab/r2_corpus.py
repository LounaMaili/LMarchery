"""Validate local R2 reference marks and compare a blind second reading."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import read_manifest, safe_file


def read_corpus(root):
    document = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    rows = read_manifest(root)
    if not rows or any(row["split"] != "test" for row in rows):
        raise ValueError("R2 pilot references are exclusively held out")
    for row in rows:
        if row["geometry"] is not None and not row.get("geometry_evidence"):
            raise ValueError("Physical errors require an independent metric reference")
        if not row.get("source_url") or not row.get("license"):
            raise ValueError("Each sample needs a source and reviewed rights")
        for key in ("image", "before"):
            if row[key]:
                file = safe_file(root, row[key])
                frame = cv.imread(str(file))
                if frame is None or tuple(frame.shape[1::-1]) != tuple(row["size"]):
                    raise ValueError(f"Dimensions or decoding failed for {row['id']}")
        if row["before"] and (row["before_sha256"] == row["sha256"] or not row.get("pair_evidence")):
            raise ValueError("Each temporal pair needs distinct frames and an acquisition description")
        identities = set()
        for mark in row["arrows"]:
            identity = mark["id"]
            if not identity or identity in identities:
                raise ValueError("Missing or duplicate arrow identifier")
            identities.add(identity)
            if mark["state"] not in {"new", "existing", "standalone"} or (
                bool(row["before"]) != (mark["state"] in {"new", "existing"})
            ):
                raise ValueError("Arrow state contradicts acquisition mode")
            if mark["opposite_kind"] not in {"visible", "clipped", "unavailable"} or (
                (mark["tail"] is None) != (mark["opposite_kind"] == "unavailable")
            ):
                raise ValueError("Shaft opposite endpoint state is inconsistent")
            if mark["visibility"] != "visible" and mark["uncertainty_px"] is not None:
                raise ValueError("Unknown entries must keep uncertainty and location unset")
            if mark["near_boundary"] not in (True, False, None) or (
                mark["visibility"] != "visible" and mark["near_boundary"] is not None
            ):
                raise ValueError("An unseen contact cannot be placed near a boundary")
            if mark["axis"] is not None:
                axis = np.asarray(mark["axis"], dtype=float)
                if (axis.shape != (2, 2) or not np.isfinite(axis).all()
                    or not np.all(axis >= 0) or not np.all(axis < row["size"])
                    or np.linalg.norm(axis[0] - axis[1]) < 4):
                    raise ValueError("Visible shaft segment is invalid")
            if mark["entry"] is not None and mark["tail"] == mark["entry"]:
                raise ValueError("Coincident entry and opposite endpoint")
    return document["annotated_by"], rows


def agreement(primary, second):
    """Compare two unchanged, independent readings of the same actual images."""
    first_reader, first_rows = primary
    second_reader, second_rows = second
    if first_reader.strip().casefold() == second_reader.strip().casefold():
        raise ValueError("A second reading must identify a different annotator")
    known = {row["id"]: row for row in first_rows}
    differences = []
    for row in second_rows:
        first = known.get(row["id"])
        if first is None or any(row[key] != first[key] for key in ("sha256", "before_sha256", "size")):
            raise ValueError("Blind reading must use the same oriented original and pair")
        previous = {mark["id"]: mark for mark in first["arrows"]}
        for mark in row["arrows"]:
            other = previous.pop(mark["id"], None)
            entry = mark["entry"]
            distance = None
            if other is not None and entry is not None and other["entry"] is not None:
                distance = round(float(np.linalg.norm(np.subtract(entry, other["entry"]))), 3)
            differences.append({"frame": row["id"], "arrow": mark["id"],
                                "first_visibility": other["visibility"] if other else None,
                                "second_visibility": mark["visibility"], "distance_px": distance})
        differences.extend({"frame": row["id"], "arrow": identity,
                            "first_visibility": mark["visibility"], "second_visibility": None,
                            "distance_px": None} for identity, mark in previous.items())
    return differences


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("primary", type=Path)
    parser.add_argument("second", type=Path)
    args = parser.parse_args()
    print(json.dumps(agreement(read_corpus(args.primary), read_corpus(args.second)), indent=2))
