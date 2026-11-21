"""Ablate decoding, representation and resolution without updating the R3 weights."""
import argparse
from collections import Counter, defaultdict
from pathlib import Path

import cv2 as cv
import numpy as np
import torch

from benchmark import frame_at_work_size, maximum_matches
from lab_data import fingerprint, read_manifest, safe_file, write_json
from r3.inference import predict, propose
from r3.model import EntryNet
from r4.trial import MODEL_SHA256, POLICY_SHA256
from r6.decode import multiple, oracle
from r6.measure import observe, points, summary

R3_MANIFEST = "7877014d6463c37a639c55528f6e79c4958c4c060735325c5a7c40e34be8a7eb"


def frozen(checkpoint):
    if fingerprint(checkpoint) != MODEL_SHA256:
        raise ValueError("Expected the released synthetic R3 checkpoint")
    model = EntryNet()
    model.load_state_dict(torch.load(checkpoint, weights_only=True, map_location="cpu"))
    return model.eval()


def support_at_contacts(maps, arrows):
    shaft, _, visibility = maps
    _, labels = cv.connectedComponents((shaft >= .48).astype(np.uint8))
    h, w = shaft.shape
    assignments = []
    counts = Counter()
    for arrow in arrows:
        if arrow["entry"] is None:
            continue
        x, y = arrow["entry"]
        x0, x1 = max(0, int(x)-7), min(w, int(x)+9)
        y0, y1 = max(0, int(y)-7), min(h, int(y)+9)
        patch = labels[y0:y1, x0:x1]
        yy, xx = np.ogrid[y0:y1, x0:x1]
        distance = (xx-x)**2 + (yy-y)**2
        supported = (distance <= 49) & (patch > 0)
        if not supported.any():
            counts["no_shaft_within_7px"] += 1
            continue
        nearest = patch.flat[np.argmin(np.where(supported, distance, np.inf))]
        assignments.append(nearest)
        seen = visibility[y0:y1, x0:x1][supported & (patch == nearest)].mean()
        counts["visible_support" if seen >= .5 else "low_visibility_on_support"] += 1
    counts["contacts_sharing_a_component"] = sum(n for n in Counter(assignments).values() if n > 1)
    return counts


def synth(root, model, threshold):
    if fingerprint(root / "manifest.json") != R3_MANIFEST:
        raise ValueError("Expected the original R3 synthetic manifest")
    rows = [r for r in read_manifest(root) if r["split"] == "dev"]
    observations = defaultdict(list)
    segmentation = []
    associations = defaultdict(Counter)
    for row in rows:
        frame, scale = frame_at_work_size(root, row, "image")
        path = safe_file(root, row["mask"])
        if fingerprint(path) != row["mask_sha256"]:
            raise ValueError("Mask checksum mismatch")
        truth = oracle(cv.imread(str(path)), row["arrows"])
        maps = predict(model, frame, True, "cpu")
        for name, channels in (("renderer", truth), ("predicted", maps)):
            associations[name].update(support_at_contacts(channels, row["arrows"]))
        pred, target = maps[0] >= .48, truth[0] > 0
        segmentation.append([int((pred & target).sum()), int((pred | target).sum())])
        for name, channels in (("predicted", ()), ("oracle", (0,1,2)), ("shaft", (0,)),
                               ("contact", (1,)), ("visibility", (2,))):
            mixed = maps.copy()
            for channel in channels:
                mixed[channel] = truth[channel]
            old, tubes, _ = propose(mixed)
            relaxed, _, _ = propose(mixed, multiple_per_component=True)
            for decoder, entries in (("historical", old), ("per_component", relaxed),
                                     ("multiple", multiple(mixed))):
                observations[name+"/"+decoder].append(observe(row, points(entries, threshold), tubes, scale))
    intersection, union = np.sum(segmentation, axis=0)
    return dict(shaft_foreground_iou=float(intersection/union), support_at_known_contacts=associations,
                methods={k: summary(v) for k, v in observations.items()})


def roi_comparison(original, frame, scale, rect, truth, model, threshold):
    x, y, cw, ch = rect
    yy, xx = np.indices((ch, cw), dtype=np.float32)
    low = cv.remap(frame, ((xx+x+.5)*scale[0]-.5).astype(np.float32),
                   ((yy+y+.5)*scale[1]-.5).astype(np.float32), cv.INTER_LINEAR)
    relative = [p-[x,y] for p in truth]
    eligible = [p*scale for p in relative if (p >= 0).all() and (p < [cw,ch]).all()]
    arms = {}
    for name, crop in (("native", original[y:y+ch, x:x+cw]), ("from704", low)):
        maps = predict(model, crop, True, "cpu")
        proposals = points(multiple(maps), threshold)
        gray = cv.cvtColor(crop, cv.COLOR_BGR2GRAY)
        gx, gy = (cv.Sobel(gray, cv.CV_32F, dx, dy) for dx,dy in ((1,0),(0,1)))
        arms[name] = dict(proposals=len(proposals), known=len(eligible),
                          matched=len(maximum_matches(eligible, [p*scale for p in proposals], 10)),
                          gradient_rms=float(np.sqrt((gx*gx+gy*gy).mean())))
    return dict(roi=rect, **arms)


def resolution(root, model, threshold):
    result, annotated_tiles = [], []
    full = defaultdict(list)
    for row in read_manifest(root):
        if row["source"] != "public-photo" or row["split"] != "test":
            raise ValueError("Resolution regression requires the public R4 holdout")
        frame, scale = frame_at_work_size(root, row, "image")
        maps = predict(model, frame, True, "cpu")
        old, tubes, _ = propose(maps)
        relaxed, _, _ = propose(maps, multiple_per_component=True)
        for name, candidates in (("historical", old), ("per_component", relaxed), ("multiple", multiple(maps))):
            full[name].append(observe(row, points(candidates, threshold), tubes, scale))
        original = cv.imread(str(safe_file(root, row["image"])))
        h, w = original.shape[:2]
        cw, ch = min(w, 576), min(h, 480)
        x, y = (w-cw)//2, (h-ch)//2
        truth = [np.array(m["entry"]) for m in row["arrows"] if m["entry"] is not None]
        result.append(dict(id=row["id"], **roi_comparison(original, frame, scale, [x,y,cw,ch],
                                                           truth, model, threshold)))
        # Post-hoc coverage diagnostic on a fixed grid. Each known contact belongs to one cell.
        for col, line in sorted({(int(p[0]//cw), int(p[1]//ch)) for p in truth}):
            x, y = col*cw, line*ch
            rect = [x, y, min(cw,w-x), min(ch,h-y)]
            annotated_tiles.append(dict(id=row["id"], **roi_comparison(
                original, frame, scale, rect, truth, model, threshold)))
    return dict(regression={k: summary(v) for k,v in full.items()}, central_roi=result,
                tiles_selected_by_reference=annotated_tiles)


if __name__ == "__main__":
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("synthetic", "public", "checkpoint", "policy", "output"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    if fingerprint(args.policy) != POLICY_SHA256:
        parser.error("Expected the frozen policy")
    threshold = json.loads(args.policy.read_text())["thresholds"]["native"]
    torch.set_num_threads(2)
    cv.setNumThreads(1)
    model = frozen(args.checkpoint)
    write_json(args.output, dict(model=MODEL_SHA256, policy=POLICY_SHA256,
                                 synthetic=synth(args.synthetic, model, threshold),
                                 public=resolution(args.public, model, threshold)))
