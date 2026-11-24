"""Figures de résultats après volée, sans pixels photographiques."""
import argparse
from pathlib import Path
from matplotlib.ticker import MaxNLocator
import matplotlib.pyplot as plt
from r14.mesurer import read,save


def figures(out):
    out.mkdir(exist_ok=True,parents=True)
    result=read('r14/mesures/synthese.json')
    methods=['LSD','paire','isole']
    titles=['Tous','Paires seules','Isolés seuls']
    colors=['#3a5265','#167d74','#c16735']
    values={}
    for k,s in result.items():
        values[k]={m:{metric:s['ensemble']['mesures'][m][metric] for metric in
                      ('parasites_sur_vide','strict_1a1','bout_strict_1a1')} for m in methods}
    fig,axes=plt.subplots(1,3,figsize=(12,4.2),layout='constrained')
    for ax,metric,title in zip(axes,['parasites_sur_vide','strict_1a1','bout_strict_1a1'],
        ['Parasites sur les 5 vides','Axes stricts sur 55','Bouts stricts sur 46 entrées']):
        for j,(m,l,c) in enumerate(zip(methods,titles,colors)):
            ax.bar([i+(j-1)*.25 for i in range(6)],[v[m][metric] for v in values.values()],.24,label=l,color=c)
        ax.set_xticks(range(6),[k.replace('ordinary-','Ord. ').replace('targeted-','Cib. ') for k in result],rotation=45,ha='right')
        ax.yaxis.set_major_locator(MaxNLocator(integer=True))
        ax.set_title(title);ax.spines[['right','top']].set_visible(False)
    axes[-1].legend(fontsize=8,loc='upper left',bbox_to_anchor=(1,1));fig.savefig(out/'geometrie.png',dpi=135);plt.close(fig)
    admission=read('r14/mesures/admission.json')['effectifs']
    def stats(label,photo):
        selected=[r for r in admission if r['famille']=='paire' and r['photo']==photo and (label is None or r['lecture']==label)]
        return [sum(r['n'] for r in selected if r['hors_condition_isole']==fails) for fails in (True,False)]
    bars={title:stats(label,group) for title,label,group in [('Tous sur vide',None,'vide'),('Tubes lus','tube','occupee'),
         ('Blason lu','blason','occupee'),('Décor lu','decor','occupee'),('Incertain lu','incertain','occupee')]}
    fig,ax=plt.subplots(figsize=(7,3.4),layout='constrained')
    for i,(l,(fail,passed)) in enumerate(bars.items()):
        ax.barh(i,fail,color='#c16735',label='Échouent' if i==0 else None)
        ax.barh(i,passed,left=fail,color='#167d74',label='Satisfont' if i==0 else None)
        ax.text(fail+passed+.4,i,f'{fail} / {fail+passed}',va='center',fontsize=9)
    ax.set_xlim(0,68);ax.set_yticks(range(len(bars)),list(bars));ax.invert_yaxis();ax.set_xlabel('Segments à deux contours')
    ax.set_title('Condition des isolés appliquée aux paires de R13');ax.legend();ax.spines[['top','right']].set_visible(False)
    fig.savefig(out/'admission.png',dpi=135);plt.close(fig)
    save(out/'valeurs.json',dict(geometrie=values,admission=bars,ordre_admission=['échouent','satisfont']))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    figures(p.parse_args().out)
