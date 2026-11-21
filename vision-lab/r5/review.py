"""Prepare independently readable packets and keep both versions of every finding."""
from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
from shutil import copyfile
from tempfile import TemporaryDirectory

import cv2 as cv
import numpy as np

from lab_data import fingerprint, read_manifest, safe_file, write_image, write_json
from r3.review import read_json
from r4.corpus import PREFIXES

ROOT = Path(__file__).resolve().parent.parent
PUBLIC_CATALOGS = ("public-sources.json", "r2-sources.json", "r3/real-sources.json", "r4/public-sources.json")
IDENTITY = ("schema", "id", "size", "original_sha256", "photo_sha256")
VISIBILITIES = {"visible", "ambiguous", "occluded", "out_of_frame"}
STARTER = ("review-002", "review-009", "review-010", "review-012", "review-014", "review-016", "review-017")


def reserved_hashes():
    fingerprints = set()
    for name in PUBLIC_CATALOGS:
        catalog = read_json(ROOT / name)
        rows = catalog["frames"] if isinstance(catalog, dict) else catalog
        fingerprints.update(row[key] for row in rows for key in ("sha256", "before_sha256") if row.get(key))
    return fingerprints


def packet_identity(blind, alias):
    if not isinstance(alias, str) or not re.fullmatch(r"review-[0-9]+", alias):
        raise ValueError("Invalid photo alias")
    metadata = safe_file(blind, f"{alias}.json")
    identity = read_json(metadata)
    size = identity.get("size")
    if (set(identity) != set(IDENTITY) or identity["schema"] != 1 or identity["id"] != alias
            or not isinstance(size, list) or len(size) != 2
            or any(type(side) is not int or side <= 0 for side in size)
            or any(not isinstance(identity[key], str) or not re.fullmatch(r"[0-9a-f]{64}", identity[key])
                   for key in ("original_sha256", "photo_sha256"))):
        raise ValueError("Invalid blind packet identity")
    image = safe_file(blind, f"{alias}.png")
    if fingerprint(image) != identity["photo_sha256"]:
        raise ValueError("The pixels of the original reader packet changed")
    return identity, metadata, image


def build_packet(datasets, output):
    """Use the oriented pixels at full resolution, with only opaque identities for readers."""
    manifests = {
        edition: (read_json(source / "manifest.json")["annotated_by"], fingerprint(source / "manifest.json"))
        for edition, source in datasets.items()
    }
    rows = [(edition, source, row) for edition, source in datasets.items() for row in read_manifest(source)]
    known = reserved_hashes()
    hashes = {row["sha256"] for _, _, row in rows}
    if not rows or any(row["split"] != "test" or row["source"] != "public-photo" for _, _, row in rows):
        raise ValueError("Only published, held-out photos may enter the R2–R4 packet")
    if len(hashes) != len(rows) or not hashes <= known:
        raise ValueError("Duplicate or unregistered held-out original")
    if any(row["before_sha256"] not in hashes for _, _, row in rows if row["before_sha256"]):
        raise ValueError("A before photo is missing from the packet")
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "batch"
        blind, control = staging / "lecture-aveugle", staging / "reference"
        blind.mkdir(parents=True, mode=0o700)
        control.mkdir(mode=0o700)
        index = []
        for n, (edition, source, row) in enumerate(rows, 1):
            alias = f"review-{n:03}"
            picture = cv.imread(str(safe_file(source, row["image"])))
            if picture is None or list(picture.shape[1::-1]) != row["size"]:
                raise ValueError("Original is unreadable in the first reading coordinate space")
            image = blind / f"{alias}.png"
            write_image(image, picture)
            identity = {"schema": 1, "id": alias, "size": row["size"],
                        "original_sha256": row["sha256"], "photo_sha256": fingerprint(image)}
            write_json(blind / f"{alias}.json", identity)
            reader, manifest_hash = manifests[edition]
            record = identity | {"annotated_by": reader,
                                 "complete": row["complete"], "arrows": row["arrows"],
                                 "landmarks": row.get("landmarks", [])}
            validate(record, identity)
            write_json(control / f"{alias}.json", record)
            index.append({"alias": alias, "edition": edition, "historical_id": row["id"],
                          "historical_manifest_sha256": manifest_hash,
                          "source_group": row["source_group"], "original_sha256": row["sha256"]})
        write_json(control / "index.json", index)
        staging.rename(output)
    return len(rows)


