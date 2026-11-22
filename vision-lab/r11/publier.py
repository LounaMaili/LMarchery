"""Render the R11 tables and figures from the versioned numerical results."""
import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm
import numpy as np

from r10.tableaux import RUNS


def read(path):
    return json.loads(path.read_text())


def pair(a,b):
    return f"{a} → {b}"


def render(results, out):
    figures = out/"figures"
    figures.mkdir(parents=True,exist_ok=True)
    data = {n:read(results/(n+".json")) for n in RUNS}
    photos = sorted(data[RUNS[0]]["96"]["photos"], key=lambda p:data[RUNS[0]]["96"]["photos"][p]["provenance"].casefold())
    missing = read(results/"dix-entrees.json")
    values = {"checkpoints":RUNS, "total_visible":35, "couverture":{}, "provenances":[]}
    for name,t in data.items():
        values["couverture"][name] = [t[cap]["strates"]["tous"]["all"]["matched"] for cap in ("96","192")]
    table = ["# Tableaux R11", "",
             "Les colonnes suivent l’ordre ordinaire 9301, 9302, 9303, puis ciblé 9301, 9302, 9303. "
             "Chaque cellule de couverture donne les contacts appariés à 96 puis à 192. Le rang commence à 1 "
             "dans la liste de la photo, avec l’ordre secondaire du décodeur pour les ex æquo.", "",
             "## Couverture maximale par provenance", "",
             "| Provenance | Visibles | Ord. 9301 | Ord. 9302 | Ord. 9303 | Cib. 9301 | Cib. 9302 | Cib. 9303 |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for photo in photos:
        ref=data[RUNS[0]]["96"]["photos"][photo]
        known=ref["mesures"]["tous"]["known"]
        pairs=[[data[n][cap]["photos"][photo]["mesures"]["tous"]["matched"] for cap in ("96","192")] for n in RUNS]
        values["provenances"].append(dict(id=photo,nom=ref["provenance"],visibles=known,apparies=pairs))
        table.append("| "+" | ".join([ref["provenance"],str(known)]+[pair(*v) for v in pairs])+" |")
    table+=["","Une ligne à zéro visible a un rappel indéfini. La photo d’April L. Sanders comporte une entrée "
            "occultée. Les trois témoins vides sont les photos d’Iain Cuthbertson, de russellstreet et de TimWilson.","",
            "## Effectifs et appariements de chaque liste", "",
            "Chaque ligne couvre une photo entière. « Faux » et « non rés. » partitionnent les propositions "
            "non appariées sur la liste complète. Les identifiants de la dernière colonne sont les entrées "
            "qui obtiennent leur premier voisin dans cet essai au-delà du rang 96. Les relevés JSON donnent "
            "pour chaque entrée son meilleur rang, son score et le rang de son candidat attribué.", ""]
    for n,t in data.items():
        table += [f"### {n}","","[Relevé numérique](mesures/"+n+".json) · [Coordonnées et scores](pics/"+n+".json)","",
                  "| Provenance | Candidats | Appariés | Faux | Non rés. | Entrées nouvellement admissibles |",
                  "|---|---:|---:|---:|---:|---|"]
        for photo in photos:
            a,b=[t[cap]["photos"][photo] for cap in ("96","192")]
            cols=[pair(a["mesures"]["tous"][key],b["mesures"]["tous"][key]) for key in ("proposals","matched","false","unresolved")]
            ids=t["comparaison"]["nouvelles_entrees_admissibles"][photo]
            table.append("| "+" | ".join([a["provenance"]]+cols+[", ".join(ids) or "—"])+" |")
        table += ["","| Sélection | Strate | Photos | Visibles | Candidats | Appariés | Faux | Non rés. | Silencieuses | Vides déclenchés |",
                  "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for selection,label in [("tous","Liste entière"),("a050","0,50"),("decision_r9","Seuil R9")]:
            for key,group in [("all","Tout"),("grouped","Groupées"),("worn","Usées"),("empty","Témoins vides")]:
                a,b=[t[cap]["strates"][selection][key] for cap in ("96","192")]
                cols=[pair(a[k],b[k]) for k in ("proposals","matched","false","unresolved","silent","empty_with_points")]
                table.append("| "+" | ".join([label,group,str(a["photos"]),str(a["known"])]+cols)+" |")
        table.append("")
    table+=["## Devenir des dix entrées absentes à 96","","La cellule indique le rang du meilleur voisin à 192 "
            "suivi de son score arrondi. Un tiret signifie qu’aucun candidat n’est appariable à cette entrée. "
            "Le score exact, le nombre de voisins et le rang attribué sont consultables dans le [relevé des dix entrées](mesures/dix-entrees.json).","",
            "| Provenance | Entrée nº | Ord. 9301 | Ord. 9302 | Ord. 9303 | Cib. 9301 | Cib. 9302 | Cib. 9303 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for row in missing:
        cells=[]
        for n in RUNS:
            x=row["resultats"][n]
            cells.append(f'{x["rang"]} / {x["score"]:.4f}' if x["voisins"] else "—")
        table.append("| "+" | ".join([row["provenance"],str(row["entree"])]+cells)+" |")
    (out/"tableaux.md").write_text("\n".join(table)+"\n")
    (figures/"valeurs.json").write_text(json.dumps(values,indent=2,ensure_ascii=False)+"\n")

    fig,ax=plt.subplots(figsize=(8,4.5),layout="constrained")
    for i,n in enumerate(RUNS):
        a,b=values["couverture"][n]
        ax.plot([a,b],[i,i],color="#768489",lw=2)
        ax.scatter(a,i,c="#275f75",s=67,label="96 candidats" if i==0 else None,zorder=3)
        ax.scatter(b,i,c="#ac4826",marker="D",s=55,label="192 candidats" if i==0 else None,zorder=3)
        ax.text(a-.7,i,str(a),ha="right",va="center")
        ax.text(b+.7,i,str(b),va="center")
    ax.set(yticks=range(6),yticklabels=[n.replace("ordinary","Ordinaire").replace("targeted","Ciblé") for n in RUNS],
           xlabel="Contacts appariés sur 35 visibles",xlim=(0,35),title="Couverture maximale sur les douze photos")
    ax.invert_yaxis()
    ax.grid(axis="x",alpha=.2)
    ax.legend(loc="lower left",frameon=False)
    fig.savefig(figures/"couverture.png",dpi=150)
    plt.close(fig)

    fig,ax=plt.subplots(figsize=(8,6.2),layout="constrained")
    matrix=np.array([[b-a if row["visibles"] else -1 for a,b in row["apparies"]] for row in values["provenances"]])
    highest=max(1,int(matrix.max()))
    palette=["#e2e4e4","#f4f7f6"]+[plt.cm.Blues(x) for x in np.linspace(.45,.89,highest)]
    ax.imshow(matrix,cmap=ListedColormap(palette),norm=BoundaryNorm(np.arange(-1.5,highest+1,1),len(palette)),
              aspect="auto",interpolation="nearest")
    for (i,j),v in np.ndenumerate(matrix):
        ax.text(j,i,"—" if v<0 else f"+{v}" if v else "0",ha="center",va="center",
                color="white" if v>=2 else "#10212c")
    ax.set(xticks=range(6),xticklabels=[s+"\n"+str(n) for s in ("Ord.","Cib.") for n in (9301,9302,9303)],
           yticks=range(len(photos)),yticklabels=[v["nom"] for v in values["provenances"]],
           title="Gain d’appariements par provenance, de 96 à 192",xlabel="—   Aucun contact visible de référence")
    fig.savefig(figures/"provenances.png",dpi=150)
    plt.close(fig)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesures",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    render(args.mesures,args.output)
