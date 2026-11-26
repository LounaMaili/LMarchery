"""Appariements hérités et décision sur les ensembles d’identités stables."""
import argparse
import json
from pathlib import Path
import numpy as np

from r19.donnees import SEEDS, read, save
from r20.corpus import references
from r20.rejouer import compact
from r21.mesures import matching, measure_views
from r21.plan import STEPS
from r22.lecture import change, compare, describe, groups
from r22.mesures import trajectory
from r24.diagnostic import ROOT, admissible, archived

ARMS = ('R21', 'R22', 'bloc8')


def identities(raw, registry, caps):
    photos = {}
    for k,row in raw.items():
        info = describe(row,registry[k],caps[k]['instances'])
        pairs = row['paires_par_identite']
        wanted = {n:['masque','axe'] for n in info['identites']}
        probs = [s['presence'] for s in row['sorties']]
        joint = matching(pairs, wanted, probs, threshold=0)['appariements']
        for n,i in info['identites'].items():
            flags = admissible(pairs[n])
            i.update(masque_et_axe_qs=flags['masque_et_axe'], masque_et_axe_q=joint.get(n),
                     masque_et_axe_separes=flags['seulement_sur_requetes_distinctes'],
                     champs_repartis=all(i['par_champ'].values()) and not i['conjoint_disponible_q'],
                     fusions_q=[int(q) for q,names in row['masques']['fusions'].items() if n in names],
                     dedoublements_q=row['masques']['dedoublements'].get(n,[]))
        photos[k] = info
    return photos


def grouped(photos):
    result = groups(photos)
    for k,photo in photos.items():
        labels = ('toutes','photo/'+k,'source/'+photo['provenance'],
                  'exhaustives' if photo['inventaire_exhaustif'] else 'partielles')
        for label in labels:
            count = result[label]['effectifs']
            for n in ('masque_et_axe_disponibles','masque_et_axe_un_a_un','masque_et_axe_separes','champs_repartis'):
                count.setdefault(n,0)
            for i in photo['identites'].values():
                count['masque_et_axe_disponibles'] += bool(i['masque_et_axe_qs'])
                count['masque_et_axe_un_a_un'] += i['masque_et_axe_q'] is not None
                count['masque_et_axe_separes'] += i['masque_et_axe_separes']
                count['champs_repartis'] += i['champs_repartis']
    return result


def selected(photos, field):
    return {n for row in photos.values() for n,i in row['identites'].items() if i[field] is not None}


def contrasts(reference, candidate):
    out = compare(reference,candidate)
    del out['conforme']
    out['champs']['masque_et_axe_q'] = change(selected(reference,'masque_et_axe_q'),selected(candidate,'masque_et_axe_q'))
    return out


def decision_for(r21, r22, candidate, require_complete_union=True):
    full = {n for row in r22.values() for n,i in row['identites'].items() if 'entree' in i['champs']}
    union = selected(r21,'entree_q') | selected(r22,'entree_q')
    if require_complete_union and (len(full)!=26 or full!=union):
        raise ValueError('Union des entrées historiques divergente')
    primary = contrasts(r22,candidate)
    secondary = contrasts(r21,candidate)
    missing = sorted(union-selected(candidate,'entree_q'))
    checks = dict(gains_multisources=len(primary['gains_triplets_hors_Crigges'])>=2 and len(primary['provenances_des_gains'])>=2,
                  conservation_triplets_R22=not primary['champs']['triplet_q']['perdues'],
                  conservation_union_entrees=not missing)
    return dict(R22=primary, R21=secondary, union_entrees=sorted(union), entrees_union_perdues=missing,
                criteres=checks, conforme=all(checks.values()))


def instrument(folder, seed, registry, parity, plan):
    hist = trajectory(folder,seed,'dice',registry,plan,parity)
    logs = [json.loads(line) for line in (folder/'progression.jsonl').read_text().splitlines()]
    cfg = read(ROOT/'comparabilite.json')['graines'][str(seed)]
    for step in STEPS:
        control = read(folder/str(step)/'controle.json')
        if control['sha256_geles']!=cfg['gel_initial'] or control['controles_gel_apres_pas']!=step:
            raise ValueError('Gel non vérifié')
    for span,rs in [('1-96',logs[:96]),('97-768',logs[96:]),('1-768',logs)]:
        for key,stats in hist[span].items():
            subset = rs if key=='toutes' else [r for r in rs if key in ('photo/'+r['photo'],'source/'+r['provenance'])]
            stats['frequence_ecretage'] = stats['gradients_ecretes']/len(subset)
            for g in ('tete','bloc8'):
                rows = [r['groupes'][g] for r in subset]
                columns = {c:[v[c] for v in rows] for c in ('norm_grad_avant','norm_grad_apres')}
                for c in ('l2','max_abs'):
                    columns['delta_'+c] = [v['delta'][c] for v in rows]
                for name,values in columns.items():
                    if not np.isfinite(values).all():
                        raise ValueError('Instrumentation non finie')
                    stats.setdefault('groupes',{}).setdefault(g,{})[name] = dict(
                        moyenne=sum(values)/len(values), maximum=max(values))
    return hist


def replay(out, archives):
    refs,registry,caps = references(),read('r21/inventaires.json'),read('r20/plafonds.json')
    totals,contrasts_by_state,wins = {},{},[]
    plans,parity = read('r21/tirages.json'),read('r22/comparabilite.json')
    for seed in SEEDS:
        s = str(seed)
        folder = archives/s
        compact(out/f'instrumentation/{s}.json',instrument(folder,seed,registry,parity,plans[s]))
        for step in STEPS:
            arms = {}
            for arm in ARMS:
                if arm=='bloc8':
                    raw = measure_views(refs,registry,folder/str(step),seed)
                    compact(out/f'mesures/{arm}-{seed}-{step}.json',raw)
                else:
                    _,raw = archived(arm,seed,step)
                arms[arm] = identities(raw,registry,caps)
                compact(out/f'identites/{arm}-{seed}-{step}.json',arms[arm])
                totals.setdefault(arm,{}).setdefault(s,{})[str(step)] = grouped(arms[arm])
            result = decision_for(arms['R21'],arms['R22'],arms['bloc8'],step==768)
            if step==96:
                result.pop('conforme')
                result.pop('criteres')
            elif result['conforme']:
                wins.append(seed)
            contrasts_by_state.setdefault(s,{})[str(step)] = result
    compact(out/'mesures/groupes.json',totals)
    compact(out/'mesures/contrastes.json',contrasts_by_state)
    save(out/'decision.json',dict(graines_conformes=wins, minimum=2, poursuite_scientifique=len(wins)>=2,
         pas_decision=768, ajustements_candidats=3, mises_a_jour=2304, ajustements_temoins=0,
         nouvelles_lectures_train=54, nouvelles_lectures_developpement=0, lectures_test=0, NO_GO_Android=True))


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path,nargs='?',default=ROOT)
    parser.add_argument('--archives',type=Path,default=ROOT/'archives/bloc8')
    args = parser.parse_args()
    replay(args.out,args.archives)
