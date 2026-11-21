"""Private, local replay of the frozen R3 model on unlabelled originals."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import time

import cv2 as cv
import numpy as np
import torch

from detector import WORK_SIDE
from lab_data import fingerprint, write_image, write_json
from r3.inference import predict, propose
from r3.model import EntryNet

MODEL_SHA256 = "fd4c8490eed9ad37602479bcbee6b8d8d7077730fc48aaed95b8c3e0fb03cece"
POLICY_SHA256 = "6b25700c8edfc68ae005e02c83eb8558baf3bbff49156de1d6e0aa898be59065"
FORMATS = {".jpg", ".jpeg", ".png", ".dng"}


def oriented(frame, orientation):
    if orientation == 1:
        return frame
    if orientation == 2:
        return cv.flip(frame, 1)
    if orientation == 3:
        return cv.rotate(frame, cv.ROTATE_180)
    if orientation == 4:
        return cv.flip(frame, 0)
    if orientation == 5:
        return cv.transpose(frame)
    if orientation == 6:
        return cv.rotate(frame, cv.ROTATE_90_CLOCKWISE)
    if orientation == 7:
        return cv.flip(cv.transpose(frame), -1)
    if orientation == 8:
        return cv.rotate(frame, cv.ROTATE_90_COUNTERCLOCKWISE)
    raise ValueError("Unsupported orientation tag")


def facts(path):
    fields = ("-Orientation", "-ImageWidth", "-ImageHeight", "-ExifImageWidth", "-ExifImageHeight",
              "-RawImageWidth", "-RawImageHeight", "-DateTimeOriginal", "-SubSecTimeOriginal")
    result = subprocess.check_output(["exiftool", "-q", "-q", "-n", "-j", *fields, str(path)])
    return json.loads(result)[0]


def decode(path, metadata):
    if path.suffix.lower() != ".dng":
        return cv.imread(str(path)), "oriented-original"
    previews = []
    for tag in ("JpgFromRaw", "PreviewImage", "OtherImage", "ThumbnailImage"):
        bits = subprocess.check_output(["exiftool", "-q", "-q", "-b", f"-{tag}", str(path)])
        if not bits:
            continue
        frame = cv.imdecode(np.frombuffer(bits, np.uint8), cv.IMREAD_COLOR | cv.IMREAD_IGNORE_ORIENTATION)
        if frame is not None:
            previews.append((frame, tag))
    if not previews:
        return None, "no-embedded-preview"
    frame, tag = max(previews, key=lambda pair: pair[0].shape[0] * pair[0].shape[1])
    return oriented(frame, metadata.get("Orientation", 1)), f"embedded-{tag}"


def work_size(frame):
    height, width = frame.shape[:2]
    factor = min(1., WORK_SIDE / max(width, height))
    size = round(width * factor), round(height * factor)
    resized = cv.resize(frame, size, interpolation=cv.INTER_AREA) if factor < 1. else frame
    return resized, np.array([size[0]/width, size[1]/height])


def render(folder, frame, maps, tubes, accepted, scale):
    draw = frame.copy()
    for tube in tubes:
        start, end = np.rint(tube.ends).astype(int).tolist()
        cv.line(draw, start, end, (247, 192, 47), 1, cv.LINE_AA)
    for number, peak in enumerate(accepted, 1):
        location = tuple(np.rint(peak.point).astype(int))
        cv.circle(draw, location, 8, (53, 249, 241), 2, cv.LINE_AA)
        cv.putText(draw, str(number), (location[0]+9, location[1]-7),
                   cv.FONT_HERSHEY_SIMPLEX, .48, (48, 255, 220), 2, cv.LINE_AA)
    write_image(folder / "propositions.png", draw)
    for channel, name in zip(maps, ("tube", "contact", "visibilite"), strict=True):
        write_image(folder / f"carte-{name}.png",
                    cv.applyColorMap(np.rint(channel*255).astype(np.uint8), cv.COLORMAP_TURBO))
    np.savez_compressed(folder / "cartes.npz", shaft=maps[0], contact=maps[1], visibility=maps[2])
    return [{"n": i, "working_xy": peak.point.tolist(), "oriented_xy": (peak.point/scale).tolist(),
             "rank": peak.confidence, "heat": peak.heat, "visibility": peak.visibility}
            for i, peak in enumerate(accepted, 1)]


def run(source, checkpoint, policy_path, output):
    source, output = source.resolve(strict=True), output.resolve()
    if output.is_relative_to(source):
        raise ValueError("Keep reports outside the originals")
    if fingerprint(checkpoint) != MODEL_SHA256 or fingerprint(policy_path) != POLICY_SHA256:
        raise ValueError("Frozen R3 checkpoint or policy changed")
    if not source.is_dir():
        raise ValueError("Expected a source directory")
    paths = sorted(p for p in source.rglob("*") if p.is_file() and not p.is_symlink()
                   and p.resolve().is_relative_to(source) and p.suffix.lower() in FORMATS)
    if not paths:
        raise ValueError("No supported originals")
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    cv.setNumThreads(1)
    torch.set_num_threads(2)
    model = EntryNet()
    model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True))
    model.eval()
    thresholds = json.loads(policy_path.read_text(encoding="utf-8"))["thresholds"]
    rows = []
    for index, path in enumerate(paths, 1):
        alias = f"photo-{index:03d}"
        tags = facts(path)
        frame, decoder = decode(path, tags)
        raw_size = [tags.get("RawImageWidth") or tags.get("ExifImageWidth") or tags.get("ImageWidth"),
                    tags.get("RawImageHeight") or tags.get("ExifImageHeight") or tags.get("ImageHeight")]
        source_digest = fingerprint(path)
        row = {"id": alias, "original": path.relative_to(source).as_posix(), "sha256": source_digest,
               "format": path.suffix.lower(), "orientation": tags.get("Orientation", 1),
               "recorded_size": raw_size, "decoded_size": list(frame.shape[1::-1]) if frame is not None else None,
               "decoder": decoder, "capture": tags.get("DateTimeOriginal"),
               "subsecond": str(tags.get("SubSecTimeOriginal", "")), "modes": {}}
        rows.append(row)
        if frame is None:
            continue
        folder = output / alias
        folder.mkdir()
        work, scale = work_size(frame)
        write_image(folder / "image-de-travail.png", work)
        row["working_size"] = list(work.shape[1::-1])
        for mode, threshold in thresholds.items():
            mode_folder = folder / mode
            mode_folder.mkdir()
            start = time.perf_counter()
            maps = predict(model, work, mode == "native", "cpu")
            entries, tubes, reasons = propose(maps)
            elapsed_ms = 1000 * (time.perf_counter() - start)
            accepted = [item for item in entries if item.confidence >= threshold]
            declines = Counter(reasons)
            if not tubes:
                declines["no_shaft"] += 1
            declines["confidence_below_threshold"] += len(entries)-len(accepted)
            row["modes"][mode] = {"decision": "proposed" if accepted else "abstained", "threshold": threshold,
                                  "shaft_count": len(tubes), "reasons": {k:v for k,v in declines.items() if v},
                                  "points": render(mode_folder, work, maps, tubes, accepted, scale),
                                  "latency_ms": elapsed_ms}
        if fingerprint(path) != source_digest:
            raise ValueError("Original changed during replay")
    write_json(output / "inventaire-et-propositions.json",
               {"experiment": "unlabelled-frozen-R3", "checkpoint_sha256": MODEL_SHA256,
                "policy_sha256": POLICY_SHA256, "images": rows})
    print(f"{len(rows)} originals inventoried, {sum(bool(row['modes']) for row in rows)} decoded")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("source", "checkpoint", "policy", "output"):
        parser.add_argument(key, type=Path)
    args = parser.parse_args()
    run(args.source, args.checkpoint, args.policy, args.output)
