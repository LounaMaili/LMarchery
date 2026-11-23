"""Rejouer A, B et le contrôle O depuis les listes publiques, sans inférence."""
import argparse
from pathlib import Path
from statistics import median

from r8.experiment import measure
from r9.experiment import threshold
from r9.report import first_contact
from r10.diagnostic import read
from r10.tableaux import RUNS
from lab_data import write_json

LAB = Path(__file__).resolve().parents[1]


def metrics(rows, peaks, cutoff):
    items = [(row,peaks[row['id']]) for row in rows]
    report = measure(items,cutoff)
    empty = [p for p in report['photos'] if 'empty' in p['flags']]
    report['empty'] = {k:sum(p[k] for p in empty) for k in report['all']}
    return report


def summarize_axes(raw, rows):
    photos = [raw[r['id']]['diagnostic'] for r in rows]
    items = [a for photo in photos for a in photo['reference_axes']]
    visible = [a for a in items if a['visible']]
    def count(axes):
        return dict(axes=len(axes), aligned=sum(a['axis_recovered'] for a in axes),
                    mask_hits=sum(a['mask_hits'] for a in axes),samples=21*len(axes))
    return dict(all=count(items),visible=count(visible),occluded=count([a for a in items if not a['visible']]),
                grouped=count([a for p in photos if p['grouped'] for a in p['reference_axes']]),
                detected=sum(p['detected_axes'] for p in photos),ends_near_entry=sum(a['entry_near'] for a in visible),
                ends_near_opposite=sum(a['opposite_trace_end_near'] for a in visible),photos=len(rows))


def run(folder, out):
    historical = read(LAB/'r12/historique.json')
    new = [r for r in read(LAB/'r12/nouvelles.json')['photos'] if r['split']=='nouveau']
    partitions = {'historique':historical,'nouveau':new}
    results = {}
    for name in RUNS:
        raw = read(folder/(name+'.json'))
        if set(raw) != {r['id'] for r in historical+new}:
            raise ValueError('Partition incomplète')
        scores = {}
        for method,col in [('A',1),('B',2),('O',3)]:
            peaks={key:[[p[0],p[col]] for p in x['candidats']] for key,x in raw.items()}
            cutoff=threshold([(r,peaks[r['id']]) for r in historical])
            scores[method] = {}
            for split,rows in partitions.items():
                if not rows:
                    continue
                local=threshold([(r,peaks[r['id']]) for r in rows]) if split=='nouveau' else cutoff
                op=metrics(rows,peaks,cutoff)
                total=metrics(rows,peaks,0)
                times=[raw[r['id']]['temps_s'].get(method,0.)*1000 for r in rows]
                scores[method][split]=dict(seuil=cutoff,operation=op,enveloppe_seuil=local,
                    enveloppe=metrics(rows,peaks,local),couverture=total,
                    budget_tenu=op['all']['unmatched']<=len(rows)/4,
                    premier_contact=first_contact(rows,peaks),surcout_ms_mediane=median(times))
        for split,rows in partitions.items():
            if not rows:
                continue
            coverage=scores['A'][split]['couverture']
            if any(scores[k][split]['couverture']!=coverage for k in ('B','O')):
                raise ValueError('Comparaison non appariée')
        ref=read(LAB/'r11/mesures'/(name+'.json'))['192']
        a=scores['A']['historique']
        if (a['premier_contact']!=ref['premier_contact_global'] or a['couverture']['all']!=ref['strates']['tous']['all']
                or a['operation']['all']!=ref['strates']['decision_r9']['all']):
            raise ValueError('Référence R11 différente')
        diag={s:summarize_axes(raw,rows) for s,rows in partitions.items() if rows}
        results[name]=dict(methodes=scores,tubes=diag)
    out.mkdir(parents=True,exist_ok=True)
    for name,result in results.items():
        write_json(out/(name+'.json'),result)
    gains=[]; transport=[]
    for name,result in results.items():
        a,b = (result['methodes'][k] for k in ('A','B'))
        if b['historique']['premier_contact']['unmatched'] < a['historique']['premier_contact']['unmatched']:
            gains.append(name)
        if new:
            newa,newb = (x['nouveau']['operation'] for x in (a,b))
            groups = [g for g,rec in newb['per_group'].items() if rec['matched']>newa['per_group'][g]['matched']]
            if (len(groups)>=2 and newb['all']['matched']>newa['all']['matched'] and b['nouveau']['budget_tenu']
                    and newb['all']['empty_with_points']<=newa['all']['empty_with_points']):
                transport.append(name)
    write_json(out/'decision.json',dict(B_cout_reduit=gains,indice_geometrique=len(gains)>=4,
               B_gain_transporte=transport,critere_transport=len(transport)>=4,C_execute=False))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    run(a.folder,a.out)
