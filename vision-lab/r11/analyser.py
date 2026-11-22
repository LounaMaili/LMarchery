"""Measure the two published prefixes without running a model."""
import argparse
from hashlib import sha256
from pathlib import Path

import numpy as np

from benchmark import maximum_matches
from lab_data import fingerprint, write_json
from r8.experiment import measure
from r9.experiment import threshold
from r9.report import first_contact
from r10.diagnostic import read
from r10.tableaux import RUNS
from r11.comparer import r9_bytes


LAB = Path(__file__).resolve().parents[1]
CAPS = (96, 192)


def contact_records(row, peaks):
    visible = [(i, m["entry"]) for i, m in enumerate(row["marks"], 1) if m["entry"] is not None]
    size = np.array(row["size"])
    scale = np.rint(size * min(1, 704/max(size))) / size
    truth = np.array([xy for _,xy in visible]).reshape(-1, 2) * scale
    coords = np.array([p for p,_ in peaks]).reshape(-1, 2) * scale
    assigned = dict(maximum_matches(truth, coords, 10))
    records = {}
    for j, (ident, _) in enumerate(visible):
        neighbors = np.flatnonzero(np.linalg.norm(coords-truth[j], axis=1) <= 10)
        best = int(neighbors[0]) if len(neighbors) else None
        used = assigned.get(j)
        records[str(ident)] = dict(voisins=len(neighbors), rang=int(best+1) if best is not None else None,
                                   score=peaks[best][1] if best is not None else None,
                                   rang_attribue=int(used+1) if used is not None else None)
    return records


def counted(items, cutoff):
    result = measure(items, cutoff)
    empty = [r for r in result["photos"] if "empty" in r["flags"]]
    result["empty"] = {k:sum(r[k] for r in empty) for k in result["all"]}
    return result


def one_prefix(rows, peaks, cap, locked):
    items = [(r,peaks[r["id"]][:cap]) for r in rows]
    views = {"tous":counted(items,0.), "a050":counted(items,.5), "decision_r9":counted(items,locked)}
    strata = {view:{k:v[k] for k in ("all","grouped","worn","empty")} for view,v in views.items()}
    images = {}
    for index, (row, points) in enumerate(items):
        records = contact_records(row, points)
        if sum(p["rang_attribue"] is not None for p in records.values()) != views["tous"]["photos"][index]["matched"]:
            raise ValueError("Appariements incompatibles")
        images[row["id"]] = dict(provenance=row["group"], taille_image=row["size"], candidats=len(points), contacts=records,
                                 mesures={view:{k:v["photos"][index][k] for k in v["all"]} for view,v in views.items()})
    return dict(strates=strata, photos=images, seuil_r9=locked, seuil_selectionne=threshold(items),
                premier_contact_global=first_contact(rows,{r["id"]:p for r,p in items}))


def verdict(trials, missing):
    qualifying = [name for name,t in trials.items() if t["comparaison"]["gain"]>=4 and
                  len(t["comparaison"]["provenances_gagnantes"])>=2]
    recovered = [m for m in missing if m["essais_avec_voisin"]]
    groups = sorted({m["provenance"] for m in recovered})
    first = len(qualifying)>=4
    second = len(recovered)>=2 and len(groups)>=2
    return dict(checkpoints_qualifies=qualifying,critere_1=first,entrees_recuperees=len(recovered),
                provenances_recuperees=groups,critere_2=second,limite_materielle=first and second)


def analyze(manifest, run, out):
    if fingerprint(manifest)!=read(LAB/"r10/entrees.json")["manifest_sha256"]:
        raise ValueError("Manifeste différent du développement R9")
    rows = read(manifest)
    audit = read(run/"parite.json")
    sealed = read(LAB/"r10/entrees.json")["members"]
    r9 = read(LAB/"r9/mesures.json")["runs"]
    prior = read(LAB/"r10/mesures.json")
    if len(rows)!=12 or len({r["group"] for r in rows})!=12 or audit["appels_full_maps"]!=72:
        raise ValueError("Population ou budget différent du protocole")
    data = {}
    # Gate the entire contrast on independent byte reconstruction for all checkpoints.
    for name in RUNS:
        peaks = read(run/"pics"/(name+".json"))
        if set(peaks)!={r["id"] for r in rows} or any(
                not 96<=len(p)<=192 or p!=sorted(p,key=lambda v:(-v[1],v[0])) for p in peaks.values()):
            raise ValueError(f"Liste de candidats invalide pour {name}")
        short_bytes = r9_bytes({r["id"]:peaks[r["id"]][:96] for r in rows})
        expected = sealed[f"train/{name}-dev-peaks.json"]["sha256"]
        checks = audit["essais"][name]
        if (sha256(short_bytes).hexdigest()!=expected or not checks["fichier_identique"] or
                checks["checkpoint_sha256"]!=r9[name]["sha256"] or not all(
                    checks["photos"][r["id"]]["passages"]==1 and checks["photos"][r["id"]]["mesures"]==len(peaks[r["id"]])
                    for r in rows)):
            raise ValueError(f"Échec de la vérification R9 pour {name}")
        data[name] = peaks

    trials = {}
    for name,peaks in data.items():
        short, long = [one_prefix(rows, peaks, cap, r9[name]["threshold"]) for cap in CAPS]
        if short["premier_contact_global"]!=r9[name]["first_dev_contact"]:
            raise ValueError(f"Coût historique différent pour {name}")
        for label in ("a050","decision_r9"):
            expected = r9[name]["dev050"] if label=="a050" else r9[name]["devlocked"]["all"]
            if short["strates"][label]["all"]!=expected:
                raise ValueError(f"Mesure R9 non reproduite pour {name}/{label}")
        gains, new = {}, {}
        for row in rows:
            photo = row["id"]
            a, b = short["photos"][photo], long["photos"][photo]
            matched = a["mesures"]["tous"]["matched"]
            if matched!=prior[photo]["essais"][name]["rangs_cumules"]["96"]:
                raise ValueError(f"Couverture de référence différente pour {name}/{photo}")
            gains[row["group"]] = b["mesures"]["tous"]["matched"]-matched
            new[photo] = [i for i,p in b["contacts"].items() if p["voisins"] and not a["contacts"][i]["voisins"]]
        trials[name] = {"96":short,"192":long,"comparaison":dict(gain=sum(gains.values()),
                        gains_par_provenance=gains,provenances_gagnantes=[g for g,v in gains.items() if v>0],
                        nouvelles_entrees_admissibles=new)}
    missing = []
    for photo,source in prior.items():
        for ident in source["sans_candidat_six_essais"]:
            outcomes = {}
            for name in RUNS:
                if trials[name]["96"]["photos"][photo]["contacts"][str(ident)]["voisins"]:
                    raise ValueError("Statut historique d’une entrée absente différent")
                outcomes[name] = trials[name]["192"]["photos"][photo]["contacts"][str(ident)]
            missing.append(dict(photo=photo,provenance=source["provenance"],entree=ident,resultats=outcomes,
                                essais_avec_voisin=[n for n,v in outcomes.items() if v["voisins"]]))
    if len(missing)!=10:
        raise ValueError("Le lot des dix entrées a changé")
    out.mkdir(parents=True,exist_ok=True)
    for name,result in trials.items():
        write_json(out/(name+".json"), result)
    write_json(out/"dix-entrees.json",missing)
    write_json(out/"decision.json",verdict(trials,missing))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ("manifest","run","out"):
        parser.add_argument(key,type=Path)
    args=parser.parse_args()
    analyze(args.manifest,args.run,args.out)
