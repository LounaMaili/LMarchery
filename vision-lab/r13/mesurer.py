"""Mesures et décisions sur les scores sauvegardés, sans lecture des pixels."""
import argparse
from collections import Counter
from pathlib import Path
from statistics import median

from lab_data import write_json
from r9.experiment import threshold
from r9.report import first_contact
from r10.diagnostic import read
from r10.tableaux import RUNS
from r12.mesurer import metrics
from r13.experience import METHODS


def partitions():
    return dict(historique=read(Path('r12/historique.json')),
                nouveau=[r for r in read(Path('r12/nouvelles.json'))['photos'] if r['split']=='nouveau'])


def photo_geom(row,photo,method):
    d=photo[method]
    records=d['reference_axes']
    def counts(items):
        return dict(axes=len(items),strict=sum(a['axis_recovered'] for a in items),
                    masque=sum(a['mask_hits'] for a in items),sondes=21*len(items),
                    union=sum(a['union_hits'] for a in items),suite=sum(a['longest_union'] for a in items),
                    multi=sum(a['union_hits']>=11 for a in items),doublons=sum(len(a['strict_ids'])>1 for a in items))
    refs=photo['audit']['references']
    # Three nearby probes avoid interpreting a single incidental crossing as shared support.
    incidence=Counter(c for a in refs for c,(n,why) in a['composantes'].items() if n>=3)
    audit=Counter()
    for a in refs:
        comps=a['composantes']
        audit['sans_masque']+=not bool(comps)
        audit['sous_025']+=max(a['maxima'])<.25
        adjacent=[c for c,(n,_) in comps.items() if n>=3]
        audit['fragmente']+=len(adjacent)>=2
        audit['partage']+=any(incidence[c]>=2 for c in adjacent)
        for reason in ('rang','aire','allongement','longueur','deux_rejets','retenue'):
            audit[reason]+=any(why==reason for n,why in comps.values())
    visible=[a for a in records if a['visible']]
    distances=[a['entry_distance'] for a in visible if a['entry_distance'] is not None]
    return dict(provenance=row['group'],flags=row['flags'],total=counts(records),
                croises=counts([a for a in records if a['crossing']]),paralleles=counts([a for a in records if a['parallel']]),
                occultes=counts([a for a in records if not a['visible']]),
                segments=d['detected_axes'],multi_reference=d['segments_multi_reference'],audit=dict(audit),
                visibles=len(visible),bout_entree=sum(a['entry_near'] for a in visible),
                bout_oppose=sum(a['opposite_trace_end_near'] for a in visible),distances=distances,
                temps_ms=1000*photo['temps_s'][method])


def aggregate(photos):
    def sums(ds):
        c=Counter()
        for d in ds:
            c.update(d)
        return dict(c)
    d={key:sums(p[key] for p in photos) for key in ('total','croises','paralleles','occultes','audit')}
    d.update({key:sum(p[key] for p in photos) for key in ('segments','multi_reference','visibles','bout_entree','bout_oppose')})
    distances=[v for p in photos for v in p['distances']]
    ts=[p['temps_ms'] for p in photos]
    return dict(**d,photos=len(photos),distance_mediane=median(distances) if distances else None,
                distances_mesurees=len(distances),ms_median=median(ts) if ts else None)


def analyze(raw_folder,folder):
    folder.mkdir(exist_ok=True,parents=True)
    sets=partitions()
    decisions={}
    for name in RUNS:
        raw=read(raw_folder/(name+'.json'))
        contacts={}
        geo={}
        for m in ('A','B'):
            geo[m]={}
            for split,rows in sets.items():
                photos={r['id']:photo_geom(r,raw[r['id']],m) for r in rows}
                geo[m][split]=dict(par_photo=photos,ensemble=aggregate(list(photos.values())),
                    strates={flag:aggregate([p for p in photos.values() if flag in p['flags']]) for flag in ('worn','grouped','empty')})
        for col,m in enumerate(METHODS,1):
            peaks={k:[[p[0],p[col]] for p in photo['candidats']] for k,photo in raw.items()}
            selected=threshold([(r,peaks[r['id']]) for r in sets['historique']])
            contacts[m]={}
            for split,rows in sets.items():
                items=[(r,peaks[r['id']]) for r in rows]
                local=threshold(items) if split=='nouveau' else selected
                contacts[m][split]=dict(seuil=selected,operation=metrics(rows,peaks,selected),
                    enveloppe_seuil=local,enveloppe=metrics(rows,peaks,local),couverture=metrics(rows,peaks,0.)['all'],
                    premier=first_contact(rows,peaks),
                    premier_par_provenance={r['group']:first_contact([r],peaks) for r in rows},
                    traces_supprimes=sum(raw[r['id']]['traits_supprimes'].get(m,0) for r in rows))
        prior=read(Path('r12/mesures')/(name+'.json'))['methodes']
        for m,old in [('H','A'),('A','B'),('O0','O')]:
            for split in sets:
                row=contacts[m][split]
                assert row['operation']==prior[old][split]['operation'] and row['premier']==prior[old][split]['premier_contact']
        gains={}
        for split in sets:
            a,b=(geo[k][split] for k in ('A','B'))
            gains[split]=dict(delta=b['ensemble']['total']['strict']-a['ensemble']['total']['strict'],
                acquisitions=sum(p['total']['strict']>a['par_photo'][i]['total']['strict'] for i,p in b['par_photo'].items()))
        empty={k:sum(geo[k][s]['strates']['empty']['segments'] for s in sets) for k in ('A','B')}
        op_a,op_b=(contacts[k]['nouveau']['operation'] for k in ('A','B'))
        decisions[name]=dict(gains=gains,parasites=empty,geo=empty['B']<=empty['A']+5 and all(v['delta']>0 and v['acquisitions']>=2 for v in gains.values()),
            contact=op_b['all']['unmatched']<=len(sets['nouveau'])/4 and op_b['all']['matched']>op_a['all']['matched']
            and op_b['all']['empty_with_points']<=op_a['all']['empty_with_points'] and
            sum(p['matched']>op_a['per_group'][g]['matched'] for g,p in op_b['per_group'].items())>=2)
        write_json(folder/(name+'.json'),dict(geometrie=geo,contacts=contacts))
    write_json(folder/'decision.json',dict(essais=decisions,geometrie=sum(v['geo'] for v in decisions.values())>=4,
        contacts=sum(v['contact'] for v in decisions.values())>=4))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('sorties',type=Path)
    p.add_argument('mesures',type=Path)
    a=p.parse_args()
    analyze(a.sorties,a.mesures)
