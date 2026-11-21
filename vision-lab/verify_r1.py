"""Rebuild frozen R1 cases without private data and check published results."""
import argparse
import hashlib
import json
from pathlib import Path

import cv2 as cv

from benchmark import run
from lab_data import fingerprint, generate, read_manifest
from stress_data import generate_stress

R1_MANIFEST = "6d588e541a0317e77be32f07ba62c13251c37455145190822f6dd09b8e43d1c2"
R1_MODEL = "139a4aef529aef1378426f124eb11f04909a0554d27d14176c48c9aee90d1f9f"
R1_REFERENCES = "cb37009ef7572659a768c410e1c90f6a9a9517b8d81de597495da12e06c462a6"
R1_MODEL_VALUES = "577ad40923b0e7c38732d160d6400d50dd535b2192a60fb78ce7802c36abf182"


def normalized_digest(path):
    def normalize(value):
        if isinstance(value, float):
            return round(value, 6) + 0.
        if isinstance(value, list):
            return [normalize(item) for item in value]
        if isinstance(value, dict):
            return {key: normalize(item) for key, item in value.items()
                    if key not in {"sha256", "before_sha256"}}
        return value

    payload = normalize(json.loads(path.read_text(encoding="utf-8")))
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def require(actual, expected, label):
    if actual != expected:
        raise SystemExit(f"{label}: expected {expected}, got {actual}")


def counts(path, checks, fields=("known_positive_entries", "detected", "fp")):
    data = json.loads((path / "report.json").read_text(encoding="utf-8"))["summaries"]
    for key, expected in checks.items():
        require(tuple(data[key][field] for field in fields), expected, key)


def verify(root, fedora_bytes):
    cv.setNumThreads(1)
    root.mkdir(parents=True, exist_ok=False)
    synthetic, stress = root / "synthetic", root / "white"
    pilot_result, stress_result = root / "synthetic-results", root / "white-results"
    generate(synthetic)
    repeat = root / "synthetic-repeat"
    generate(repeat)
    manifest = synthetic / "manifest.json"
    require(fingerprint(manifest), fingerprint(repeat / "manifest.json"),
            "Repeatable manifest and image hashes on this runner")
    rows = read_manifest(synthetic)
    read_manifest(repeat)
    require(normalized_digest(manifest), R1_REFERENCES, "Frozen annotations and geometry")
    if fedora_bytes:
        require(fingerprint(manifest), R1_MANIFEST, "Original Fedora manifest and image hashes")
    require(tuple(sum(row["split"] == name for row in rows) for name in ("train", "dev", "test")),
            (36, 18, 132), "Partition sizes")
    cv.setRNGSeed(20261121)
    run(synthetic, pilot_result, None)
    model = pilot_result / "model.json"
    require(normalized_digest(model), R1_MODEL_VALUES, "Frozen orientation model coefficients")
    if fedora_bytes:
        require(fingerprint(model), R1_MODEL, "Original Fedora orientation model bytes")
    counts(pilot_result, {
        "test/pair/A-heuristic": (228, 100, 13),
        "test/pair/B-learned": (228, 101, 5),
        "test/pair/B-learned/scenario/grouped": (99, 0, 2),
        "test/single/A-heuristic": (228, 0, 8),
        "test/single/B-learned": (228, 0, 24),
    })
    generate_stress(stress)
    require(len(read_manifest(stress)), 12, "White holdout size")
    cv.setRNGSeed(20261121)
    run(stress, stress_result, model)
    counts(stress_result, {
        "test/pair/A-heuristic/condition/white-visible": (6, 5, 0),
        "test/pair/B-learned/condition/white-visible": (6, 6, 0),
    })
    counts(stress_result, {
        "test/pair/A-heuristic/condition/white-masked": (0, 0, 6),
        "test/pair/B-learned/condition/white-masked": (0, 0, 6),
    }, ("known_positive_entries", "detected", "unassigned"))
    print("R1 data, model and published results reproduced.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--fedora-bytes", action="store_true",
                        help="Also require original serialized R1 bytes from Fedora")
    args = parser.parse_args()
    verify(args.output, args.fedora_bytes)
