"""Rejeu géométrique des segments déjà publiés. Toutes les distances sont en repère 704."""
import argparse
from pathlib import Path

import numpy as np

from r19.donnees import read, save
from r19.mesures import axes_report, samples
from r20.corpus import references
from r20.rejouer import compact
from r25.commun import ARMS, ROOT, SEEDS, TRANSITIONS, historical, matrices

COS20 = np.cos(np.deg2rad(20.))
COLUMNS = ['segment_proche','distance','angle_deg','segment_orientation_compatible',
           'distance_compatible','angle_compatible_deg','angle_min_dans_6px','proche','couvert']


def rounded(x):
    return round(float(x),6) if np.isfinite(x) else None


def distances(points, segments):
    seg = np.asarray(segments,dtype=float).reshape(-1,2,2)
    vec = seg[:,1]-seg[:,0]
    lengths = np.linalg.norm(vec,axis=1)
    unit = vec/np.where(lengths>1.e-9,lengths,1.)[:,None]
    delta = points[:,None]-seg[None,:,0]
    along = np.sum(delta*unit,axis=-1).clip(0,lengths)
    return np.linalg.norm(delta-along[:,:,None]*unit,axis=2),unit,lengths


def probe_rows(points,tangents,segments):
    dist,unit,lengths = distances(points,segments)
    if not len(lengths):
        return [[None]*7+[False,False] for _ in points]
    dist[:,lengths<=1.e-9] = np.inf
    dot = tangents@unit.T
    cross = tangents[:,0,None]*unit[:,1]-tangents[:,1,None]*unit[:,0]
    angle = np.rad2deg(np.arctan2(abs(cross),abs(dot)))
    oriented = (abs(dot)>=COS20) & (lengths>1.e-9)
    rows = []
    for d,a,ok in zip(dist,angle,oriented,strict=True):
        near = d<=6
        def nearest(mask):
            candidates = np.flatnonzero(mask)
            if not len(candidates):
                return [None,None,None]
            minimum = float(d[candidates].min())
            j = int(candidates[np.flatnonzero(d[candidates]<=minimum+1.e-10)[0]])
            return [j,rounded(minimum),rounded(a[j])]
        rows.append(nearest(np.isfinite(d))+nearest(ok)+[rounded(a[near].min()) if near.any() else None,
                                                        bool(near.any()),bool((near&ok).any())])
    return rows


def geometry(out):
    refs = references()
    transitions, per_state, summaries = [], {}, {}
    for seed in SEEDS:
        pair = {}
        for arm in ARMS:
            folder = historical(arm,seed)
            predictions = read(folder/'predictions.json')
            archive = matrices(arm,seed)
            records = {}
            totals = dict(photos=0,axes=0,cellules_verifiees=0,apparies=0)
            for k,ref in refs.items():
                outputs = predictions[k]['sorties']
                axes = {s['id']:s['segments'] for s in outputs if s['segments']}
                match = axes_report(ref,axes)
                if match!=archive[k]['axes']:
                    raise ValueError('Divergence des appariements '+k)
                names = {}
                for n,paths in ref['axes'].items():
                    point,tangent = samples(paths)
                    qs = []
                    for q,row in enumerate(outputs):
                        if row['id']!=q:
                            raise ValueError('Ordre des requêtes')
                        probes=probe_rows(point,tangent,row['segments'])
                        hits=sum(p[-1] for p in probes)
                        if hits!=archive[k]['paires_par_identite'][n][q]['sondes_axe']:
                            raise ValueError(f'Couverture divergente {seed} {arm} {n} {q}')
                        qs.append(dict(q=q,couvertes=hits,proches=sum(p[-2] for p in probes),sondes=probes))
                    names[n]=dict(appariee_q=archive[k]['identites'][n]['axe_q'],
                                  admissibles=[q['q'] for q in qs if q['couvertes']>=11],requetes=qs)
                totals['photos']+=1
                totals['axes']+=len(names)
                totals['cellules_verifiees']+=12*len(names)
                totals['apparies']+=match['apparies']
                records[k]=names
                compact(out/'geometrie'/f'{arm}-{seed}'/(k+'.json'),dict(provenance=ref['spec']['provenance'],
                    photo=k,bras=arm,graine=seed,colonnes_sonde=COLUMNS,appariement=match,identites=names))
            pair[arm]=records
            per_state[f'{arm}-{seed}']=totals
        count={'conserves':0,'pertes':0,'gains':0,'absents_deux':0}
        for k,ref in refs.items():
            for n in ref['axes']:
                a,b=(pair[arm][k][n] for arm in ARMS)
                qa,qb=a['appariee_q'],b['appariee_q']
                status=('conserves' if qb is not None else 'pertes') if qa is not None else (
                    'gains' if qb is not None else 'absents_deux')
                count[status]+=1
                if status not in ('pertes','gains'):
                    continue
                if k not in TRANSITIONS[seed]:
                    raise ValueError('Transition hors périmètre préenregistré')
                weak_arm='bloc8' if status=='pertes' else 'dice'
                weak=pair[weak_arm][k][n]
                competition=[dict(q=q,sondes=weak['requetes'][q]['couvertes'],attribution=[
                    dict(identite=j,sondes=i['requetes'][q]['couvertes']) for j,i in pair[weak_arm][k].items()
                    if i['appariee_q']==q]) for q in weak['admissibles']]
                transitions.append(dict(graine=seed,photo=k,provenance=ref['spec']['provenance'],identite=n,
                                        statut=status,dice_q=qa,bloc8_q=qb,concurrence=competition))
        summaries[str(seed)]=count
        if seed==9302:
            crist=pair['bloc8']['4169106197']['4169106197:1']['requetes']
            # Exception descriptive demandée. Ces minima inter-requêtes ne sont jamais un appariement.
            nearest=[min((q['sondes'][i][1] for q in crist if q['sondes'][i][1] is not None),default=None)
                     for i in range(21)]
    result=dict(etats=per_state,bilan=summaries,transitions=transitions,cristina_min_sans_angle=nearest)
    save(out/'diagnostic-archives.json',result)
    losses=[t for t in transitions if t['statut']=='pertes']
    comp=[t for t in losses if t['concurrence']]
    if (sum(v['cellules_verifiees'] for v in per_state.values())!=2232 or len(losses)!=13 or
            len(transitions)-len(losses)!=5 or len(comp)!=1 or comp[0]['identite']!='wc71488242:3' or
            comp[0]['graine']!=9302 or comp[0]['concurrence']!=[dict(q=7,sondes=11,
            attribution=[dict(identite='wc71488242:9',sondes=20)])] or
            (round(min(nearest),3),round(max(nearest),3))!=(18.122,40.307)):
        raise ValueError('Un constat du diagnostic des archives diverge')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',nargs='?',type=Path,default=ROOT)
    geometry(parser.parse_args().out)
