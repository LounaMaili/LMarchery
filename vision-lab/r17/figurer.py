"""Tracer les décisions de revue et la supervision disponible sur le train."""
import argparse
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from r14.mesurer import read


def render(out):
    review=read('r17/bilan.json')['par_ensemble']
    train=read('r17/effectifs-instances.json')['train']['total']
    fig,axes=plt.subplots(1,2,figsize=(12,4.35),gridspec_kw={'width_ratios':[1,1.1]})
    fig.subplots_adjust(left=.13,right=.975,top=.76,bottom=.24,wspace=.67)
    labels=[('positif_fit','Positifs du fit'),('exclu_ignore','Exclus du train'),('dev_strict','Développement')]
    palette=[('confirmee','Confirmé','#247668'),('erreur_demontree','Erreur d’association','#ba5935'),('indecidable','Indécidable','#777f92')]
    for i,(k,_) in enumerate(labels):
        start=0
        for status,_,color in palette:
            n=review[k][status]
            axes[0].barh(i,n,left=start,color=color,height=.53)
            if n: axes[0].text(start+n/2,i,str(n),color='white',ha='center',va='center',weight='bold')
            start+=n
    axes[0].set_yticks(range(3),[label for _,label in labels]);axes[0].invert_yaxis()
    axes[0].set(xlim=(0,12.4),xticks=[0,4,8,12],xlabel='Segments émis')
    right=[('instances_annotees','Instances annotées'),('entree_visible','Entrée visible'),('avec_masque','Masque'),
           ('avec_axe','Axe tracé'),('axe_et_entree_meme_vue','Axe + entrée'),('axe_masque_entree_meme_vue','Masque + axe + entrée')]
    for i,(k,_) in enumerate(right):
        n=train[k];axes[1].barh(i,n,color='#496a88',height=.53);axes[1].text(n+2,i,str(n),va='center',fontsize=9)
    axes[1].set_yticks(range(len(right)),[label for _,label in right]);axes[1].invert_yaxis()
    axes[1].set(xlim=(0,185),xticks=[0,40,80,120,160],xlabel='Flèches physiques annotées')
    for ax,title in zip(axes,['Relecture des 28 cas','Annotations sur la même vue du train'],strict=True):
        ax.set_title(title,loc='left',weight='bold',fontsize=11,pad=12);ax.spines[['top','right','left']].set_visible(False)
        ax.grid(axis='x',alpha=.13);ax.set_axisbelow(True);ax.tick_params(axis='y',length=0)
    fig.suptitle('Revue des associations et inventaire par instance',x=.035,ha='left',weight='bold')
    fig.legend(handles=[Patch(color=color,label=name) for _,name,color in palette],loc='lower left',bbox_to_anchor=(.025,.02),ncols=3,frameon=False,fontsize=9)
    fig.text(.57,.065,'Les catégories du train se recouvrent.',fontsize=9)
    out.mkdir(parents=True,exist_ok=True)
    fig.savefig(out/'bilan.png',dpi=130,facecolor='white');plt.close(fig)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path)
    render(p.parse_args().out)
