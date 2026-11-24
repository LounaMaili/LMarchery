"""Tracer les critères R14 et les courbes de contacts à partir des mesures publiées."""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator

ARMS = {'B':('#356991', '-'), 'V':('#b44d22', '--')}


def arm_handles():
    return [Line2D([],[],color=c,linestyle=ls,label=label) for (c,ls),label in
            zip(ARMS.values(),('B · générateur R13','V · après vérification'),strict=True)]


def style(ax):
    ax.spines[['right','top']].set_visible(False)
    ax.grid(axis='y',alpha=.18)


def save(fig, path):
    fig.savefig(path,dpi=128,facecolor='white')
    plt.close(fig)


def geometry(summary, out):
    keys = ['parasites_sur_vide','faux_occupes','strict_1a1','bout_strict_1a1']
    titles = ['Parasites sur vides\nRéduction','Faux établis sur occupées\nRéduction',
              'Axes stricts\nConservation','Entrées au bon bout\nConservation']
    fig, axes = plt.subplots(1,4,figsize=(12.5,4.1),sharey=True)
    fig.subplots_adjust(left=.12,right=.985,bottom=.18,top=.71,wspace=.23)
    for i,(ax,key,title) in enumerate(zip(axes,keys,titles,strict=True)):
        ax.set_title(title,fontsize=10)
        for j,(name,weight) in enumerate(summary.items()):
            m = weight['segments']['criteres'][key]
            num, den = m['numerateur'], m['denominateur']
            y = len(summary)-1-j
            if den:
                value = 100*num/den
                ax.scatter(value,y,s=26,c='#17695c' if m['atteint'] else '#9d462d',zorder=3)
                ax.annotate(f'{num}/{den}',(value,y),xytext=(-6 if value>55 else 6,0),
                            textcoords='offset points',ha='right' if value>55 else 'left',va='center',fontsize=8)
        ax.axvline(60 if i<2 else 90,ls=':',c='#4b4e51')
        ax.set(xlim=(-6,106),ylim=(-.65,5.7),xticks=[0,50,100],xlabel='Pourcentage')
        ax.set_yticks(range(6),list(reversed(summary)),fontsize=9)
        style(ax)
    fig.suptitle('Le vérificateur retire les dix axes stricts de chaque checkpoint',
                 x=.035,y=.98,ha='left',fontsize=15,weight='bold')
    fig.text(.035,.87,'Onze photos de développement. Le trait vertical marque le minimum préenregistré pour chaque critère.',fontsize=9.5)
    save(fig,out/'criteres.png')


def all_curves(summary, curves, out):
    fig, axes = plt.subplots(2,3,figsize=(12.6,7.2),sharex=True,sharey=True)
    fig.subplots_adjust(left=.075,right=.984,bottom=.16,top=.82,wspace=.15,hspace=.32)
    for ax,(name,weight) in zip(axes.flat,summary.items(),strict=True):
        for arm,(color,ls) in ARMS.items():
            points = curves[name,arm,'ensemble']
            x = [p['unmatched']/p['photos'] for p in points]
            y = [100*p['matched']/p['known'] for p in points]
            ax.plot(x,y,color=color,ls=ls,lw=1.5)
            for op,marker in [('historique','x'),('local_R9','o')]:
                p = weight['contacts'][arm][op]
                ax.scatter(p['unmatched']/p['photos'],100*p['matched']/p['known'],
                           c=[color] if marker=='x' else 'none',edgecolors=None if marker=='x' else color,
                           marker=marker,s=48,zorder=3)
        ax.axvline(.25,c='#62686a',ls=':',lw=1)
        ax.set_xscale('symlog',linthresh=.28,linscale=.55)
        ax.xaxis.set_major_locator(FixedLocator([0,.25,1,10,100]))
        ax.set_xticklabels(['0','0,25','1','10','100'])
        ax.set(xlim=(-.02,220),ylim=(-4,100),yticks=[0,25,50,75,100])
        ax.set_title(name,loc='left',fontsize=11)
        style(ax)
    fig.suptitle('Contacts retrouvés selon les propositions non appariées',x=.04,ha='left',weight='bold')
    fig.text(.04,.887,'Dénominateur commun de 18 entrées visibles. Le budget est de 0,25 proposition non appariée par photo.',fontsize=10)
    fig.supylabel('Rappel (%)',x=.012,fontsize=10)
    fig.supxlabel('Propositions non appariées / photo (échelle linéaire près de zéro, puis logarithmique)',y=.083,fontsize=10)
    handles = arm_handles()+[Line2D([],[],color='#404040',ls='',marker=m,markerfacecolor='none',label=l)
                              for m,l in [('x','Seuil hérité'),('o','Seuil choisi sur ce développement')]]
    fig.legend(handles=handles,loc='lower center',ncols=4,frameon=False,fontsize=9)
    save(fig,out/'contacts.png')


