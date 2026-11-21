"""Export the research graph and compare fixed-window scores offline."""
import argparse
from pathlib import Path
import subprocess

import cv2 as cv
import numpy as np
import torch

from lab_data import fingerprint, safe_file, write_json
from r3.train import WINDOW, tensor_image
from r6.decode import multiple
from r6.diagnose import frozen
from r6.measure import points
from r6.transfer import corpus


def run(root, checkpoint, runtime_python, output):
    rows = corpus(root)
    output.mkdir(parents=True, exist_ok=False)
    model = frozen(checkpoint)
    torch.set_num_threads(2)
    cv.setNumThreads(1)
    for split in ("train", "dev"):
        # Same central region in every photo. No use of the locations or test images.
        patches = []
        for r in rows:
            if r["split"] == split:
                frame = cv.imread(str(safe_file(root, r["image"])))
                w, h = WINDOW
                x, y = (frame.shape[1]-w)//2, (frame.shape[0]-h)//2
                patches.append(tensor_image(frame[y:y+h, x:x+w]))
        np.save(output / (split+".npy"), np.stack(patches))
    windows = np.load(output / "dev.npy")
    source = torch.from_numpy(windows)
    torch.onnx.export(model, source[:1], output / "float.onnx", input_names=["rgb"],
                      output_names=["logits"], opset_version=18, dynamo=True, external_data=False)
    with torch.inference_mode():
        baseline = np.concatenate([model(frame[None]).sigmoid().numpy() for frame in source])
    # The ORT worker may use a different Python ABI and must not inherit export dependencies.
    import os
    env = {k: v for k,v in os.environ.items() if k != "PYTHONPATH"}
    subprocess.run([str(runtime_python.absolute()),
                    str(Path(__file__).with_name("ort_worker.py")), str(output.resolve())],
                   check=True, env=env)
    def decisions(batch):
        return [sorted(tuple(p) for p in points(multiple(maps), .42)) for maps in batch]
    reference = decisions(baseline)
    reports = {}
    for variant in ("float", "int8"):
        logits = np.load(output / (variant+"-logits.npy"))
        maps = torch.from_numpy(logits).sigmoid().numpy()
        current = decisions(maps)
        graph = output / (variant+".onnx")
        reports[variant] = dict(bytes=graph.stat().st_size, sha256=fingerprint(graph),
                                max_probability_error=float(np.max(np.abs(maps-baseline))),
                                reference_points=sum(map(len,reference)), points=sum(map(len,current)),
                                windows=len(windows), changed_point_sets=sum(a!=b for a,b in zip(reference,current)),
                                changed_silence=sum(bool(a)!=bool(b) for a,b in zip(reference,current)))
    write_json(output / "parity.json", reports)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("corpus","checkpoint","runtime_python","output"):
        parser.add_argument(key, type=Path)
    args = parser.parse_args()
    run(args.corpus, args.checkpoint, args.runtime_python, args.output)
