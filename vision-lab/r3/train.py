"""Train exclusively on validated, synthetic train/dev groups."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import time

import cv2 as cv
import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset

from lab_data import fingerprint, read_manifest, safe_file, write_json
from r3.model import EntryNet, loss

from r3.windows import WINDOW, tensor_image

SEED = 27043


def target_maps(mask, entries, left, top, width, height):
    cols, rows = WINDOW
    crop = mask[top:top+height, left:left+width]
    # Area resampling retains narrow shafts even at global scale.
    shaft = cv.resize(crop[:, :, 1:].max(axis=2), WINDOW, interpolation=cv.INTER_AREA) > 32
    seen = cv.resize(crop[:, :, 1], WINDOW, interpolation=cv.INTER_AREA) > 32
    hidden = cv.resize(crop[:, :, 2], WINDOW, interpolation=cv.INTER_AREA) > 32
    gaussian = np.zeros((rows, cols), np.float32)
    yy, xx = np.ogrid[:rows, :cols]
    for x, y in entries:
        if left <= x < left + width and top <= y < top + height:
            u = (x-left+.5)*cols/width - .5
            v = (y-top+.5)*rows/height - .5
            gaussian = np.maximum(gaussian, np.exp(-((xx-u)**2+(yy-v)**2) / 8).astype(np.float32))
    supervised = seen ^ hidden
    if any(not (left <= x < left+width and top <= y < top+height) for x, y in entries):
        # A crop that excludes an annotated visible contact does not label its visibility.
        supervised = np.zeros_like(supervised)
    return np.stack((shaft, gaussian, seen, supervised)).astype(np.float32)


class Photos(Dataset):
    def __init__(self, root, rows, train):
        self.samples = []
        self.train = train
        for row in rows:
            mask = safe_file(root, row["mask"])
            if fingerprint(mask) != row["mask_sha256"]:
                raise ValueError(f"Mask changed: {row['id']}")
            image = cv.imread(str(safe_file(root, row["image"])))
            labels = cv.imread(str(mask))
            if (image is None or labels is None or image.shape != labels.shape
                or list(image.shape[1::-1]) != row["size"] or np.any(labels[:, :, 0])):
                raise ValueError(f"Invalid photo or mask: {row['id']}")
            entries = [mark["entry"] for mark in row["arrows"] if mark["entry"] is not None]
            self.samples.append((image, labels, entries))

    def __len__(self):
        return len(self.samples) * (2 if self.train else 1)

    def __getitem__(self, index):
        frame, mask, entries = self.samples[index // 2 if self.train else index]
        height, width = frame.shape[:2]
        left, top = 0, 0
        if self.train and index % 2:
            rng = np.random.default_rng(SEED + index)
            cw, ch = min(width, WINDOW[0]), min(height, WINDOW[1])
            left, top = int(rng.integers(width-cw+1)), int(rng.integers(height-ch+1))
            width, height = cw, ch
        patch = frame[top:top+height, left:left+width]
        image = tensor_image(cv.resize(patch, WINDOW, interpolation=cv.INTER_AREA))
        return image, target_maps(mask, entries, left, top, width, height)


def run(root, output, device, epochs, batch_size):
    if epochs < 1 or batch_size < 1:
        raise ValueError("Epoch and batch counts must be positive")
    if device == "cuda" and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    cv.setNumThreads(1)
    torch.set_num_threads(2)
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    torch.use_deterministic_algorithms(True)
    torch.manual_seed(SEED)
    rows = read_manifest(root)
    # Reject arbitrary substitution of a historical or experimental real set.
    if any(row["source"] != "synthetic" or not row["source_group"].startswith("r3-board-") for row in rows):
        raise ValueError("This trainer accepts only the R3 synthetic corpus")
    train, dev = [Photos(root, [row for row in rows if row["split"] == split], split == "train")
                  for split in ("train", "dev")]
    if not len(train) or not len(dev):
        raise ValueError("Training and development groups are required")
    train_batches = DataLoader(train, batch_size=batch_size, shuffle=True,
                               generator=torch.Generator().manual_seed(SEED))
    dev_batches = DataLoader(dev, batch_size=batch_size)
    model = EntryNet().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.0015)
    output.mkdir(parents=True, exist_ok=False)
    start = time.perf_counter()
    history = []
    best = float("inf")
    for epoch in range(epochs):
        model.train()
        train_loss = 0.
        for photos, labels in train_batches:
            photos, labels = photos.to(device), labels.to(device)
            optimizer.zero_grad(set_to_none=True)
            value = loss(model(photos), labels)
            value.backward()
            optimizer.step()
            train_loss += float(value.detach())*len(photos)
        model.eval()
        dev_loss = 0.
        with torch.inference_mode():
            for photos, labels in dev_batches:
                dev_loss += float(loss(model(photos.to(device)), labels.to(device)))*len(photos)
        dev_loss /= len(dev)
        summary = {"epoch": epoch+1, "train_loss": train_loss/len(train), "dev_loss": dev_loss}
        history.append(summary)
        print(summary, flush=True)
        if dev_loss < best:
            best = dev_loss
            torch.save({name: weight.detach().cpu() for name, weight in model.state_dict().items()}, output / "model.pt")
    elapsed = time.perf_counter() - start
    write_json(output / "training.json", {"device": device, "torch": torch.__version__,
               "cuda": torch.version.cuda if device == "cuda" else None,
               "cudnn": torch.backends.cudnn.version() if device == "cuda" else None,
               "gpu": torch.cuda.get_device_name() if device == "cuda" else None,
               "epochs": history, "chosen_epoch": min(history, key=lambda row: row["dev_loss"])["epoch"],
               "train_samples": len(train), "dev_samples": len(dev), "seed": SEED, "batch_size": batch_size,
               "manifest_sha256": fingerprint(root / "manifest.json"),
               "cpu_threads": torch.get_num_threads(), "seconds": elapsed,
               "parameters": sum(value.numel() for value in model.parameters()),
               "cuda_peak_bytes": torch.cuda.max_memory_allocated() if device == "cuda" else None,
               "model_bytes": (output / "model.pt").stat().st_size,
               "model_sha256": fingerprint(output / "model.pt")})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=12)
    args = parser.parse_args()
    run(args.dataset, args.output, args.device, args.epochs, args.batch_size)
