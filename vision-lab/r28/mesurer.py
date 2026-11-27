"""Rejouer les quatre états R27 et comparer les trois géométries sur les archives."""
import argparse
import hashlib
from collections import Counter
from pathlib import Path
from statistics import median
from tempfile import TemporaryDirectory

import cv2
import numpy as np

from r27.bilan import summary as historical_summary
from r27.mesurer import measure
from r28.commun import ROOT, REFERENCE, GEL, INPUTS, SEEDS, STEPS, digest, read, save, scope, tag
from r28.couples import compare, transition
from r28.partiel import partial


def cross(pairs):
    counts=dict(boite_et_borne=0,boite_seule=0,borne_seule=0,ni_boite_ni_borne=0)
    for box,bound in pairs:
        label=('boite_et_borne' if bound else 'boite_seule') if box else ('borne_seule' if bound else 'ni_boite_ni_borne')
        counts[label]+=1
    return counts


def paired(rows):
    if not rows:
        return dict(couples=0)
    deltas=[p['delta_dice_appris_moins_plein'] for p in rows]
    return dict(couples=len(rows),mediane_dice_appris=median(p['dice_appris'] for p in rows),
        mediane_dice_plein=median(p['dice_plein'] for p in rows),mediane_difference=median(deltas),
        dice_superieur=sum(d>0 for d in deltas),dice_identique=sum(d==0 for d in deltas),
        dice_inferieur=sum(d<0 for d in deltas),
        admis_appris=sum(p['appris_admis'] for p in rows),admis_plein=sum(p['plein_admis'] for p in rows),
        separes_appris=sum(p['appris_separe'] for p in rows),separes_plein=sum(p['plein_separe'] for p in rows),
        gagnes_separes=sum(p['appris_separe'] and not p['plein_separe'] for p in rows),
        perdus_separes=sum(p['plein_separe'] and not p['appris_separe'] for p in rows))


def summarize(photos):
    ids={n:inst for row in photos for n,inst in row['identites'].items()}
    assert len(ids)==sum(len(row['identites']) for row in photos)
    pairs=[p for inst in ids.values() for p in inst['couples']]
    arms={}
    for arm,key in [('appris','effectifs_appris'),('plein','effectifs_plein')]:
        counts=Counter()
        for row in photos:
            counts.update(row[key])
        arms[arm]=dict(**counts,dice_median_attribue=median(inst[arm]['dice_attribue'] for inst in ids.values()))
    fixed={'tous':paired(pairs)}
    for arm in ('appris','plein'):
        for field in ('q_separe','q_admis','q_dice_total'):
            subset=[i['couples'][i[arm][field]] for i in ids.values() if i[arm][field] is not None]
            fixed[f'{arm}_{field}']=paired(subset)
    comparisons={}
    for label,field in [('admis','q_admis'),('separes','q_separe')]:
        a,b=[{n for n,inst in ids.items() if inst[arm][field] is not None} for arm in ('appris','plein')]
        comparisons[label]=dict(gains_appris=sorted(a-b),pertes_appris=sorted(b-a),conserves=sorted(a&b))
    feasible={n for n,i in ids.items() if i['oracle']['admissibles']}
    allocated={n for n,i in ids.items() if i['oracle']['q_attribue'] is not None}
    assert arms['appris']['separes']<=len(allocated) and arms['plein']['separes']<=len(allocated)
    return dict(**arms,memes_couples=fixed,comparaison_appariements=comparisons,
        oracle=dict(couples_possibles=sum(p['oracle_suffisant'] for p in pairs),couples=len(pairs),
            possibles_individuels=len(feasible),cardinalite_un_a_un=len(allocated),
            impossibles_geometriques=sorted(ids.keys()-feasible),possibles_non_attribues=sorted(feasible-allocated),
            identites_attribuees=sorted(allocated),deficit_concurrence=len(feasible-allocated)),
        croisement_IoU_borne_couples=cross((p['iou_boite']>=.5,p['oracle_suffisant']) for p in pairs),
        croisement_IoU_borne_identites=cross((i['meilleure_boite']['iou_boite']>=.5,bool(i['oracle']['admissibles'])) for i in ids.values()),
        q_meilleur_dice_distinct_meilleure_boite=sum(not i['meme_q_meilleurs'] for i in ids.values()))


