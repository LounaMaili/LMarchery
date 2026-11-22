"""Measure saved development peaks. No model or test inputs are required."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from zipfile import ZipFile

import numpy as np

from benchmark import maximum_matches


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def save(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def analyze(row, peaks):
    size = np.asarray(row["size"])
    scale = np.rint(size * min(1., 704 / max(size))) / size
    marks = {i: m["entry"] for i, m in enumerate(row["marks"], 1) if m["entry"] is not None}
    ids = list(marks)
    truth = np.array(list(marks.values())).reshape(-1, 2) * scale
    points = np.array([xy for xy, _ in peaks]).reshape(-1, 2) * scale
    scores = [s for _, s in peaks]
    # Match in the original proposal order used in R9, even when scored peaks tie.
    def matches(indices):
        pairs = maximum_matches(truth, points[indices], 10)
        return {indices[j]: ids[i] for i, j in pairs}

    full = matches(list(range(len(peaks))))
    at050 = matches([i for i, score in enumerate(scores) if score >= .5])
    distances = np.linalg.norm(points[:, None, :] - truth[None, :, :], axis=2)
    eligible = [[ids[k] for k in np.flatnonzero(dist <= 10)] for dist in distances]
    ranks = {score: 1 + sum(s > score for s in scores) for score in set(scores)}
    first = max((scores[j] for j, options in enumerate(eligible) if options), default=None)
    entries = []
    order = sorted(range(len(peaks)), key=scores.__getitem__, reverse=True)
    rows = []
    for j in order:
        xy, s = peaks[j]
        rank = ranks[s]
        before = first is None or s > first
        unknown = (not row["exhaustive"] or any(
            np.all(np.array(xy) >= lo) and np.all(np.array(xy) <= hi) for lo, hi in row["ignore"]))
        status = "apparié" if j in full else "non résolu" if unknown else "faux établi"
        rows.append(dict(xy=xy, rank=rank, score=s, before=before, examined=before or rank<=3,
                         eligible=eligible[j], match=full.get(j), match050=at050.get(j), reference=status,
                         reference050=("apparié" if j in at050 else "non résolu" if unknown else "faux établi")
                         if s >= .5 else None))
    for i, m in enumerate(row["marks"], 1):
        candidates = [p for p in rows if i in p["eligible"]]
        winner = next((p for p in candidates if p["match"] == i), None)
        entries.append(dict(id=i, entry=m["entry"], candidates=len(candidates),
                            best_rank=candidates[0]["rank"] if candidates else None,
                            best_score=candidates[0]["score"] if candidates else None,
                            matched_rank=winner["rank"] if winner else None,
                            matched_score=winner["score"] if winner else None))
    prefix = {str(k): len(matches([i for i, score in enumerate(scores)
                                 if ranks[score] <= k]))
              for k in (1, 3, 10, 24, 48, 72, 96)}
    return dict(prefix=prefix, first_score=first, first_rank=next((p["rank"] for p in rows if p["eligible"]), None),
                points=rows, entries=entries)


def build(r9, output):
    lab = Path(__file__).resolve().parents[1]
    manifest = r9/"data/r9/dev.json"
    assert digest(manifest) == "dd183e14d3d97cbebf595b6fb70a6b4f4c39bda0567cab01210d7862b787e862"
    dev = read(manifest)
    assert len(dev)==12 and all(r["split"]=="dev" for r in dev)
    archive = r9/"artifacts/r9/experiences.zip"
    inventory = read(lab/"r9/inventaire-local.json")["fichiers"]
    assert digest(archive) == next(item["sha256"] for item in inventory if item["nom"]==archive.name)
    summary = read(lab/"r9/mesures.json")
    with ZipFile(archive) as capsule:
        trials = {}
        inputs = {}
        for name in summary["runs"]:
            member = f"train/{name}-dev-peaks.json"
            buf = capsule.read(member)
            inputs[member] = dict(octets=len(buf), sha256=sha256(buf).hexdigest())
            trials[name] = json.loads(buf)
            assert set(trials[name]) == {r["id"] for r in dev}
    save(output/"inputs.json",dict(manifest_sha256=digest(manifest), archive_sha256=digest(archive), members=inputs))
    for row in dev:
        results = {}
        positions = set()
        for name, trial in trials.items():
            result = analyze(row, trial[row["id"]])
            published = read(lab/f"r9/train/{name}.json")["reports"]["dev"]["at050"]["photos"]
            counts = next(v for v in published if v["id"] == row["id"])
            assert sum(p["score"]>=.5 for p in result["points"]) == counts["proposals"]
            assert sum(p["match050"] is not None for p in result["points"]) == counts["matched"]
            positions.update(tuple(p["xy"]) for p in result["points"] if p["examined"])
            results[name] = result
        # Stable visual ID within a photograph, shared only at identical coordinates.
        positions = sorted(positions, key=lambda xy: (xy[1],xy[0]))
        position_ids = {xy: i for i,xy in enumerate(positions,1)}
        for result in results.values():
            for p in result["points"]:
                p["spot"] = position_ids.get(tuple(p["xy"]))
        save(output/(row["id"]+".json"), dict(id=row["id"],group=row["group"],positions=positions,runs=results))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("r9",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    build(args.r9,args.output)
