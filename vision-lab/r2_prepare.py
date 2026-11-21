"""Prepare pinned public R2 frames with local, prediction-blind annotations."""
import argparse
import json
from pathlib import Path
from shutil import copyfile

from lab_data import fingerprint, safe_file, write_json
from r2_corpus import read_corpus


def prepare(originals, annotations, root):
    registry = json.loads(Path(__file__).with_name("r2-sources.json").read_text(encoding="utf-8"))
    labels = json.loads(annotations.read_text(encoding="utf-8"))
    if set(labels["frames"]) != {row["id"] for row in registry}:
        raise ValueError("Annotation inventory must cover the pinned acquisitions")
    frames = []
    sources = {}
    for row in registry:
        for path_key, sha_key in (("image", "sha256"), ("before", "before_sha256")):
            if row[path_key]:
                path = safe_file(originals, row[path_key])
                if fingerprint(path) != row[sha_key]:
                    raise ValueError(f"Hash mismatch: {row[path_key]}")
                sources[row[path_key]] = path
        marks = labels["frames"][row["id"]]
        frames.append(row | {"split": "test", "source": "public-photo", "geometry": None,
                             "complete": marks["complete"], "arrows": marks["arrows"]})
    root.mkdir(parents=True, exist_ok=False)
    for name, path in sources.items():
        copyfile(path, root / name)
    write_json(root / "manifest.json", {"schema": 1, "annotated_by": labels["annotated_by"], "frames": frames})
    read_corpus(root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("originals", type=Path)
    parser.add_argument("annotations", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.originals, args.annotations, args.output)
