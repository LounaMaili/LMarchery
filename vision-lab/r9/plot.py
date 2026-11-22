"""Draw the six whole-photo learning curves and one threshold figure per provenance."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import PercentFormatter

PALETTE = {"ordinary":"#166190","targeted":"#ad451a"}
STYLES = {9301:"-",9302:"--",9303:":"}


def style(ax):
    ax.grid(axis="y", alpha=.24)
    ax.set_axisbelow(True)
    ax.spines[["top","right"]].set_visible(False)


def legend(fig):
    lines = [Line2D([0],[0],color=c,lw=2.2) for c in PALETTE.values()]
    lines += [Line2D([0],[0],color="#454545",ls=s) for s in STYLES.values()]
    fig.legend(lines,["Ordinaire","Ciblé","9301","9302","9303"],ncol=5,loc="lower center",frameon=False)


def draw(out):
    data = json.loads((out/"mesures.json").read_text())
    folder = out/"courbes"
    folder.mkdir(exist_ok=True)
    fig, axes = plt.subplots(2,2,figsize=(11,7),sharex=True)
    reports = {}
    for name, run in data["runs"].items():
        arm, s = name.split("-")
        args = dict(color=PALETTE[arm],ls=STYLES[int(s)],lw=1.8,alpha=.92)
        steps = [p["step"] for p in run["learning"]]
        for col,key in enumerate(("train050","dev050")):
            rows = [p[key] for p in run["learning"]]
            axes[0,col].plot(steps,[r["matched"]/r["known"] for r in rows],**args)
            axes[1,col].plot(steps,[r["unmatched"]/r["photos"] for r in rows],**args)
        reports[name] = json.loads((out/"train"/(name+".json")).read_text())["reports"]["dev"]["curve"]
    for col,title in enumerate((f"Apprentissage · {data['train']['photos']} photos",f"Développement · {data['dev']['photos']} photos")):
        axes[0,col].set_title(title)
        axes[0,col].yaxis.set_major_formatter(PercentFormatter(1))
        axes[0,col].set_ylim(-.03,1.05)
        axes[1,col].set_xlabel("Mises à jour")
        axes[1,col].set_xticks(steps)
        for ax in axes[:,col]:
            style(ax)
    axes[0,0].set_ylabel("Rappel des contacts visibles")
    axes[1,0].set_ylabel("Non-appariées par photo")
    fig.suptitle("R9 · Mesures sur photo entière au seuil 0,50",fontsize=16)
    legend(fig)
    fig.tight_layout(rect=(0,.065,1,.96))
    fig.savefig(out/"apprentissage.png",dpi=155)
    plt.close(fig)
    groups = list(next(iter(reports.values()))[0]["per_group"])
    for i,group in enumerate([None]+groups):
        fig,axes=plt.subplots(2,1,figsize=(8.5,6),sharex=True)
        for name,points in reports.items():
            arm,s=name.split("-")
            pts=[p for p in points if p["t"]<=1]
            rows=[p["all"] if group is None else p["per_group"][group] for p in pts]
            x=[p["t"] for p in pts]
            args=dict(color=PALETTE[arm],ls=STYLES[int(s)],lw=1.6)
            if rows[0]["known"]:
                axes[0].plot(x,[r["matched"]/r["known"] for r in rows],**args)
            axes[1].plot(x,[r["unmatched"]/r["photos"] for r in rows],**args)
        if not rows[0]["known"]:
            axes[0].text(.5,.45,"Aucun contact visible de référence",ha="center",transform=axes[0].transAxes)
        axes[0].set_ylim(-.04,1.05)
        axes[0].yaxis.set_major_formatter(PercentFormatter(1))
        axes[0].set_ylabel("Rappel")
        axes[1].set_ylabel("Non-appariées par photo")
        axes[1].set_yscale("symlog",linthresh=1)
        axes[1].axhline(.25,color="#666",lw=.8,ls=":")
        axes[1].set_xlabel("Seuil de confiance")
        axes[1].set_xlim(.045,1)
        for ax in axes:
            style(ax)
        fig.suptitle(f"{group or 'Développement entier'} · {rows[0]['photos']} photo(s), {rows[0]['known']} contacts visibles")
        legend(fig)
        fig.tight_layout(rect=(0,.07,1,.96))
        fig.savefig(folder/f"{i:02}.png",dpi=140)
        plt.close(fig)
    with ZipFile(out/"courbes-par-provenance.zip","w",ZIP_DEFLATED) as z:
        for p in sorted(folder.glob("*.png")):
            z.write(p,p.name)
        z.write(out/"apprentissage.png","apprentissage.png")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("out",type=Path)
    draw(p.parse_args().out)
