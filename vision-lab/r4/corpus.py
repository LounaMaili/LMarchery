"""Audit public-photo provenance and bind blind readings to unchanged originals."""
from __future__ import annotations

import argparse
from pathlib import Path

from lab_data import fingerprint, safe_file, write_json
from r3.review import audit as audit_r3, blind as write_blind, prepare as prepare_r3, read_json

HERE = Path(__file__).resolve().parent
PREFIXES = {"trou-": "old-hole", "dechirure-": "tear", "leurre-": "lookalike"}


def audit(rows, originals):
    past = {row["sha256"] for row in read_json(HERE.parent / "r3/real-sources.json")["frames"]}
    photographers = {}
    for row in rows:
        identifier = row.get("photographer_id", "").strip().casefold()
        if not identifier or photographers.setdefault(identifier, row["split"]) != row["split"]:
            raise ValueError("Photographer is absent or crosses partitions")
        if row["sha256"] in past:
            raise ValueError("Prior R3 evaluation original is reserved")
        if row.get("use") != "public-lab":
            raise ValueError("The public catalog cannot admit a private or unreviewed original")
    return audit_r3(rows, originals)


def typed_nuisances(landmarks):
    rows = []
    for mark in landmarks:
        kind = next((kind for prefix, kind in PREFIXES.items() if mark["id"].startswith(prefix)), None)
        if kind:
            rows.append({"kind": kind, "point": mark["point"], "radius_px": 0.})
    return rows


def prepare(rows, originals, packet, labels, output):
    rows = audit(rows, originals)
    expected = [read_json(path) for path in sorted(packet.glob("review-*.json"))]
    by_hash = {row["original_sha256"]: row for row in expected}
    if set(by_hash) != {row["sha256"] for row in rows} or len(expected) != len(by_hash):
        raise ValueError("Packet differs from catalog")
    for label in labels:
        entry = by_hash.get(label["original_sha256"])
        if entry is None or any(entry[key] != label[key] for key in ("id", "size", "photo_sha256")):
            raise ValueError("Reading belongs to another photo or orientation")
        photo = safe_file(packet, f"{entry['id']}.png")
        if fingerprint(photo) != entry["photo_sha256"]:
            raise ValueError("The packet photo changed")
    prepare_r3(originals, rows, labels, output)
    path = output / "manifest.json"
    manifest = read_json(path)
    for row in manifest["frames"]:
        row["nuisances"] = typed_nuisances(row["landmarks"])
    manifest["annotation_status"] = "first-reading-awaiting-independent-review"
    write_json(path, manifest)


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
            tool.add_argument("packet", type=Path)
            tool.add_argument("labels", type=Path, nargs="+")
    args = parser.parse_args()
    frames = read_json(args.catalog)["frames"]
    if args.command == "audit":
        print(f"{len(audit(frames, args.originals))} validated originals")
    elif args.command == "blind":
        write_blind(args.originals, audit(frames, args.originals), args.output)
    else:
        prepare(frames, args.originals, args.packet, [read_json(path) for path in args.labels], args.output)
