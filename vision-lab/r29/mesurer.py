"""Capacités et témoins par photo depuis les seules traces et intersections publiques."""
import argparse
from collections import Counter
from itertools import combinations
from pathlib import Path

import numpy as np

from r26.archives import assignment
from r29.commun import ROOT, STAGES, SEEDS, array_hash, checked, identical, read, save
from r29.selection import explain


def conflicts(names, edges, assigned):
    """Composantes et certificats de Hall, en excluant les identités sans arête."""
    pending = [n for n in names if edges[n]]
    results = []
    while pending:
        component = {pending[0]}
        while True:
            neighbors = set().union(*(edges[n] for n in component))
            joined = {n for n in pending if neighbors.intersection(edges[n])}
            if joined == component:
                break
            component = joined
        group = [n for n in pending if n in component]
        pending = [n for n in pending if n not in component]
        deficit = len(group)-sum(n in assigned for n in group)
        if not deficit:
            continue
        best_gap,best_ids,best_origins = 0,(),set()
        for size in range(1,len(group)+1):
            for subset in combinations(group,size):
                origins = set().union(*(edges[n] for n in subset))
                gap = len(subset)-len(origins)
                if gap > best_gap:
                    best_gap,best_ids,best_origins = gap,subset,origins
        assert best_gap == deficit
        results.append(dict(composante=group,origines_composante=sorted(neighbors),deficit=deficit,
                            identites_temoin=list(best_ids),origines_temoin=sorted(best_origins)))
    return results


def measure_stage(origins, counts, names, caps):
    eligible, costs, ids = {},{},{}
    origins=np.asarray(origins,dtype=np.int64)
    assert len(set(origins.tolist()))==len(origins)
    for col,n in enumerate(names):
        cap=caps[n]
        total=cap['positifs_natifs']
        inter=counts[origins,col]
        assert np.all((inter>=0)&(inter<=total))
        denominator=total+inter
        dice=2*inter/denominator
        ok=10*inter*cap['denominateur_dice']>=9*cap['intersection_optimale']*denominator
        eligible[n]=np.flatnonzero(ok).tolist()
        costs[n]=(1-np.minimum(1.,dice/cap['dice_max'])).tolist()
        best=int(dice.argmax()) if len(dice) else None
        ids[n]=dict(possibles_j=origins[ok].tolist(),meilleur_j=int(origins[best]) if best is not None else None,
                    borne_max=float(dice[best]) if best is not None else 0.,seuil=.9*cap['dice_max'])
    matched=assignment(eligible,costs)
    for n,info in ids.items():
        rank=matched.get(n)
        info.update(rang_attribue=rank,j_attribue=int(origins[rank]) if rank is not None else None)
    feasible=[n for n in names if eligible[n]]
    rivals=conflicts(names,{n:set(info['possibles_j']) for n,info in ids.items()},matched)
    assert sum(c['deficit'] for c in rivals)==len(feasible)-len(matched)
    return dict(boites=len(origins),identites=len(names),individuelles=len(feasible),un_a_un=len(matched),
        arêtes=sum(len(q) for q in eligible.values()),sans_possibilite=[n for n in names if not eligible[n]],
        possibles_non_attribuees=[n for n in feasible if n not in matched],deficit_partage=len(feasible)-len(matched),
        concurrences=rivals,par_identite=ids)


def validated_trace(tag,rec,spec):
    with np.load(checked(ROOT/'traces'/rec['fichier'],rec),allow_pickle=False) as f:
        a={k:f[k] for k in f.files}
    wh=spec['entree_modele_xy']
    explainable=explain(a['regression'],a['score'],wh,a['nms'])
    assert all(identical(v,a[k]) for k,v in explainable.items())
    q=a['propositions']
    assert identical(a['nms'][:12],q) and np.array_equal(a['entree_masque'],a['borne'][q])
    assert identical(a['borne'][a['nms']],a['survivantes_boites'])
    assert identical(a['score'][a['nms']],a['survivantes_scores'])
    assert np.array_equal(np.array(spec['dimensions']*2,np.float32)/np.array(wh*2,np.float32),a['echelle'])
    with np.load(rec['origine_R27'],allow_pickle=False) as h:
        for k,v in [('rpn_boites',a['rpn']*a['echelle']),('boites',a['borne'][q]*a['echelle']),
                    ('scores',a['score'][q]),('propositions',q),('rpn_logits',a['rpn_logits']),('rpn_ancres',a['rpn_ancres'])]:
            assert identical(v,h[k]),(tag,k)
        for k,field in rec['champs'].items():
            assert field['identique'] and field['sha256_instrumente']==field['sha256_R27']==array_hash(h[k])
    return a


def trajectory(n, stages, geometry, col):
    events=[]
    possible=[]
    for k in STAGES:
        row=stages[k]['par_identite'][n]
        possible.append(bool(row['possibles_j']))
    for i,(first,second) in enumerate(zip(STAGES,STAGES[1:])):
        before,after=[set(stages[k]['par_identite'][n]['possibles_j']) for k in (first,second)]
        label='stable'
        if before and not after:
            label='perte'
        elif after and not before:
            label='gain'
        events.append(dict(avant=first,apres=second,possibilite=label,gains_j=sorted(after-before),
                           pertes_j=sorted(before-after)))
    last=next((k for k,p in reversed(list(zip(STAGES,possible))) if p),None)
    reason='possible_final' if possible[-1] else (STAGES[STAGES.index(last)+1] if last else 'aucun_depuis_RPN')
    changes=geometry['regression'][:,col]-geometry['rpn'][:,col]
    return dict(presence=possible,dernier_possible=last,diagnostic_final=reason,transitions=events,
        regression=dict(boites_avec_borne_amelioree=int((changes>0).sum()),
                        boites_avec_borne_degradee=int((changes<0).sum()),
                        boites_avec_borne_identique=int((changes==0).sum())))


