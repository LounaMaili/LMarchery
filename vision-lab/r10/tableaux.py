"""Publish compact development-only records and per-acquisition tables."""
import argparse
from collections import Counter
import json
from pathlib import Path


LABELS = {"F":"fond ou support", "M":"motif ou marquage", "H":"trou ou déchirure",
          "O":"ombre", "T":"corps de tube", "E":"extrémité opposée", "I":"aspect indéterminé"}
RUNS = [f"{arm}-{seed}" for arm in ("ordinary","targeted") for seed in (9301,9302,9303)]
COLUMNS = ["rang","score","x","y","avant_premier","examen","position_lue","aspect","entrees_admissibles",
           "entree_appariee","reference_96","entree_appariee_050","reference_050"]


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, allow_nan=False)


def save(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False)+"\n")


def count_aspects(points):
    return dict(Counter(p["aspect"] for p in points))


def compact_records(path, raw, results):
    header = {"provenance":raw["group"], "photo":raw["id"], "colonnes":COLUMNS, "aspects":LABELS}
    with path.open("w") as out:
        out.write("{\n"+",\n".join(f'  {dump(k)}: {dump(v)}' for k,v in header.items())+',\n  "essais": {\n')
        for i,name in enumerate(RUNS):
            result,rows = results[name]
            out.write(f'    {dump(name)}: {{\n      "entrees": [\n')
            out.write(",\n".join("        "+dump(e) for e in result["entries"]))
            out.write('\n      ],\n      "propositions": [\n')
            out.write(",\n".join("        "+dump(row) for row in rows))
            out.write("\n      ]\n    }"+("," if i+1<len(RUNS) else "")+"\n")
        out.write("  }\n}\n")


