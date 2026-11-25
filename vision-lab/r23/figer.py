"""Sceller les sources, les poids et les transformations avant les huit passages."""
import argparse
from pathlib import Path

from r19.donnees import fingerprint, read, save
from r19.pilote import setup
from r22.verifier import environment
from r23.commun import ARMS, PHOTOS, ROOT, SEED, STEPS, archive
from r23.perimetre import describe_inputs, inputs, references, weights


def freeze(worktrees):
    setup(SEED)
    env=environment()
    if env!=read('r22/comparabilite.json')['environnement']:
        raise ValueError('Environnement numérique différent')
    refs=references()
    files={Path(v) for v in ['r18/validites.json','r18/sources.json','r20/plafonds.json','r21/configuration.json',
           'r21/inventaires.json','r22/configuration.json','r22/inventaire-local.json','r22/comparabilite.json',
           'r7/model.py','r7/prepare.py','r19/modele.py','r19/donnees.py','r19/pilote.py','r19/supervision.py',
           'r19/decoder.py','r19/mesures.py','r19/affecter.py','r20/operations.py','r20/mesurer.py','r20/instrumenter.py',
           'r21/mesures.py','r21/regle.py','r21/plan.py','r22/regle.py','r22/verifier.py']}
    files.update(ROOT/n for n in ('commun.py','perimetre.py','historique.py','capturer.py','parite.py','extraire.py',
                                   'spatial.py','mesures.py','figer.py','protocole.md','historique.json'))
    for arm in ARMS:
        files.add(archive(arm)/'progression.jsonl')
        for t in STEPS:
            folder=archive(arm)/str(t)
            files.update(folder/n for n in ('predictions.json','pertes.json','controle.json'))
            files.update(folder/'masques'/(k+'.png') for k in PHOTOS)
            files.add(Path(f'r21/mesures/temoin-9302-{t}.json' if arm=='temoin' else f'r22/mesures/dice-9302-{t}.json'))
    for k in PHOTOS:
        files.update([Path(f'r18/annotations/{k}.json'),Path(f'r18/masques/{k}.png')])
    save(ROOT/'configuration.json',dict(base='b2a2165f6d64f653d0c7905bc5ef67b43c157204',
        preuves_R22='102e0c9767f27bb5df4cea40ea19c03a958b7bf0',graine=SEED,bras=list(ARMS),etats=list(STEPS),
        maximum_passages=8,ajustements=0,rayon_px704=10,voisinage_mode=8,
        entrees=describe_inputs(inputs(worktrees,refs),refs),environnement=env,poids=weights(worktrees),
        droits={r['photo']:r['droits'] for r in read('r18/sources.json') if r['photo'] in PHOTOS},
        empreintes={str(f):fingerprint(f) for f in sorted(files)}))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('worktrees',type=Path)
    freeze(p.parse_args().worktrees)
