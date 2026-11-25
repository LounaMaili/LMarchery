"""Conditionner les douze états observés et vérifier pertes, expositions et empreintes."""
import argparse
from collections import Counter
import json
from pathlib import Path
from shutil import copyfile

from r19.donnees import SEEDS, fingerprint, read, save
from r19.publier import item
from r20.conditionner import capsule
from r20.rejouer import compact
from r21.inventorier import ROOT
from r21.plan import ARMS, STEPS

TASKS=('masque','axe','entree','presence','total','norme_gradient')


def summarize(rows):
    if not rows:raise ValueError('Groupe vide')
    return dict(presentations=len(rows),positifs=sum(len(r['positives']) for r in rows),
                negatifs=sum(len(r['negatives']) for r in rows),
                masques=sum(r['masques'] for r in rows),axes=sum(r['axes'] for r in rows),entrees=sum(r['entrees'] for r in rows),
                gradients_ecretes=sum(r['ecrete'] for r in rows),
                moyennes={k:sum(r[k] for r in rows)/len(rows) for k in TASKS})


def histories(archive,out):
    config=read(ROOT/'configuration.json');registry=read(ROOT/'inventaires.json');plans=read(ROOT/'tirages.json')
    stats={};parity={}
    for arm in ARMS:
        stats[arm]={}
        for seed in map(str,SEEDS):
            folder=archive/arm/seed
            ledger=read(folder/'execution.json')
            if (ledger['etat_initial']!=config['etats_initiaux_par_graine'][seed] or ledger['nombre_pas']!=768 or
                    ledger['vues_train_lues_aux_controles']!=18 or ledger['expositions']!=config['expositions']):
                raise ValueError('Budget ou initialisation')
            if arm=='temoin':
                parity[seed]=read(folder/'96/parite.json')
                if not parity[seed]['identique'] or parity[seed]['conteneur_historique']!=config['poids_temoin_historiques'][seed]['sha256']:
                    raise ValueError('Parité R19 non démontrée')
            rows=[json.loads(s) for s in (folder/'progression.jsonl').read_text().splitlines()]
            if [{k:r[k] for k in ('pas','provenance','photo')} for r in rows]!=plans[seed]:
                raise ValueError('Ordre réel différent du plan')
            for r in rows:
                rec=registry[r['photo']];pos=r['positives'];neg=r['negatives']
                if set(pos)!={i['instance'] for i in rec['identites'] if i['champs']}:raise ValueError('Référence oubliée')
                if (len(set(pos.values()))!=len(pos) or set(pos.values())&set(neg) or len(set(neg))!=len(neg) or
                        r['presences_negatives']!=len(neg) or r['identites']!=len(pos)):raise ValueError('Affectation irrégulière')
                if arm=='candidat' and rec['admissible'] and set(pos.values())|set(neg)!=set(range(12)):
                    raise ValueError('Supervision exhaustive incomplète')
                if abs(r['total']-(r['masque']+r['axe']+r['entree']+.25*r['presence']))>2.e-5:raise ValueError('Décomposition de perte')
            slices={}
            for label,span in [('1-96',rows[:96]),('97-768',rows[96:]),('1-768',rows)]:
                subsets={'toutes':span,'certifiees':[r for r in span if registry[r['photo']]['admissible']],
                         'partielles':[r for r in span if not registry[r['photo']]['admissible']]}
                for r in span:
                    subsets.setdefault('source/'+r['provenance'],[]).append(r)
                    subsets.setdefault('photo/'+r['photo'],[]).append(r)
                slices[label]={g:summarize(group) for g,group in subsets.items()}
            for s in STEPS:
                check=read(folder/str(s)/'controle.json')
                if ledger['controles'][str(s)]!=check:raise ValueError('État de contrôle divergent')
                for k in registry:
                    actual=[r for r in rows[:s] if r['photo']==k]
                    if [len(actual),sum(len(r['positives']) for r in actual),sum(len(r['negatives']) for r in actual)]!=[
                        check[metric][k] for metric in ('expositions','positives','negatives')]:raise ValueError('Comptes de perte incohérents')
            stats[arm][seed]=slices
    compact(out/'pertes-et-expositions.json',stats);save(out/'parite-R19.json',parity)
    return stats


def publish(src,out,worktrees):
    config=read(ROOT/'configuration.json')
    initial=config['initial_R9'];inventory=[item(worktrees/initial['fichier'],'Tronc initial commun R9')]
    for seed,p in config['poids_temoin_historiques'].items():
        inventory.append(item(worktrees/p['fichier'],'Contrôle de parité historique R19 '+seed))
    for arm in ARMS:
        for seed in map(str,SEEDS):
            origin=src/arm/seed
            for step in STEPS:
                f=origin/str(step);control=read(f/'controle.json')
                for key in ('poids','optimiseur'):
                    entry=item(f/(key+'.pt'),f'{arm}, graine {seed}, pas {step}, '+key)
                    if entry['sha256']!=control[key+'_sha256']:raise ValueError(entry['nom'])
                    inventory.append(entry)
            for p in sorted(v for v in origin.rglob('*') if v.is_file() and v.suffix!='.pt'):
                dest=out/'archives'/p.relative_to(src)
                dest.parent.mkdir(parents=True,exist_ok=True);copyfile(p,dest)
    archive=src.parent/'r21-etats-et-sorties.zip'
    capsule(src,archive)
    inventory.append(item(archive,'Six trajectoires, douze états réseau et optimiseur, sorties et mesures de perte'))
    save(out/'inventaire-local.json',dict(fichiers=inventory,photos='configuration.json',corpus='../r18/inventaire-local.json'))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('src',type=Path);p.add_argument('out',type=Path);p.add_argument('--worktrees',type=Path,default=Path('../..'))
    a=p.parse_args();publish(a.src,a.out,a.worktrees);histories(a.out/'archives',a.out)