def raster(rec, source, spec):
    name=ROOT/'supports'/rec['fichier']
    assert digest(name)==rec['sha256'] and digest(source)==rec['sha256_source']
    full=cv2.imread(str(name),cv2.IMREAD_UNCHANGED)
    assert full.dtype==np.uint16 and full.shape==tuple(spec['dimensions'][::-1])
    assert hashlib.sha256(full.astype('<u2').tobytes()).hexdigest()==rec['sha256_pixels_uint16_le']
    return full


def replay(out):
    cv2.setNumThreads(1)
    index=read(ROOT/'supports/index.json')
    assert index['reference']==REFERENCE and index['etats']==36 and index['seuil_inclusif']==.5
    assert index['divergences_R27']==index['pixels_appris_hors_omega']==0
    caps=read('r20/plafonds.json')
    photos=read('r26/train.json')['photos']
    original={(s,t):read(f'r27/mesures/{s}-{t}.json') for s in SEEDS for t in STEPS}
    curves=read('r27/mesures/courbes.json')
    states, paths, matrix = {},{},{}
    historical={}
    with TemporaryDirectory() as folder:
        temp=Path(folder)/'boites-pleines.npz'
        for seed,step,k,spec,source in scope():
            code=tag(seed,step,k)
            paths[code]=source
            rec=index['supports'][code]
            full=raster(rec,source,spec)
            learned=measure(source,spec,caps[k]['instances'])
            assert learned==original[seed,step][k], 'Divergence de mesure R27 '+code
            with np.load(source,allow_pickle=False) as data:
                assert not np.any(data['masques'] & ~full), 'Sortie hors support '+code
                arrays={a:data[a] for a in ('boites','scores','propositions','rpn_boites','rpn_logits','rpn_ancres')}
            for q,audit in enumerate(rec['sorties']):
                assert q==audit['q'] and audit['divergences_R27']==audit['appris_hors_omega']==0
                assert learned['sorties'][q]['aire']==audit['pixels_appris']
            np.savez(temp,masques=full,**arrays)
            control=measure(temp,spec,caps[k]['instances'])
            assert [v['aire'] for v in control['sorties']]==[v['pixels_omega'] for v in rec['sorties']]
            state=compare(spec,learned,control,arrays,caps[k]['instances'])
            state.update(graine=seed,pas=step,photo=k)
            states[code]=state
            historical.setdefault((seed,step),[]).append(learned)
            save(out/'couples'/(code+'.json'),state)
    assert set(states)==set(index['supports']) and len(states)==36
    for seed in SEEDS:
        for step in STEPS:
            rows=[states[tag(seed,step,k)] for k in photos]
            archive=historical_summary(historical[seed,step])
            assert archive==curves[str(seed)][str(step)]
            sources=list(dict.fromkeys(r['provenance'] for r in rows))
            matrix[f'{seed}-{step}']=dict(ensemble=summarize(rows),
                provenances={s:summarize([v for v in rows if v['provenance']==s]) for s in sources},
                par_photo={r['photo']:summarize([r]) for r in rows})
    transitions=[]
    for seed in SEEDS:
        for k,spec in photos.items():
            a=states[tag(seed,512,k)]['identites']
            b=states[tag(seed,768,k)]['identites']
            for name in a:
                c=transition(a[name],b[name])
                transitions.append(dict(graine=seed,photo=k,identite=name,provenance=spec['provenance'],**c,
                    controles={str(step):{k:v for k,v in inst.items() if k!='couples'}
                               for step,inst in [(512,a[name]),(768,b[name])]}))
    counts=Counter(r['categorie'] for r in transitions if r['sens']!='stable')
    expected=dict(bascule_boite=2,seuil_masque_boite_couvrante=10,appariement_seul=0)
    observed={k:counts[k] for k in sorted(set(counts)|set(expected))}
    save(out/'trajectoires.json',transitions)
    save(out/'comparaison.json',matrix)
    save(out/'partiel.json',partial(photos,states,paths))
    pairs=sum(len(i['couples']) for s in states.values() for i in s['identites'].values())
    assert (len(transitions),pairs)==(62,1488)
    save(out/'verification.json',dict(reference_R27=REFERENCE,gel_R28=GEL,
        sources_R27=INPUTS,quatre_mesures_identiques=True,quatre_agregats_identiques=True,
        etats_photo=len(states),sorties=sum(len(r['sorties']) for r in states.values()),etats_identite=2*len(transitions),
        couples=pairs,transitions=sum(counts.values()),repartition_annoncee=expected,repartition_observee=observed,
        accord_revue=Counter(observed)==Counter(expected),passages_modele=0))


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=ROOT/'mesures')
    replay(cli.parse_args().sortie)
