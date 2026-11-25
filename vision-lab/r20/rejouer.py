"""Recalculer toutes les mesures de décision depuis les sorties publiées, sans inférence."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from r19.donnees import SEEDS, archive, read, save
from r19.mesures import candidate
from r19.publier import readable
from r20.corpus import ROOT, STEPS, references
from r20.mesurer import aggregate, photo


def compact(path,data):
    archive(path,data)
    readable(path)


def replay(archives,out):
    seal=read(ROOT/'configuration.json')
    cap=read(ROOT/'plafonds.json')
    refs=references()
    a=read(archives/'A/execution.json')
    expected=[(s,k) for s in SEEDS for k in refs]
    if [(x['graine'],x['photo']) for x in a['lectures']]!=expected or a['mises_a_jour']!=0:
        raise ValueError('Registre A différent des 27 couples prévus')
    summary={'A':{},'B':{}}
    dest=out/'mesures'
    dest.mkdir(parents=True,exist_ok=True)
    for s in map(str,SEEDS):
        folder=archives/'A'/s
        costs=read(folder/'pertes.json')
        pred=candidate(refs,folder)
        reports={k:photo(ref,pred[k],cap[k]['instances'],seal['checkpoints_A'][s]['seuil_R19'],costs[k]) for k,ref in refs.items()}
        by_source=defaultdict(list)
        for k,row in reports.items():
            by_source[refs[k]['spec']['provenance']].append(row)
        totals=aggregate(reports.values())
        compact(dest/('A-'+s+'.json'),dict(seuil_herite=seal['checkpoints_A'][s]['seuil_R19'],par_photo=reports,
                par_provenance={k:aggregate(v) for k,v in by_source.items()},total=totals))
        summary['A'][s]=totals
    b=read(archives/'B/execution.json')
    steps=b['controles'];last=b['mises_a_jour'];key=seal['photo_B']
    if steps!=list(STEPS[:len(steps)]) or steps[-1]!=last or b['passages_avant']!=last+1:
        raise ValueError('Trajectoire B imprévue')
    rows=[json.loads(s) for s in (archives/'B/progression.jsonl').read_text().splitlines()]
    if [r['pas'] for r in rows]!=list(range(1,last+1)):
        raise ValueError('Mises à jour B non contiguës')
    for step in steps:
        folder=archives/'B'/f'{step:04}'
        pred=candidate({key:refs[key]},folder)[key]
        costs=read(folder/'pertes.json')[key]
        report=photo(refs[key],pred,cap[key]['instances'],seal['checkpoints_A']['9301']['seuil_R19'],costs)
        frozen=read(folder/'controle.json')
        passed=report['bilan']['triplets']==len(seal['cibles_B'])
        if (report['bilan']['triplets']!=frozen['conjoint'] or passed!=frozen['succes'] or
            (passed and step!=last) or (step==last and not passed and step!=STEPS[-1])):
            raise ValueError('Règle d’arrêt violée')
        compact(dest/(f'B-{step:04}.json'),report)
        updates=rows[:step]
        prev=steps[steps.index(step)-1] if step else 0
        summary['B'][str(step)]=dict(**report['bilan'],succes=passed,
                mises_a_jour=step,gradients_ecretes_cumul=sum(r['ecrete'] for r in updates),
                gradients_ecretes_intervalle=sum(r['ecrete'] for r in updates[prev:]),
                negatifs_reellement_utilises_cumul=sum(r['presences_negatives'] for r in updates),
                norme_gradient_etat=frozen['gradient_avant_ecretage'])
    summary['decision']=dict(photo=key,instances_completes=seal['cibles_B'],reussite=passed,pas_arret=last,
            critere_arret='premier_controle_conjoint' if passed else 'plafond_1024',
            nouvelles_inferences_dev=0,inferences_test=0,passages_A=27,ajustements_B=1)
    save(dest/'synthese.json',summary)
    return summary


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--archives',type=Path,default=ROOT/'archives')
    a=parser.parse_args();replay(a.archives,a.out)
