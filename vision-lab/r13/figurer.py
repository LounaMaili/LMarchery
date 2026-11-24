"""Tracer les valeurs publiques avec Matplotlib, sans modèle ni photographie."""
import argparse
import json
from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np

COLORS={'H':'#465269','A':'#1b6489','B':'#ae471f','O':'#af4322','F':'#29658a'}


def render(data,out):
    out.mkdir(parents=True,exist_ok=True)
    sources=sorted(data.glob('*-*.json'))
    names=[p.stem for p in sources]
    rows=[json.loads(p.read_text()) for p in sources]
    ids=[n.replace('ordinary-','Ord. ').replace('targeted-','Cib. ') for n in names]
    fig,axes=plt.subplots(1,3,figsize=(13,4),layout='constrained')
    for ax,split,title in zip(axes[:2],('historique','nouveau'),('12 photos · 40 axes','5 photos · 15 axes')):
        for j,m in enumerate(('A','B')):
            ax.bar(np.arange(6)+(j-.5)*.34,[r[split]['axes'][j] for r in rows],.34,color=COLORS[m],label=m)
        ax.set_title(title);ax.set_ylabel('Axes retrouvés, critère strict')
    for j,m in enumerate(('A','B')):
        axes[2].bar(np.arange(6)+(j-.5)*.34,[sum(r[s]['parasites'][j] for s in r) for r in rows],.34,color=COLORS[m],label=m)
    axes[2].set_title('5 témoins vides réunis');axes[2].set_ylabel('Segments parasites')
    for ax in axes:
        ax.set_xticks(range(6),ids,rotation=38,ha='right')
        ax.legend(title='Extraction',loc='upper left',ncol=2);ax.margins(y=.25)
    fig.savefig(out/'axes.png',dpi=135);plt.close(fig)
    fig,axes=plt.subplots(2,3,figsize=(12,6),layout='constrained',sharex=True,sharey=True)
    for ax,row,title in zip(axes.flat,rows,ids):
        for k in ('H','A','B'):
            pts=row['nouveau']['courbes'][k]
            ax.plot(range(len(pts)),[p['ensemble']['matched'] for p in pts],'.-',color=COLORS[k],label=k)
        ax.axvline(1,linewidth=.8,linestyle=':',color='gray');ax.set_title(title);ax.set_ylim(-.4,11.4)
        ax.set_xticks(range(7),['0','¼','½','1','2','4','8'])
    axes[0,0].legend();fig.supylabel('Entrées retrouvées sur 11');fig.supxlabel('Budget local de non-appariées par photo · catégories')
    fig.savefig(out/'rappel.png',dpi=135);plt.close(fig)
    fig,axes=plt.subplots(2,3,figsize=(12,6),layout='constrained',sharex=True,sharey=True)
    for ax,row,title in zip(axes.flat,rows,ids):
        for prefix,label in [('O','Bout proche'),('F','Bout éloigné')]:
            costs=row['nouveau']['couts']
            ax.plot([0,20,40],[costs['O0'],costs[prefix+'20'],costs[prefix+'40']],'.-',label=label,color=COLORS[prefix])
        ax.set_title(title);ax.set_xticks([0,20,40])
    axes[0,0].legend();fig.supylabel('Non-appariées au premier vrai contact · cinq photos R12')
    fig.supxlabel('Longueur retirée du trait annoté (px au repère R12)')
    fig.savefig(out/'ablation.png',dpi=135);plt.close(fig)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('data',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    render(a.data,a.out)
