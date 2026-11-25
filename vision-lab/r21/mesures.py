"""Rejouer la mesure de présence et la décision depuis les sorties décodées du train."""
import argparse
from collections import Counter
from pathlib import Path

import numpy as np

from r19.affecter import pairs
from r19.donnees import SEEDS, read, save
from r19.mesures import candidate
from r20.corpus import references
from r20.mesurer import photo
from r20.rejouer import compact
from r21.inventorier import ROOT
from r21.plan import ARMS, STEPS


def matching(matrix,validities,probabilities,threshold=.5):
    names=list(validities)
    eligible=np.zeros((len(names),len(probabilities)),bool)
    distance=np.zeros_like(eligible,dtype=float)
    for i,k in enumerate(names):
        fields=validities[k]
        if not fields:raise ValueError('Identité sans cible dans le dénominateur')
        for c in matrix[k]:
            q=c['q'];checks=[];cost=[]
            if 'masque' in fields:
                checks.append(c['masque_90_pourcent']);cost.append(1-min(1.,c['fraction_plafond']))
            if 'axe' in fields:
                checks.append(c['sondes_axe']>=11);cost.append(1-c['sondes_axe']/21)
            if 'entree' in fields:
                checks.append(c['erreur_entree_px704']<=10);cost.append(min(1.,c['erreur_entree_px704']/10))
            eligible[i,q]=all(checks) and probabilities[q]>=threshold
            distance[i,q]=sum(cost)/len(cost)
    found={names[i]:q for i,q in pairs(eligible,distance)}
    emitted=[q for q,p in enumerate(probabilities) if p>=threshold]
    return dict(appariements=found,manquees=[n for n in names if n not in found],
                sorties_emises=emitted,supplementaires=[q for q in emitted if q not in found.values()],
                requetes_admissibles={n:np.flatnonzero(eligible[i]).tolist() for i,n in enumerate(names)})


def measure_views(refs,registry,folder,seed):
    config=read(ROOT/'configuration.json')
    threshold=config['poids_temoin_historiques'][str(seed)]['seuil_R19']
    caps=read('r20/plafonds.json')
    predictions=candidate(refs,folder)
    costs=read(folder/'pertes.json')
    rows={}
    for k,ref in refs.items():
        pred=predictions[k];cost=costs[k]
        r=photo(ref,pred,caps[k]['instances'],threshold,cost)
        fields={t['instance']:t['champs'] for t in registry[k]['identites'] if t['champs']}
        probabilities=[s['presence'] for s in pred['contacts']]
        m=matching(r['paires_par_identite'],fields,probabilities)
        r['presence_05']=m
        for q,slot in enumerate(r['sorties']):
            slot.update(presence=probabilities[q],logit_presence=cost['logits_presence'][q],
                        pic_spatial=pred['contacts'][q]['pic_spatial'])
        r['bilan'].update(physiques_actives=len(fields),representees_p05=len(m['appariements']),
            manquees_p05=len(m['manquees']),emises_p05=len(m['sorties_emises']),supplementaires_p05=len(m['supplementaires']),
            silencieuses_p05=int(not m['sorties_emises']),positives_au_controle=len(cost['par_identite']),
            non_appariees_avant_seuil=r['couverture']['non_appariees'],faux_avant_seuil=r['couverture']['faux_etablis'],
            non_resolus_avant_seuil=r['couverture']['non_resolus'])
        rows[k]=r
    return rows


def summary(rows,keys):
    totals=Counter();represented=[];triplets=[]
    for k in keys:
        row=rows[k];totals.update(row['bilan'])
        represented.extend(row['presence_05']['appariements'])
        triplets.extend(name for name,i in row['identites'].items() if i['conjoint_q'] is not None)
    if len(represented)!=len(set(represented)) or len(triplets)!=len(set(triplets)):
        raise ValueError('Identité interphoto ambiguë')
    return dict(effectifs=dict(totals),identites_representees=sorted(represented),identites_triplets=sorted(triplets))


def by_group(rows,registry):
    categories={'toutes':list(rows),'certifiees':[k for k in rows if registry[k]['admissible']],
                'partielles':[k for k in rows if not registry[k]['admissible']]}
    for k in rows:categories.setdefault('source/'+registry[k]['provenance'],[]).append(k)
    return {g:summary(rows,keys) for g,keys in categories.items()}


def contrast(before,after):
    detail={}
    for k in before:
        lhs,rhs=before[k],after[k]
        change={m:rhs['effectifs'][m]-v for m,v in lhs['effectifs'].items()}
        row=dict(variation=change)
        for name,field in [('presence','identites_representees'),('triplets','identites_triplets')]:
            a,b=set(lhs[field]),set(rhs[field])
            row[name]=dict(gagnees=sorted(b-a),perdues=sorted(a-b),preservees=sorted(a&b))
        detail[k]=row
    return detail


def passes(compare,candidate_summary):
    verified=compare['certifiees']
    return (verified['variation']['supplementaires_p05']<0 and not verified['presence']['perdues'] and
            candidate_summary['certifiees']['effectifs']['representees_p05']>0 and not compare['toutes']['triplets']['perdues'])


def replay(archives,out):
    registry=read(ROOT/'inventaires.json');refs=references();dest=out/'mesures';dest.mkdir(parents=True,exist_ok=True)
    groups={}; changes={}
    for arm in ARMS:
        groups[arm]={}
        for seed in map(str,SEEDS):
            groups[arm][seed]={}
            for step in map(str,STEPS):
                folder=archives/arm/seed/step
                rows=measure_views(refs,registry,folder,int(seed))
                grouped=by_group(rows,registry)
                groups[arm][seed][step]=grouped
                compact(dest/f'{arm}-{seed}-{step}.json',dict(bras=arm,graine=int(seed),pas=int(step),
                        par_photo=rows,groupes=grouped))
    wins=[]
    for seed in map(str,SEEDS):
        witness=groups['temoin'][seed];candidate_end=groups['candidat'][seed]['768']
        exposure=contrast(witness['96'],witness['768'])
        policy=contrast(witness['768'],candidate_end)
        ok=passes(policy,candidate_end)
        changes[seed]=dict(exposition_temoin=exposure,effet_regle_final=policy,critere_apparie=ok)
        if ok:wins.append(int(seed))
    decision=dict(phase='train',pas_decision=768,graines_conformes=wins,critere_confirme=len(wins)>=2,
                  ajustements=6,mises_a_jour=4608,nouvelles_inferences_dev=0,inferences_test=0)
    compact(dest/'groupes.json',groups);compact(dest/'contrastes.json',changes)
    save(out/'decision.json',decision)
    return groups,changes,decision


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',nargs='?',type=Path,default=ROOT)
    p.add_argument('--archives',type=Path,default=ROOT/'archives')
    a=p.parse_args();replay(a.archives,a.out)