def publish(numeric, output):
    lab = Path(__file__).resolve().parents[1]
    readings = json.loads((lab/"r10/lectures.json").read_text())
    sources = json.loads((lab/"r9/sources.json").read_text())["photos"]
    reports = {n: json.loads((lab/f"r9/train/{n}.json").read_text())["reports"]["dev"] for n in RUNS}
    details = output/"releves"
    details.mkdir(parents=True,exist_ok=True)
    summary = {}
    table = ["# Tableaux du développement R9", "",
             "Chaque ligne décrit une photo et un essai au pas 96. Le rang est local à la photo. "
             "« Avant » compte les propositions strictement mieux classées que le premier point appariable, "
             "ou la liste entière quand aucun candidat ne convient. La couverture correspond au nombre maximal "
             "d’appariements un à un sur les 96 points. Le plafond porte sur ces coordonnées et cette tolérance.", "",
             "Les scores des tableaux sont arrondis à quatre décimales. Les relevés liés donnent leur valeur "
             "enregistrée, les coordonnées, les ex æquo, les aspects visuels et les statuts de référence. "
             "Les identifiants des entrées sont numérotés à partir de 1 dans l’ordre R9. "
             "Une référence incertaine conserve une coordonnée nulle.", ""]
    for source in sorted((s for s in sources if s["split"]=="dev"),key=lambda s:s["provenance"].casefold()):
        photo = source["id"]
        raw = json.loads((numeric/(photo+".json")).read_text())
        codes = readings[photo]["lecture"]
        assert len(codes)==len(raw["positions"]) and set(codes)<=set(LABELS)
        photo_summary = dict(id=photo,provenance=source["provenance"],supports=source["supports"],
                             visibles=source["contacts_visibles"],incertains=source["entrees_incertaines"],
                             exhaustif=source["inventaire_complet"],vide=source["temoin_vide"],
                             positions_lues=len(codes),essais={})
        results = {}
        covered_union = set()
        table += [f'## {raw["group"]}', "", f'{source["contacts_visibles"]} entrées visibles, '
                  f'{source["entrees_incertaines"]} incertaines. '
                  f'[Tous les points et entrées](releves/{photo}.json) · [Planche](planches/{photo}.png)',"",
                  "| Bras et graine | Rang 1 · score · aspect · référence¹ | Avant | Premier appariable · rang / score | Couverture | Visibles sans aucun candidat |",
                  "|---|---|---:|---|---:|---:|"]
        for name in RUNS:
            result=raw["runs"][name]
            pts=result["points"]
            for p in pts:
                p["aspect"] = codes[p["spot"]-1] if p["spot"] else None
                assert not p["examined"] or p["aspect"]
            entries=result["entries"]
            visible=[e for e in entries if e["entry"] is not None]
            covered_union.update(e["id"] for e in visible if e["candidates"])
            examined=[p for p in pts if p["examined"]]
            before=[p for p in pts if p["before"]]
            top=[p for p in pts if p["rank"]<=3]
            linked=Counter(i for p in pts for i in p["eligible"])
            dup=[p for p in pts if p["eligible"] and p["match"] is None]
            counts050=next(x for x in reports[name]["at050"]["photos"] if x["id"]==photo)
            locked=next(x for x in reports[name]["locked"]["photos"] if x["id"]==photo)
            assert Counter(p["reference050"] for p in pts if p["score"]>=.5)==Counter(
                {"apparié":counts050["matched"],"non résolu":counts050["unresolved"],"faux établi":counts050["false"]})
            metrics=dict(premier_rang=result["first_rank"],premier_score=result["first_score"],
                         avant=len(before),examinees=len(examined),aspects_avant=count_aspects(before),
                         aspects_examines=count_aspects(examined),aspects_top3=count_aspects(top),
                         references_avant=dict(Counter(p["reference"] for p in before)),
                         references_96=dict(Counter(p["reference"] for p in pts)),
                         rangs_cumules=result["prefix"],entrees_avec_plusieurs_candidats=sum(v>1 for v in linked.values()),
                         candidats_redondants_non_apparies=len(dup),candidats_partages=sum(len(p["eligible"])>1 for p in pts),
                         sans_candidat=[e["id"] for e in visible if not e["candidates"]],
                         contacts_avec_candidat=sum(e["candidates"]>0 for e in visible),
                         seuil_050=counts050,seuil_decision_2=locked)
            photo_summary["essais"][name]=metrics
            rows=[[p["rank"],p["score"],*p["xy"],p["before"],p["examined"],p["spot"],p["aspect"],
                   p["eligible"],p["match"],p["reference"],p["match050"],p["reference050"]] for p in pts]
            results[name]=(result,rows)
            best=pts[0]
            first=(f'{result["first_rank"]} / {result["first_score"]:.4f}' if result["first_score"] is not None else "Aucun")
            coverage = f'{result["prefix"]["96"]}/{len(visible)}' if visible else "— (0 visible)"
            table.append(f'| {name} | {best["score"]:.4f} · {LABELS[best["aspect"]]} · {best["reference"]} | '
                         f'{len(before)} | {first} | {coverage} | {len(metrics["sans_candidat"])} |')
        photo_summary["sans_candidat_six_essais"]=[e["id"] for e in visible if e["id"] not in covered_union]
        summary[photo]=photo_summary
        compact_records(details/(photo+".json"),raw,results)
        table.append("")
    table+=["¹ Statut au terme de l’appariement de la liste complète. À un seuil intermédiaire, un candidat "
            "peut changer de partenaire. Les points avant le premier appariable sont géométriquement hors tolérance "
            "de toutes les entrées visibles. Les témoins vides ont un dénominateur nul pour le rappel.",""]
    (output/"tableaux.md").write_text("\n".join(table))
    save(output/"mesures.json",summary)

    totals={"avant":Counter(),"examinees":Counter(),"top3":Counter(),"references_avant":Counter()}
    per_run={}
    for name in RUNS:
        cells=[row["essais"][name] for row in summary.values()]
        for cell in cells:
            for key, field in (("avant","aspects_avant"), ("examinees","aspects_examines"), ("top3","aspects_top3")):
                totals[key].update(cell[field])
            totals["references_avant"].update(cell["references_avant"])
        per_run[name]=dict(listes_avec_candidat=sum(c["premier_rang"] is not None for c in cells),
                           apparies_96=sum(c["rangs_cumules"]["96"] for c in cells),
                           apparies_3=sum(c["rangs_cumules"]["3"] for c in cells),
                           sans_candidat=sum(len(c["sans_candidat"]) for c in cells),
                           avant=sum(c["avant"] for c in cells),
                           non_resolus_avant=sum(c["references_avant"].get("non résolu",0) for c in cells),
                           pics_redondants=sum(c["candidats_redondants_non_apparies"] for c in cells),
                           entrees_plusieurs_voisins=sum(c["entrees_avec_plusieurs_candidats"] for c in cells),
                           pics_partages=sum(c["candidats_partages"] for c in cells))
    save(output/"bilan.json",dict(photos=len(summary),listes=len(RUNS)*len(summary),
         propositions=sum(sum(c["references_96"].values()) for s in summary.values() for c in s["essais"].values()),
         positions_visuelles=sum(s["positions_lues"] for s in summary.values()),
         visibles_sans_candidat_six_essais=sum(len(s["sans_candidat_six_essais"]) for s in summary.values()),
         aspects=LABELS,comptages=totals,essais=per_run))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("numeric",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    publish(args.numeric,args.output)
