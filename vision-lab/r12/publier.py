"""Rejouer les tableaux de classement, les courbes et les figures R12."""
import argparse
from functools import lru_cache
from html import escape
import json
from pathlib import Path

from r8.experiment import measure
from r10.diagnostic import read
from r10.tableaux import RUNS
from lab_data import write_json


def curve(rows, record, method):
    col={'A':1,'B':2,'O':3}[method]
    items=[(row,[[p[0],p[col]] for p in record[row['id']]['candidats']]) for row in rows]
    scores=[2.]+sorted({s for _,peaks in items for _,s in peaks},reverse=True)
    @lru_cache(maxsize=None)
    def at(i):
        return measure(items,scores[i])
    values=[]
    for budget in (0,.25,.5,1,2,4,8):
        # Les non-appariées sont monotones, même si l’appariement maximal change.
        lo,hi=0,len(scores)
        while hi-lo>1:
            mid=(hi+lo)//2
            if at(mid)['all']['unmatched']<=budget*len(rows):
                lo=mid
            else:
                hi=mid
        report=at(lo)
        values.append(dict(budget=budget,dernier_seuil_admissible=scores[lo],
                           ensemble=report['all'],provenances=report['per_group'],
                           groupes=report['grouped'],uses=report['worn']))
    return values


def graph(values, split, known):
    width,height=940,558
    parts=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img">',
           f'<title>Rappel de couverture en fonction du budget, développement {split}</title>',
           '<rect width="100%" height="100%" fill="white"/>',
           '<g font-family="sans-serif" fill="#17283d">']
    def label(x,y,words,size=13):
        parts.append(f'<text x="{x}" y="{y}" font-size="{size}">{escape(words)}</text>')
    label(18,29,f'Développement {split} · entrées appariées sur {known}',21)
    label(18,52,'Budgets descriptifs de propositions non appariées par photo. Espacement des budgets catégoriel.')
    colors={'A':'#2861a5','B':'#b74023','O':'#267348'}
    for j,(k,color) in enumerate(colors.items()):
        x=22+j*275
        parts.append(f'<path d="M{x} 76h26" stroke="{color}" stroke-width="3"/>')
        label(x+34,81,{'A':'A · carte de contact','B':'B · axes détectés','O':'O · axes annotés'}[k])
    for index,name in enumerate(RUNS):
        x0=43+(index%3)*303;y0=259+(index//3)*211;h=122
        label(x0,y0-h-13,name,15)
        for f in (0,.5,1):
            y=y0-f*h
            parts.append(f'<path d="M{x0} {y}h235" stroke="#d9e1e8"/>')
            label(x0-27,y+4,f'{known*f:g}',10)
        for j,b in enumerate(['0','¼','½','1','2','4','8']):
            label(x0+39*j-3,y0+15,b,11)
        for k,color in colors.items():
            xy=[(x0+39*j,y0-h*v['ensemble']['matched']/known) for j,v in enumerate(values[name][split][k])]
            points=' '.join(f'{x:.1f},{y:.2f}' for x,y in xy)
            dash={'A':'','B':'5 2','O':'2 3'}[k]
            parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="2.6" stroke-dasharray="{dash}"/>')
            for x,y in xy:
                parts.append(f'<circle cx="{x}" cy="{y:.2f}" r="2.4" fill="{color}"/>')
    label(18,533,'Le seuil est choisi sur la partition représentée. Le tableau présente séparément le seuil transporté.')
    parts.append('</g></svg>')
    return '\n'.join(parts)+'\n'


def render(root,out):
    new=read(root/'nouvelles.json')
    partitions={'historique':read(root/'historique.json'),'nouveau':[r for r in new['photos'] if r['split']=='nouveau']}
    out.mkdir(parents=True,exist_ok=True)
    figures=out/'figures'
    figures.mkdir(exist_ok=True)
    lines=['# Résultats R12 par checkpoint et provenance','',
           'Le coût est le nombre de propositions non appariées admises avec le premier vrai contact sur toute la partition. Le seuil historique est transporté sans recalage. Les effectifs distinguent pics émis, contacts appariés et non-appariées. Le contrôle O utilise les traits annotés.','']
    values={}
    for name in RUNS:
        res=read(root/'mesures'/(name+'.json'))
        raw=read(root/'predictions'/(name+'.json'))
        lines+=['## '+name,'']
        values[name]={}
        for split,rows in partitions.items():
            lines+=['### Développement '+split,'',
                    '| Méthode | Couverture | Coût du premier | Seuil transportable | Appariés | Faux | Non résolus | Silences | Vides déclenchés | Enveloppe locale à 0,25 | Surcoût médian (ms) |',
                    '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
            values[name][split]={}
            for k in ('A','B','O'):
                m=res['methodes'][k][split];o=m['operation']['all']
                first=m['premier_contact']
                lines.append(f"| {k} | {m['couverture']['all']['matched']}/{o['known']} | {first['unmatched'] if first else '—'} | {m['seuil']:.8g} | {o['matched']} | {o['false']} | {o['unresolved']} | {o['silent']}/{len(rows)} | {o['empty_with_points']}/{m['operation']['empty']['photos']} | {m['enveloppe']['all']['matched']} | {m['surcout_ms_mediane']:.2f} |")
                values[name][split][k]=curve(rows,raw,k)
            lines+=['','| Provenance | Méthode | Candidats | Visibles | Couverture | Émis | Appariés | Faux | Non résolus | Silences | Vides déclenchés |',
                    '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
            for row in rows:
                g=row['group']
                for k in ('A','B','O'):
                    m=res['methodes'][k][split];o=m['operation']['per_group'][g];all_=m['couverture']['per_group'][g]
                    lines.append(f"| {g} | {k} | {all_['proposals']} | {o['known']} | {all_['matched']} | {o['proposals']} | {o['matched']} | {o['false']} | {o['unresolved']} | {o['silent']} | {o['empty_with_points']} |")
            lines+=['','| Strate | Méthode | Photos | Visibles | Couverture | Émis | Appariés | Faux | Non résolus |',
                    '|---|---|---:|---:|---:|---:|---:|---:|---:|']
            for group in ('grouped','worn','empty'):
                for k in ('A','B','O'):
                    m=res['methodes'][k][split];o=m['operation'][group]
                    lines.append(f"| {group} | {k} | {o['photos']} | {o['known']} | {m['couverture'][group]['matched']} | {o['proposals']} | {o['matched']} | {o['false']} | {o['unresolved']} |")
            lines.append('')
    (out/'tableaux.md').write_text('\n'.join(lines).rstrip()+'\n')
    for split,rows in partitions.items():
        visible=sum(m['entry'] is not None for r in rows for m in r['marks'])
        (figures/(split+'.svg')).write_text(graph(values,split,visible))
    for name,series in values.items():
        # Une ligne JSON par point de budget, y compris son détail par provenance.
        with (figures/(name+'.jsonl')).open('w') as f:
            for split,methods in series.items():
                for k,points in methods.items():
                    for v in points:
                        f.write(json.dumps(dict(partition=split,methode=k,**v),ensure_ascii=False)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    render(a.root,a.out)
