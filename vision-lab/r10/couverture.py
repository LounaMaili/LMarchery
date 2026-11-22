"""Plot maximal matched contacts by saved rank, before score thresholding."""
import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt


def plot(path, out):
    data=json.loads(path.read_text())
    ranks=[1,3,10,24,48,72,96]
    totals={}
    fig,axes=plt.subplots(1,2,figsize=(9.5,4.4),sharey=True)
    for ax,arm,title in zip(axes,("ordinary","targeted"),("Tirage ordinaire","Tirage ciblé"),strict=True):
        for seed,color,style in zip((9301,9302,9303),("#146e83","#9d4b20","#67459a"),("-","--",":"),strict=True):
            name=f"{arm}-{seed}"
            counts=[sum(photo["essais"][name]["rangs_cumules"][str(k)] for photo in data.values()) for k in ranks]
            totals[name]=counts
            ax.plot(ranks,counts,ls=style,marker="o",lw=2,color=color,label=str(seed))
        ax.set(xlabel="Rang maximal conservé par photo",title=title,ylim=(-1,36),xticks=[1,10,24,48,72,96])
        ax.grid(alpha=.22)
        ax.axhline(35,color="#536167",lw=.8,ls=":")
        ax.legend(frameon=False)
    axes[0].set_ylabel("Entrées appariées sur 35 visibles")
    fig.suptitle("Développement R9 · couverture des coordonnées sauvegardées")
    fig.tight_layout()
    fig.savefig(out/"couverture.png",dpi=140)
    plt.close(fig)
    (out/"couverture.json").write_text(json.dumps(dict(rangs=ranks,visibles=35,essais=totals),indent=2)+"\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesures",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    plot(args.mesures,args.output)