def by_source(summary,curves,out,empty):
    first = next(iter(summary))
    strata = sorted(s for n,a,s in curves if n==first and a=='B' and s.startswith('source/')
                    and (curves[n,a,s][0]['known']==0)==empty)
    if not empty:
        strata += ['grouped','worn']
    shape = (2,2) if empty else (3,3)
    fig, axes = plt.subplots(*shape,figsize=(10.4,5.7) if empty else (12.6,8.7),sharex=True,sharey=True)
    fig.subplots_adjust(left=.09,right=.98,bottom=.13,top=.81 if empty else .85,hspace=.43,wspace=.16)
    for ax,group in zip(axes.flat,strata,strict=True):
        for name in summary:
            for arm,(color,ls) in ARMS.items():
                points = [p for p in curves[name,arm,group] if p['seuil']<=1]
                ax.plot([p['seuil'] for p in points],
                        [p['unmatched']/p['photos'] if empty else 100*p['matched']/p['known'] for p in points],
                        color=color,ls=ls,lw=1,alpha=.68)
        last = points[-1]
        label = {'grouped':'Groupes','worn':'Supports usés'}.get(group,group.removeprefix('source/'))
        count = last["known"]
        suffix = "entrée" if count == 1 else "entrées"
        ax.set_title(label if empty else f"{label} · {count} {suffix}",loc='left',fontsize=9.5)
        ax.set_xlim(0,1)
        ax.set_ylim((-5,198) if empty else (-4,104))
        ax.set_xticks([0,.25,.5,.75,1])
        style(ax)
    fig.suptitle('Propositions sur les quatre témoins vides' if empty else 'Rappel par provenance occupée et condition',
                 x=.04,ha='left',weight='bold')
    fig.text(.04,.882 if empty else .912,'Six checkpoints par bras, seuils de 0 à 1. Les tracés se superposent aux seuils où les bras coïncident.',fontsize=9)
    fig.supylabel('Propositions / photo' if empty else 'Rappel (%)',x=.013,fontsize=10)
    fig.supxlabel('Seuil du score contact',y=.071,fontsize=10)
    fig.legend(handles=arm_handles(),loc='lower center',ncols=2,frameon=False,fontsize=9)
    save(fig,out/('temoins.png' if empty else 'provenances.png'))


def render(measures,out):
    summary = json.loads((measures/'synthese.json').read_bytes())['poids']
    curves = defaultdict(list)
    with (measures/'courbes.csv').open() as stream:
        for row in csv.DictReader(stream):
            curves[row['poids'],row['bras'],row['strate']].append(
                {k:(float(row[k]) if k=='seuil' else int(row[k])) for k in
                 ('seuil','photos','known','matched','unmatched')})
    out.mkdir(parents=True,exist_ok=True)
    geometry(summary,out)
    all_curves(summary,curves,out)
    by_source(summary,curves,out,False)
    by_source(summary,curves,out,True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mesures',type=Path)
    parser.add_argument('out',type=Path)
    args=parser.parse_args()
    render(args.mesures,args.out)