def starter(packet, output):
    """Copy only the seven verified blind photos and their fiches."""
    if output.exists():
        raise FileExistsError(output)
    members = [packet_identity(packet / "lecture-aveugle", alias) for alias in STARTER]
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "starter"
        staging.mkdir(mode=0o700)
        for _, metadata, image in members:
            for file in (metadata, image):
                copyfile(file, staging / file.name)
        staging.rename(output)


def validate(row, identity):
    if any(row.get(key) != identity[key] for key in IDENTITY):
        raise ValueError("Reading belongs to a different original, packet or orientation")
    if not isinstance(row.get("annotated_by"), str) or not row["annotated_by"].strip():
        raise ValueError("Missing reader identifier")
    if type(row.get("complete")) is not bool:
        raise ValueError("Completeness must be explicit")

    def point(value):
        return (isinstance(value, list) and len(value) == 2 and
                all(type(v) in (int, float) and 0 <= v < bound for v, bound in zip(value, row["size"], strict=True)))

    for marks in (row["arrows"], row["landmarks"]):
        ids = [mark["id"] for mark in marks]
        if any(not isinstance(i, str) or not i.strip() for i in ids) or len(set(ids)) != len(ids):
            raise ValueError("Missing or repeated mark identifier")
    for mark in row["arrows"]:
        axis, entry, visibility = mark["axis"], mark["entry"], mark["visibility"]
        if axis is not None and (not isinstance(axis, list) or len(axis) != 2 or
                                 not all(point(p) for p in axis) or axis[0] == axis[1]):
            raise ValueError("Invalid tube axis")
        if visibility not in VISIBILITIES or (visibility == "visible") != (entry is not None):
            raise ValueError("Only visible contacts carry an entry")
        uncertainty = mark["uncertainty_px"]
        if entry is None and uncertainty is not None:
            raise ValueError("A hidden contact has no location uncertainty")
        if entry is not None and (not point(entry) or type(uncertainty) not in (int, float)
                                  or not np.isfinite(uncertainty) or uncertainty < 0):
            raise ValueError("Invalid contact location or uncertainty")
    if any(not point(mark["point"]) for mark in row["landmarks"]):
        raise ValueError("Landmark outside the photo")


def axes_close(left, right, scale):
    if left["axis"] is None or right["axis"] is None:
        return False
    a, b = (np.asarray(row["axis"], dtype=float)*scale for row in (left, right))

    def compatible(origin, other):
        delta = origin[1]-origin[0]
        length = np.linalg.norm(delta)
        unit = delta/length
        shift = other-origin[0]
        low, high = sorted(shift @ unit)
        overlap = max(0., min(length, high)-max(0., low))
        normal = np.array([-unit[1], unit[0]])
        return (overlap >= .5*length and np.abs(shift @ normal).mean() <= 8.
                and abs((other[1]-other[0]) @ normal) <= .30*np.linalg.norm(other[1]-other[0]))

    return compatible(a, b) and compatible(b, a)


def pair_unique(first, second, compatible):
    """Leave multiple plausible matches for adjudication instead of guessing an identity."""
    left = [[j for j, b in enumerate(second) if compatible(a, b)] for a in first]
    right = [[i for i, options in enumerate(left) if j in options] for j in range(len(second))]
    pairs = [(i, js[0]) for i, js in enumerate(left) if len(js) == 1 and len(right[js[0]]) == 1]
    return pairs, [[i, js] for i, js in enumerate(left) if js and (i, js[0]) not in pairs]


