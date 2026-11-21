"""Measure a complete single-photo ONNX CPU run on an oriented public regression frame."""
import argparse
from collections import defaultdict
from pathlib import Path
import resource
import time

import cv2 as cv
import numpy as np
import onnxruntime as ort

from benchmark import WORK_SIDE, maximum_matches
from lab_data import fingerprint, read_manifest, safe_file, write_json
from r3.windows import tiled_maps
from r6.decode import multiple


def run(root, graph, output):
    rows = read_manifest(root)
    if not rows or any(r["source"] != "public-photo" or r["split"] != "test" for r in rows):
        raise ValueError("Expected public regression photos")
    cv.setNumThreads(1)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    start = time.perf_counter()
    session = ort.InferenceSession(str(graph), sess_options=options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter()-start)*1000

    def infer(batch):
        logits = np.concatenate([session.run(["logits"], {"rgb": patch[None]})[0] for patch in batch])
        return 1/(1+np.exp(-logits))

    measurements = defaultdict(list)
    decisions = []
    for repeat in range(5):
        for row in rows:
            start = time.perf_counter()
            # IMREAD_COLOR applies EXIF orientation by default. R4 sizes are oriented.
            frame = cv.imread(str(safe_file(root, row["image"])), cv.IMREAD_COLOR)
            if frame is None or list(frame.shape[1::-1]) != row["size"]:
                raise ValueError("Oriented size mismatch")
            w, h = row["size"]
            ratio = min(1., WORK_SIDE/max(w,h))
            shape = (round(w*ratio), round(h*ratio))
            if shape != (w,h):
                frame = cv.resize(frame, shape, interpolation=cv.INTER_AREA)
            maps = tiled_maps(frame, infer)
            scale = np.array(shape) / [w,h]
            points = [item.point for item in multiple(maps) if item.confidence >= .42]
            original = [(point+.5)/scale-.5 for point in points]
            elapsed = (time.perf_counter()-start)*1000
            measurements["first_pass" if repeat == 0 else "warm"].append(elapsed)
            if repeat == 0:
                truth = [(np.array(m["entry"])+.5)*scale-.5 for m in row["arrows"] if m["entry"] is not None]
                matched = maximum_matches(truth, points, 10)
                decisions.append(dict(id=row["id"], known=len(truth), matched=len(matched),
                                      proposals=len(points), oriented_points=[p.tolist() for p in original]))
    write_json(output, dict(onnx_sha256=fingerprint(graph), runtime=ort.__version__, load_ms=load_ms,
                            first_photo_ms=measurements["first_pass"][0], warm_photos=len(measurements["warm"]),
                            warm_p50_p95_ms=np.percentile(measurements["warm"], [50,95]).tolist(),
                            peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                            photos=decisions))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("public", "graph", "output"):
        parser.add_argument(key, type=Path)
    opts = parser.parse_args()
    run(opts.public, opts.graph, opts.output)
