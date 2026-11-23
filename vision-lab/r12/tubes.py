"""Synthèse descriptive des axes, des croisements projetés et des cas sans appui."""
import argparse
from itertools import combinations
from pathlib import Path

from lab_data import write_json
from r10.diagnostic import read
from r10.tableaux import RUNS


def crosses(first,second):
    def side(a,b,c):
        return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    a,b=first;c,d=second
    return side(a,b,c)*side(a,b,d)<0 and side(c,d,a)*side(c,d,b)<0


def summarize(root,out):
    new=read(root/'nouvelles.json')['photos']
    splits={'historique':read(root/'historique.json'),'nouveau':[r for r in new if r['split']=='nouveau']}
    data={}
    lines=['# Diagnostic des tubes','',
           'Le masque est mesuré au seuil 0,5 en 21 points par axe, avec tolérance latérale de 3 px. Un axe est retrouvé si un même segment détecté reste à 6 px et à 20° près sur au moins 11 points. Un segment peut expliquer plusieurs axes annotés. Le croisement est une intersection intérieure stricte des traits en projection. Deux tubes proches et parallèles peuvent être groupés sans croiser leurs axes.','']
    for split,rows in splits.items():
        lines+=['## Développement '+split,'',
                '| Checkpoint | Points couverts / sondés | Axes retrouvés | Groupés | Croisés | Entrée occultée | Segments détectés | Bout près entrée / visibles | Bout près extrémité opposée du trait / visibles | Photos sans segment |',
                '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        data[split]={}
        for name in RUNS:
            raw=read(root/'predictions'/(name+'.json'))
            stats={}
            for row in rows:
                key=row['id']; photo=raw[key];d=photo['diagnostic']
                axes=[m['axis'] for m in row['marks']]
                crossing={i for a,b in combinations(range(len(axes)),2) if crosses(axes[a],axes[b]) for i in (a,b)}
                refs=d['reference_axes']
                stats[key]=dict(provenance=row['group'],axes=len(refs),retrouves=sum(a['axis_recovered'] for a in refs),
                    pixels_sondes=21*len(refs),couvert=sum(a['mask_hits'] for a in refs),
                    groupes=len(refs) if d['grouped'] else 0,
                    groupes_retrouves=sum(a['axis_recovered'] for a in refs) if d['grouped'] else 0,
                    croises=len(crossing),croises_retrouves=sum(refs[i]['axis_recovered'] for i in crossing),
                    occultes=sum(not a['visible'] for a in refs),
                    occultes_retrouves=sum(a['axis_recovered'] and not a['visible'] for a in refs),
                    segments=d['detected_axes'],sans_segment=int(not d['detected_axes']),
                    pres_entree=sum(a.get('entry_near',False) for a in refs),
                    pres_oppose=sum(a.get('opposite_trace_end_near',False) for a in refs),
                    visibles=sum(a['visible'] for a in refs),candidats=len(photo['candidats']),
                    bonus_nul=sum(p[4]==0 for p in photo['candidats']),
                    score_inchange=sum(p[1]==p[2] for p in photo['candidats']))
            total={k:sum(v[k] for v in stats.values()) for k in next(iter(stats.values())) if k!='provenance'}
            data[split][name]=dict(par_photo=stats,total=total)
            t=total
            lines.append(f"| {name} | {t['couvert']}/{t['pixels_sondes']} | {t['retrouves']}/{t['axes']} | {t['groupes_retrouves']}/{t['groupes']} | {t['croises_retrouves']}/{t['croises']} | {t['occultes_retrouves']}/{t['occultes']} | {t['segments']} | {t['pres_entree']}/{t['visibles']} | {t['pres_oppose']}/{t['visibles']} | {t['sans_segment']}/{len(rows)} |")
        lines.append('')
    lines+=['Un dénominateur nul indique une strate sans référence. La colonne « bout près » compte les références ayant au moins un bout détecté dans un rayon de 10 px. Le bout opposé est celui du tracé humain le plus éloigné du contact. Il peut se trouver sur le corps visible. L’axe est non orienté et la proximité seule n’identifie pas le côté qui pénètre dans le support.','',
            'Les coordonnées détaillées et distances sont conservées dans les [sorties par checkpoint](predictions/). Le [relevé numérique](diagnostic-tubes.json) donne aussi les effectifs de pics dont le bonus est nul et de scores effectivement inchangés. La mesure de continuité seule ne permet pas d’estimer la précision pixel à pixel du masque.','']
    write_json(out/'diagnostic-tubes.json',data)
    (out/'tubes.md').write_text('\n'.join(lines))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    summarize(a.root,a.out)