def compare(first, second, identity):
    validate(first, identity)
    validate(second, identity)
    if first["annotated_by"].strip().casefold() == second["annotated_by"].strip().casefold():
        raise ValueError("A different reader identifier is required")
    scale = 704/max(identity["size"])
    a, b = first["arrows"], second["arrows"]
    pairs, ambiguous = pair_unique(a, b, lambda left, right: axes_close(left, right, scale))
    matched = []
    for i, j in pairs:
        left, right = a[i], b[j]
        distance = None
        if left["entry"] is not None and right["entry"] is not None:
            distance = float(np.linalg.norm(np.subtract(left["entry"], right["entry"])))
        matched.append({"ids": [left["id"], right["id"]], "visibility": [left["visibility"], right["visibility"]],
                        "distance_px": distance, "beyond_combined_uncertainty": (
                            distance > left["uncertainty_px"] + right["uncertainty_px"] if distance is not None else None
                        )})
    marks = first["landmarks"], second["landmarks"]

    def category(mark):
        return next((kind for prefix, kind in PREFIXES.items() if mark["id"].startswith(prefix)), None)

    mark_pairs, mark_ambiguous = pair_unique(*marks, lambda x, y: np.linalg.norm(
        np.subtract(x["point"], y["point"]))*scale <= 5)
    return {"identity": identity, "readings": [first, second],
            "completeness_agrees": first["complete"] == second["complete"],
            "tube_pairs": matched, "tube_ambiguities": [[a[i]["id"], [b[j]["id"] for j in js]] for i, js in ambiguous],
            "tube_unmatched": unmatched(a, b, pairs),
            "landmark_pairs": [{"ids": [marks[0][i]["id"], marks[1][j]["id"]],
                                "categories": [category(marks[0][i]), category(marks[1][j])]}
                               for i, j in mark_pairs],
            "landmark_ambiguities": [[marks[0][i]["id"], [marks[1][j]["id"] for j in js]]
                                     for i, js in mark_ambiguous],
            "landmark_unmatched": unmatched(*marks, mark_pairs), "status": "awaiting-adjudication"}


def unmatched(first, second, pairs):
    return [[mark["id"] for i, mark in enumerate(row) if all(pair[side] != i for pair in pairs)]
            for side, row in enumerate((first, second))]


def collect(packet, submissions, output):
    """Accept partial return batches and preserve exact submitted bytes separately."""
    if output.exists():
        raise FileExistsError(output)
    reference, blind = packet / "reference", packet / "lecture-aveugle"
    index = read_json(reference / "index.json")
    expected = {entry["alias"]: entry["original_sha256"] for entry in index}
    if not expected or len(expected) != len(index):
        raise ValueError("Missing or duplicate photo in review index")
    seen, results = {}, []
    for path in sorted(submissions.glob("*.json")):
        raw = path.read_bytes()
        submission = json.loads(raw)
        alias = submission["id"]
        if alias not in expected or alias in seen:
            raise ValueError("Unexpected or duplicate returned reading")
        identity, _, _ = packet_identity(blind, alias)
        if identity["original_sha256"] != expected[alias]:
            raise ValueError("Packet photo differs from the reference index")
        first = safe_file(reference, f"{alias}.json")
        result = compare(read_json(first), submission, identity)
        result["record_sha256"] = [fingerprint(first), sha256(raw).hexdigest()]
        seen[alias] = raw
        results.append(result)
    if not seen:
        raise ValueError("No returned readings")
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "results"
        staging.mkdir(mode=0o700)
        raw = staging / "second-readings"
        raw.mkdir()
        for alias, data in seen.items():
            (raw / f"{alias}.json").write_bytes(data)
        write_json(staging / "desaccords.json", {"awaiting_reading": sorted(expected.keys() - seen.keys()),
                                                   "comparisons": results})
        staging.rename(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command, names in (("packet", ("r2", "r3", "r4", "output")), ("starter", ("packet", "output")),
                           ("collect", ("packet", "submissions", "output"))):
        child = sub.add_parser(command)
        for name in names:
            child.add_argument(name, type=Path)
    args = parser.parse_args()
    if args.command == "packet":
        print(build_packet({"r2": args.r2, "r3": args.r3, "r4": args.r4}, args.output), "photos")
    elif args.command == "starter":
        starter(args.packet, args.output)
    else:
        collect(args.packet, args.submissions, args.output)
