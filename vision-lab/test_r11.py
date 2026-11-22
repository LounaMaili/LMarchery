"""Recheck the published coverage and decision using only the Git checkout."""
import hashlib
import json
from pathlib import Path
import unittest

import numpy as np

from benchmark import maximum_matches
from r10.tableaux import RUNS


LAB = Path(__file__).resolve().parent


def read(path):
    return json.loads((LAB/path).read_text())


class PublishedCoverageTests(unittest.TestCase):
    def test_coordinates_reproduce_both_criteria_and_the_archived_prefix(self):
        sources = read("r10/mesures.json")
        archives = read("r10/entrees.json")["members"]
        audit = read("r11/parite.json")
        original_marks = {}
        for photo in sources:
            trials = read(f"r10/releves/{photo}.json")["essais"]
            original_marks[photo] = [e for e in trials[RUNS[0]]["entrees"] if e["entry"] is not None]

        recovered = {}
        qualifying = []
        for name in RUNS:
            peaks = read(f"r11/pics/{name}.json")
            data = read(f"r11/mesures/{name}.json")
            short = {k:v[:96] for k,v in peaks.items()}
            blob = (json.dumps(short, indent=2, ensure_ascii=False) + "\n").encode()
            self.assertEqual(hashlib.sha256(blob).hexdigest(), archives[f"train/{name}-dev-peaks.json"]["sha256"])
            improvements = []
            for photo, rows in peaks.items():
                self.assertEqual(audit["essais"][name]["photos"][photo]["passages"], 1)
                marks = original_marks[photo]
                truth = np.asarray([e["entry"] for e in marks]).reshape(-1,2)
                size = np.asarray(data["96"]["photos"][photo]["taille_image"])
                scale = np.rint(size*min(1,704/max(size)))/size
                counts = []
                for cap in (96,192):
                    result = data[str(cap)]["photos"][photo]
                    coords = np.asarray([p for p,score in rows[:cap]]).reshape(-1,2)
                    self.assertEqual(result["candidats"], len(coords))
                    matches = dict(maximum_matches(truth*scale, coords*scale, 10))
                    counts.append(len(matches))
                    self.assertEqual(counts[-1], result["mesures"]["tous"]["matched"])
                    for j,e in enumerate(marks):
                        near = np.flatnonzero(np.linalg.norm(coords*scale-truth[j]*scale,axis=1)<=10)
                        record = result["contacts"][str(e["id"])]
                        self.assertEqual(record["voisins"], len(near))
                        self.assertEqual(record["rang"], int(near[0])+1 if len(near) else None)
                        self.assertEqual(record["score"], rows[near[0]][1] if len(near) else None)
                        self.assertEqual(record["rang_attribue"], matches[j]+1 if j in matches else None)
                        if e["id"] in sources[photo]["sans_candidat_six_essais"] and len(near):
                            self.assertEqual(cap,192)
                            recovered.setdefault((photo,e["id"]), []).append(name)
                improvements.append(counts[1]-counts[0])
            if sum(improvements)>=4 and sum(v>0 for v in improvements)>=2:
                qualifying.append(name)
        missing = read("r11/mesures/dix-entrees.json")
        self.assertEqual({(r["photo"],r["entree"]) for r in missing},
                         {(p,i) for p,s in sources.items() for i in s["sans_candidat_six_essais"]})
        for row in missing:
            self.assertEqual(row["essais_avec_voisin"],recovered.get((row["photo"],row["entree"]),[]))
        decision = read("r11/mesures/decision.json")
        groups = {sources[photo]["provenance"] for photo,_ in recovered}
        self.assertEqual(qualifying, decision["checkpoints_qualifies"])
        self.assertEqual(len(recovered),decision["entrees_recuperees"])
        self.assertEqual(sorted(groups),decision["provenances_recuperees"])
        self.assertEqual(decision["critere_1"],len(qualifying)>=4)
        self.assertEqual(decision["critere_2"],len(recovered)>=2 and len(groups)>=2)
        self.assertEqual(decision["limite_materielle"],decision["critere_1"] and decision["critere_2"])
