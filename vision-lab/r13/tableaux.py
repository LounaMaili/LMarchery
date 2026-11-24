"""Tables et valeurs des figures issues des relevés par checkpoint."""
import argparse
import json
from pathlib import Path
from r10.diagnostic import read
from r10.tableaux import RUNS
from r12.publier import curve
from r13.mesurer import partitions


def render(root,out):
    out.mkdir(exist_ok=True,parents=True)
    figs=out/'figures'
    figs.mkdir(exist_ok=True)
    values={}
    text=['# Résultats par checkpoint et provenance','',
          'Le seuil est sélectionné sur les douze historiques. Le coût du premier contact compte les propositions non appariées globales au premier seuil contenant un appariement. Le rappel est exprimé en contacts sur les références visibles. Les lignes par provenance sont au seuil historique transporté.','']
    for name in RUNS:
        raw=read(root/'sorties'/(name+'.json'))
        d=read(root/'mesures'/(name+'.json'))
        values[name]={}
        text+=['## '+name,'']
        for s,rows in partitions().items():
            text+=['### '+s,'','| Méthode | Seuil | Couverture | Rappel | Faux | Non résolus | Silences | Vides déclenchés | Coût premier | Rappel enveloppe locale |',
                   '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
            costs={}
            for k,t in d['contacts'].items():
                v=t[s];p=v['operation']['all'];costs[k]=v['premier']['unmatched']
                text.append(f"| {k} | {v['seuil']:.8g} | {v['couverture']['matched']}/{p['known']} | {p['matched']}/{p['known']} | {p['false']} | {p['unresolved']} | {p['silent']} | {p['empty_with_points']} | {costs[k]} | {v['enveloppe']['all']['matched']} |")
            text+=['','| Provenance | Axes stricts A → B | Segments A → B | Bouts près de l’entrée A → B | Coût premier A → B | Contacts au seuil A → B | Faux / non résolus B |',
                   '|---|---:|---:|---:|---:|---:|---:|']
            for row in rows:
                ident,g=row['id'],row['group']
                a,b=(d['geometrie'][m][s]['par_photo'][ident] for m in ('A','B'))
                ma,mb=(d['contacts'][m][s] for m in ('A','B'))
                oa,ob=(m['operation']['per_group'][g] for m in (ma,mb))
                cost=[m['premier_par_provenance'][g] for m in (ma,mb)]
                ca,cb=[v['unmatched'] if v else '—' for v in cost]
                text.append(f"| {g} | {a['total']['strict']} → {b['total']['strict']} / {a['total']['axes']} | {a['segments']} → {b['segments']} | {a['bout_entree']} → {b['bout_entree']} / {a['visibles']} | {ca} → {cb} | {oa['matched']} → {ob['matched']} / {ob['known']} | {ob['false']} / {ob['unresolved']} |")
            text.append('')
            curves={m:curve(rows,raw,old) for m,old in [('H','A'),('A','B'),('B','O')]}
            a,b=(d['geometrie'][k][s] for k in ('A','B'))
            values[name][s]=dict(couts=costs,axes=[v['ensemble']['total']['strict'] for v in (a,b)],
                parasites=[v['strates']['empty']['segments'] for v in (a,b)],courbes=curves)
        with (figs/(name+'.json')).open('w') as f:
            json.dump(values[name],f,indent=1,ensure_ascii=False)
            f.write('\n')
    (out/'tableaux.md').write_text('\n'.join(text).rstrip()+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    render(a.root,a.out)
