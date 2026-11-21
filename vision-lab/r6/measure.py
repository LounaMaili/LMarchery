"""Paired decoder comparisons in the oriented working coordinate system."""
from collections import Counter, defaultdict

import numpy as np

from benchmark import maximum_matches
from r3.evaluate import row_metrics


def points(entries, threshold):
    return [item.point for item in entries if item.confidence >= threshold]


def observe(row, candidates, tubes, scale):
    result = row_metrics(row, candidates, tubes, scale)
    truth = [np.asarray(mark["entry"])*scale for mark in row["arrows"] if mark["entry"] is not None]
    result.update(group=row["source_group"], scenario=row["scenario"], complete=row["complete"],
                  present=len(row["arrows"]), close={str(d): len(maximum_matches(truth, candidates, d))
                                                     for d in (3, 5, 10)})
    return result


def totals(rows):
    def total(key):
        return sum(row[key] for row in rows)
    missed = total("proposals") - total("matched")
    full = [r for r in rows if r["complete"]]
    errors = [e for r in rows for e in r["errors_px"]]
    reasons = Counter()
    for r in rows:
        reasons.update(r["unassigned"])
    proposed_full = sum(r["proposals"] for r in full)
    groups = defaultdict(lambda: np.zeros(2))
    for r in rows:
        groups[r["group"]] += [r["matched"], r["known"]]
    counts = np.array(list(groups.values()))
    rng = np.random.default_rng(6100)
    draws = counts[rng.integers(len(counts), size=(1000, len(counts)))].sum(axis=1)
    positive = draws[draws[:, 1] > 0]
    intervals = np.percentile(positive[:, 0]/positive[:, 1], [2.5, 97.5]).tolist() if len(positive) else None
    return dict(photos=len(rows), groups=len(groups), present=total("present"),
                axes_eligible=total("tubes_known"), presence_matched=total("tubes_matched"),
                known=total("known"), matched=total("matched"), proposals=total("proposals"),
                by_tolerance={str(d): sum(r["close"][str(d)] for r in rows) for d in (3,5,10)},
                recall_ci95=intervals, precision_complete=(sum(r["matched"] for r in full)/proposed_full
                                                          if proposed_full else None),
                false_per_photo=reasons["false"]/len(rows),
                unresolved_per_photo=(missed-reasons["false"])/len(rows),
                unmatched_per_photo=missed/len(rows), unassigned=dict(reasons),
                errors_original_px_p50_p95=np.percentile(errors, [50,95]).tolist() if errors else None,
                silent=sum(not r["proposals"] for r in rows), empty=sum(r["negative"] for r in rows),
                empty_with_points=sum(r["negative"] and r["proposals"] > 0 for r in rows),
                processing_failures=0)


def summary(rows):
    return {"all": totals(rows), "scenarios": {s: totals([r for r in rows if r["scenario"] == s])
                                              for s in sorted({r["scenario"] for r in rows})},
            "by_group": {g: [sum(r["matched"] for r in rows if r["group"] == g),
                             sum(r["known"] for r in rows if r["group"] == g)]
                         for g in sorted({r["group"] for r in rows})}}