def photo(tag,rec,spec,cap,inter_index):
    trace=validated_trace(tag,rec,spec)
    path=checked(ROOT/'intersections'/(tag+'.json'),inter_index['fichiers'][tag])
    source=read(path)
    names=sorted(spec['instances'],key=lambda n:spec['instances'][n]['indice'])
    assert source['identites']==names and source['etages']==['rpn','regression','borne']
    mat=np.asarray(source['par_origine_RPN'],np.int64)
    assert mat.shape==(len(trace['rpn']),3,len(names))
    geometry={name:mat[:,i,:] for i,name in enumerate(source['etages'])}
    origins=[np.arange(len(mat)),trace['ordre_score'],trace['ordre_score'],trace['admission'],trace['nms'],trace['propositions']]
    stages={}
    for name,js,g in zip(STAGES,origins,['rpn','regression','borne','borne','borne','borne']):
        stages[name]=measure_stage(js,geometry[g],names,cap)
    trajectories={n:trajectory(n,stages,geometry,i) for i,n in enumerate(names)}
    r28=read(Path('r28/mesures/couples')/f"{rec['graine']}-768-{rec['photo']}.json")
    for i,n in enumerate(names):
        old=r28['identites'][n]
        final=stages['final']['par_identite'][n]
        assert [x['intersection_omega'] for x in old['couples']]==geometry['borne'][trace['propositions'],i].tolist()
        sufficient=trace['propositions'][old['oracle']['admissibles']].tolist()
        assert final['possibles_j']==sufficient and final['rang_attribue']==old['oracle']['q_attribue']
        assert final['borne_max']==old['oracle']['meilleur_dice']
    nms=trace['nms']
    n_all=len(mat)
    reasons=dict(boites_RPN=n_all,invalides=int((~trace['boite_valide']).sum()),
        scores_non_positifs_valides=int((trace['boite_valide']&~trace['score_admis']).sum()),
        supprimees_NMS=int((trace['supprime_par']>=0).sum()),apres_limite=len(nms)-len(trace['propositions']),
        finales=len(trace['propositions']))
    assert sum(v for k,v in reasons.items() if k!='boites_RPN')==n_all
    return dict(photo=rec['photo'],graine=rec['graine'],pas=768,provenance=spec['provenance'],etages=stages,
                identites=trajectories,selection=reasons)


def aggregate(rows):
    stages={}
    for k in STAGES:
        fields=('boites','identites','arêtes','individuelles','un_a_un','deficit_partage')
        stages[k]={f:sum(r['etages'][k][f] for r in rows) for f in fields}
    transitions={}
    ids=[i for r in rows for i in r['identites'].values()]
    for j,k in enumerate(STAGES[1:]):
        events=[i['transitions'][j] for i in ids]
        transitions[k]=dict(pertes_individuelles=sum(e['possibilite']=='perte' for e in events),
            gains_individuels=sum(e['possibilite']=='gain' for e in events),
            aretes_perdues=sum(len(e['pertes_j']) for e in events),aretes_gagnees=sum(len(e['gains_j']) for e in events))
    regression=Counter()
    for i in ids:
        regression.update(i['regression'])
    return dict(photos=len(rows),etages=stages,changements=transitions,regression=dict(regression),
                dernier_retrait=dict(Counter(i['diagnostic_final'] for i in ids)))


def run(dest):
    register=read(ROOT/'traces/index.json')
    inter_index=read(ROOT/'intersections/index.json')
    specs=read('r26/train.json')['photos']
    caps=read('r20/plafonds.json')
    assert list(register['traces'])==[f'{s}-{k}' for s in SEEDS for k in specs]
    assert inter_index['supports_finaux_identiques_R28']==216
    controls={}
    for s in SEEDS:
        rows=[]
        for key,spec in specs.items():
            tag=f'{s}-{key}'
            row=photo(tag,register['traces'][tag],spec,caps[key]['instances'],inter_index)
            save(dest/'photos'/(tag+'.json'),row)
            rows.append(row)
        origins=list(dict.fromkeys(r['provenance'] for r in rows))
        controls[str(s)]=dict(ensemble=aggregate(rows),
            provenances={p:aggregate([r for r in rows if r['provenance']==p]) for p in origins})
        reference=read('r28/mesures/comparaison.json')[f'{s}-768']['ensemble']['oracle']
        final=controls[str(s)]['ensemble']['etages']['final']
        assert final['un_a_un']==reference['cardinalite_un_a_un'] and final['individuelles']==reference['possibles_individuels']
    save(dest/'capacites.json',controls)
    traces=register['traces'].values()
    save(dest/'verification.json',dict(gel=register['gel'],passages_consommes=register['passages'],budget=register['budget'],
        champs_historiques_identiques=sum(len(r['champs']) for r in traces),sorties_finales_R28=216,
        appels_tete_masque_intermediaire=register['appels_masque_intermediaires'],
        boites_RPN=sum(v['ensemble']['etages']['rpn']['boites'] for v in controls.values()),
        identites_physiques=31,photos=9,provenances=8,graphes_par_photo_et_etage=len(traces)*len(STAGES),
        couples_evalues_par_geometrie=sum(len(specs[r['photo']]['instances'])*r['champs']['rpn_logits']['dimensions'][0] for r in traces),
        appariements_finaux_identiques_R28=True,survivantes_NMS_reconstruites_identiques=True))


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=ROOT/'mesures')
    run(cli.parse_args().sortie)
