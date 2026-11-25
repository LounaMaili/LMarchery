"""Rejouer les deux états et la décision à partir des sorties archivées, sans modèle."""
import argparse
from collections import Counter
import json
from pathlib import Path

from r19.donnees import SEEDS, read, save
from r20.corpus import references
from r20.rejouer import compact
from r21.mesures import measure_views
from r21.plan import STEPS
from r21.publier import summarize
from r22.lecture import compare, describe, groups

ROOT = Path('r22')
ARMS = ('temoin','dice')


def trajectory(folder, seed, arm, registry, plan, parity):
    ledger = read(folder/'execution.json')
    logs = [json.loads(s) for s in (folder/'progression.jsonl').read_text().splitlines()]
    hist = parity['temoins'][str(seed)]
    if (ledger['etat_initial']!=hist['initial'] or ledger['nombre_pas']!=len(plan) or
            ledger['vues_train_lues_aux_controles']!=18 or
            any(ledger[k]!=v for k,v in parity['environnement'].items()) or
            [{k:r[k] for k in ('pas','photo','provenance')} for r in logs]!=plan):
        raise ValueError('Trajectoire différente du protocole')
    for r in logs:
        names = {i['instance'] for i in registry[r['photo']]['identites'] if i['champs']}
        pos,neg = r['positives'],r['negatives']
        if (set(pos)!=names or len(set(pos.values()))!=len(pos) or set(pos.values())&set(neg) or
                r['presences_negatives']!=len(neg) or r['identites']!=len(pos)):
            raise ValueError('Affectation au pas '+str(r['pas']))
        expected = r['masque']+r['axe']+r['entree']+.25*r['presence']
        if arm=='dice':
            expected += r['dice']
        if abs(r['total']-expected)>2.e-5:
            raise ValueError('Somme des pertes différente')
    for step in STEPS:
        c = read(folder/str(step)/'controle.json')
        prefix = logs[:step]
        if c!=ledger['controles'][str(step)] or (arm=='dice' and c['photos_evaluees']!=list(registry)):
            raise ValueError('Registre des évaluations incohérent')
        for name,fn in [('expositions',lambda r:1),('positives',lambda r:len(r['positives'])),
                        ('negatives',lambda r:len(r['negatives']))]:
            observed = Counter()
            for r in prefix:
                observed[r['photo']] += fn(r)
            if dict(observed)!=c[name]:
                raise ValueError('Expositions différentes '+name)
    spans={}
    for name,rows in [('1-96',logs[:96]),('97-768',logs[96:]),('1-768',logs)]:
        subsets={'toutes':rows}
        for r in rows:
            subsets.setdefault('source/'+r['provenance'],[]).append(r)
            subsets.setdefault('photo/'+r['photo'],[]).append(r)
        spans[name]={}
        for g,rs in subsets.items():
            stats=summarize(rs)
            stats['moyennes']['dice']=sum(r['dice'] for r in rs)/len(rs) if arm=='dice' else None
            spans[name][g]=stats
    return spans


def replay(out, archives):
    registry=read('r21/inventaires.json')
    caps=read('r20/plafonds.json')
    refs=references()
    parity=read(ROOT/'comparabilite.json')
    plans=read('r21/tirages.json')
    totals,changes,losses={},{},{}
    wins=[]
    for seed in SEEDS:
        s=str(seed)
        changes[s]={}
        for arm in ARMS:
            folder=(Path('r21/archives/temoin') if arm=='temoin' else archives)/s
            losses.setdefault(arm,{})[s]=trajectory(folder,seed,arm,registry,plans[s],parity)
        for step in STEPS:
            pair={}
            for arm in ARMS:
                if arm=='temoin':
                    raw=read(f'r21/mesures/temoin-{s}-{step}.json')['par_photo']
                else:
                    raw=measure_views(refs,registry,archives/s/str(step),seed)
                    compact(out/f'mesures/dice-{s}-{step}.json',raw)
                pair[arm]={k:describe(row,registry[k],caps[k]['instances']) for k,row in raw.items()}
                for k,row in raw.items():
                    official={c['instance']:c['sortie'] for c in row['couverture']['paires']}
                    if official!={n:i['entree_q'] for n,i in pair[arm][k]['identites'].items() if i['entree_q'] is not None}:
                        raise ValueError('Désaccord avec la couverture R19')
                totals.setdefault(arm,{}).setdefault(s,{})[str(step)]=groups(pair[arm])
                compact(out/f'identites/{arm}-{s}-{step}.json',pair[arm])
            contrast=compare(pair['temoin'],pair['dice'])
            for g,a in totals['temoin'][s][str(step)].items():
                b=totals['dice'][s][str(step)][g]
                contrast.setdefault('variations_par_groupe',{})[g]={k:b['effectifs'][k]-v for k,v in a['effectifs'].items()}
            changes[s][str(step)]=contrast
            if step==768 and contrast['conforme']:
                wins.append(seed)
    compact(out/'mesures/groupes.json',totals)
    compact(out/'mesures/contrastes.json',changes)
    compact(out/'pertes-et-expositions.json',losses)
    decision=dict(graines_conformes=wins,minimum_graines=2,critere_confirme=len(wins)>=2,
        pas_decision=768,ajustements_candidats=3,ajustements_temoins=0,mises_a_jour=2304,
        lectures_train_candidat=54,nouvelles_lectures_dev=0,lectures_test=0,NO_GO_Android=True)
    save(out/'decision.json',decision)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--archives',type=Path,default=ROOT/'archives/dice')
    a=p.parse_args()
    replay(a.out,a.archives)
