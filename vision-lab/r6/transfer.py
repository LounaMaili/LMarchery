"""Paired, short transfer experiment restricted to the generated R6 boards."""
import argparse
import time
from pathlib import Path

import cv2 as cv
import numpy as np
import torch
from torch.utils.data import DataLoader

from benchmark import maximum_matches
from lab_data import fingerprint, read_manifest, safe_file, write_json
from r3.evaluate import THRESHOLDS, select_threshold
from r3.inference import predict, propose
from r3.model import EntryNet, loss
from r3.train import Photos
from r4.trial import MODEL_SHA256
from r6.decode import multiple
from r6.diagnose import frozen
from r6.measure import observe, points, summary

MANIFEST = "8d1df349022e2795969283ba7504e0fedb9da10cbe65f78061c5d1a98a5a4027"


def corpus(root):
    if fingerprint(root / "manifest.json") != MANIFEST:
        raise ValueError("This experiment is bound to the generated R6 manifest")
    return read_manifest(root)


def fit(root, rows, model, seed, output):
    train, dev = [Photos(root, [r for r in rows if r["split"] == split], split == "train")
                  for split in ("train", "dev")]
    batches = DataLoader(train, batch_size=8, shuffle=True, generator=torch.Generator().manual_seed(seed))
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001)
    history = []
    for epoch in range(1, 5):
        model.train()
        for images, labels in batches:
            optimizer.zero_grad(set_to_none=True)
            error = loss(model(images), labels)
            error.backward()
            optimizer.step()
        model.eval()
        with torch.inference_mode():
            value = sum(float(loss(model(images), labels))*len(images)
                        for images, labels in DataLoader(dev, batch_size=8)) / len(dev)
        if not history or value < min(history):
            torch.save(model.state_dict(), output / "model.pt")
        history.append(value)
    model.load_state_dict(torch.load(output / "model.pt", weights_only=True))
    return history


def predictions(root, rows, model, split):
    records = []
    for row in rows:
        if row["split"] == split:
            frame = cv.imread(str(safe_file(root, row["image"])))
            maps = predict(model, frame, True, "cpu")
            _, tubes, _ = propose(maps)
            records.append((row, multiple(maps), tubes))
    return records


def evaluate(records, threshold):
    return [observe(row, points(items, threshold), tubes, np.ones(2)) for row, items, tubes in records]


def run(root, checkpoint, output):
    rows = corpus(root)
    cv.setNumThreads(1)
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    output.mkdir(parents=True, exist_ok=False)
    for seed in (6021, 6022):
        for name in ("scratch", "r3"):
            torch.manual_seed(seed)
            model = frozen(checkpoint) if name == "r3" else EntryNet()
            run_dir = output / f"{name}-{seed}"
            run_dir.mkdir()
            start = time.perf_counter()
            history = fit(root, rows, model, seed, run_dir)
            seconds = time.perf_counter()-start
            dev = predictions(root, rows, model, "dev")
            curve = []
            for threshold in THRESHOLDS:
                matched, unassigned = 0, 0
                for row, items, _ in dev:
                    proposals = points(items, threshold)
                    truth = [np.array(m["entry"]) for m in row["arrows"] if m["entry"] is not None]
                    found = len(maximum_matches(truth, proposals, 10))
                    matched += found
                    unassigned += len(proposals)-found
                curve.append(dict(threshold=threshold, matched=matched, unmatched_per_image=unassigned/len(dev)))
            threshold = select_threshold(curve)
            policy = dict(seed=seed, manifest=MANIFEST, start_sha256=MODEL_SHA256 if name == "r3" else None,
                          checkpoint_sha256=fingerprint(run_dir / "model.pt"), dev_loss=history,
                          epoch=int(np.argmin(history))+1, dev_curve=curve, threshold=threshold,
                          elapsed_training_seconds=seconds, torch=torch.__version__)
            write_json(run_dir / "policy.json", policy)
            write_json(run_dir / "dev.json", summary(evaluate(dev, threshold)))
    # Run the locked test only after *all* policies have been written.
    import json
    for run_dir in sorted(output.iterdir()):
        model = EntryNet()
        model.load_state_dict(torch.load(run_dir / "model.pt", weights_only=True))
        policy = json.loads((run_dir / "policy.json").read_text())
        records = predictions(root, rows, model.eval(), "test")
        write_json(run_dir / "test.json", summary(evaluate(records, policy["threshold"])))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("corpus", "checkpoint", "output"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    run(args.corpus, args.checkpoint, args.output)
