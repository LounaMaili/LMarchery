"""Figures de comparaison issues des sorties train et des journaux de perte."""
import argparse
import json
from pathlib import Path

from r19.donnees import SEEDS, read, save
from r19.tracer import draw, label, node, page, panel, save_svg
from r21.inventorier import ROOT
from r21.plan import ARMS

PALETTE={'temoin':'#15717b','candidat':'#ac5326'}
SEED_COLORS={'9301':'#087e87','9302':'#aa4a0a','9303':'#7052ae'}


def key(root,y):
    for i,(arm,color) in enumerate(PALETTE.items()):
        x=31+i*117
        node(root,'rect',x=x,y=y-8,width=20,height=8,fill=color)
        label(root,x+26,y,arm.capitalize().replace('Temoin','Témoin'),12)


def outcomes(dest,groups):
    charts=[('certifiees','supplementaires_p05','Sorties supplémentaires · 7 vues',84),
            ('certifiees','representees_p05','Instances représentées · 20 admises',20),
            ('toutes','triplets','Triplets avant seuil · 26 admis',26),
            ('partielles','supplementaires_p05','Sorties sans appariement · 2 vues partielles',24)]
    root=page(990,542,'Effet de la règle au pas 768',
              'Chaque paire compare les mêmes 768 présentations. Les nombres en bout de ligne donnent le témoin puis le candidat.')
    key(root,90);data={}
    for i,(group,metric,title,n) in enumerate(charts):
        x=32+(i%2)*482;y=132+(i//2)*189
        label(root,x,y,title,16,'bold')
        data[title]={}
        for j,s in enumerate(map(str,SEEDS)):
            off=y+25+j*39
            label(root,x,off+9,s,12)
            vals=[]
            for idx,arm in enumerate(ARMS):
                value=groups[arm][s]['768'][group]['effectifs'][metric]
                vals.append(value);yy=off+idx*9
                node(root,'rect',x=x+48,y=yy,width=267,height=6,fill='#e0e4e2',rx=2)
                node(root,'rect',x=x+48,y=yy,width=267*value/n,height=6,fill=PALETTE[arm],rx=2)
            label(root,x+328,off+11,f'{vals[0]} → {vals[1]}',14,'bold')
            data[title][s]=dict(temoin=vals[0],candidat=vals[1],plafond=n)
    label(root,32,505,'Sur les vues partielles, une sortie non appariée peut correspondre à une flèche documentaire sans cible active.',12)
    save_svg(root,dest/'comparaison.svg')
    return data


def sources(dest,groups):
    sources=[k for k in groups['temoin']['9301']['768'] if k.startswith('source/')]
    root=page(1030,508,'Où les sorties et les identités changent-elles ?',
              'Pas 768, candidat moins témoin. Les chiffres sont présentés séparément pour chaque graine et chaque provenance.')
    xcols=(273,512,763)
    for x,title in zip(xcols,['Sorties supplémentaires','Instances représentées','Triplets avant seuil'],strict=True):
        label(root,x,110,title,14,'bold')
        for i,s in enumerate(SEEDS):label(root,x+15+i*64,136,str(s),11,anchor='middle')
    data={}
    for j,g in enumerate(sources):
        y=167+j*36
        label(root,29,y+8,g[7:],13)
        data[g]={}
        for i,metric in enumerate(('supplementaires_p05','representees_p05','triplets')):
            vals=[]
            for z,s in enumerate(map(str,SEEDS)):
                delta=groups['candidat'][s]['768'][g]['effectifs'][metric]-groups['temoin'][s]['768'][g]['effectifs'][metric]
                vals.append(delta);x=xcols[i]+z*64
                shade='#c3e0dd' if delta<0 else '#fae2c6' if delta>0 else '#ebeeeb'
                node(root,'rect',x=x-13,y=y-12,width=55,height=27,rx=3,fill=shade)
                label(root,x+15,y+6,f'{delta:+}' if delta else '0',13,'bold',anchor='middle')
            data[g][metric]=dict(zip(map(str,SEEDS),vals,strict=True))
    label(root,29,478,'Bleu-vert pour une diminution, orange pour une augmentation. Les identités échangées sont détaillées dans la table des gains et pertes.',12)
    save_svg(root,dest/'provenances.svg')
    return data


def learning(dest,archive):
    series={}
    for s in map(str,SEEDS):
        series[s]={}
        for arm in ARMS:
            rows=[json.loads(v) for v in (archive/arm/s/'progression.jsonl').read_text().splitlines()]
            series[s][arm]=[dict(cycle=i//8+1,**{k:sum(row[k] for row in rows[i:i+8])/8 for k in ('presence','total')})
                            for i in range(0,768,8)]
    root=page(1030,565,'Trajectoires appariées sur 96 cycles',
              'Chaque point est la moyenne des huit présentations d’un cycle. Les axes verticaux sont communs entre graines pour une même perte.')
    key(root,91)
    for i,metric in enumerate(('presence','total')):
        top=1 if metric=='presence' else 14
        while any(v[metric]>top for r in series.values() for ss in r.values() for v in ss):top*=2
        for j,s in enumerate(map(str,SEEDS)):
            x=47+335*j;y=140+i*197
            fx,fy=panel(root,x,y,267,121,('Présence' if metric=='presence' else 'Perte totale')+' · '+s,96,top,[0,12,48,96],[0,top/2,top])
            for arm in ARMS:
                draw(root,[(fx(v['cycle']),fy(v[metric])) for v in series[s][arm]],PALETTE[arm])
    label(root,30,528,'Cycle 12 = pas 96. Cycle 96 = pas 768. Les classes effectivement supervisées entrent dans la moyenne de présence.',12)
    save_svg(root,dest/'trajectoires.svg')
    return series


def render(out,archives):
    folder=out/'figures';folder.mkdir(exist_ok=True,parents=True)
    groups=read(out/'mesures/groupes.json')
    data=dict(comparaison=outcomes(folder,groups),provenances=sources(folder,groups),cycles=learning(folder,archives))
    save(folder/'donnees.json',data)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--archives',type=Path,default=ROOT/'archives')
    a=p.parse_args();render(a.out,a.archives)
