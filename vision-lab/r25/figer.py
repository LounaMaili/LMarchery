"""Sceller avant inférence les ressources, l'instrumentation et le diagnostic des archives."""
import argparse
from pathlib import Path

import torch

from r19.donnees import fingerprint, read, save
from r19.pilote import setup
from r20.corpus import references
from r21.plan import tensor_state
from r22.verifier import environment
from r25.commun import ARMS, ROOT, SEEDS, REUSE, captures, historical
from r25.perimetre import reused, rgb_inputs, weights


def freeze(worktrees):
    setup(9301)
    env=environment()
    if env!=read('r24/comparabilite.json')['environnement']:
        raise ValueError('Environnement CPU différent')
    states=weights(worktrees)
    for st in states.values():
        if tensor_state(torch.load(worktrees/st['fichier'],map_location='cpu',weights_only=True))['sha256']!=st['etat']:
            raise ValueError('Empreinte tensorielle différente')
    _,inputs=rgb_inputs(worktrees,references())
    inputs_files=[Path(n) for n in ('r18/validites.json','r18/sources.json','r19/donnees.json',
        'r19/donnees.py','r19/modele.py','r19/pilote.py','r19/decoder.py','r19/mesures.py','r19/affecter.py',
        'r20/corpus.py','r22/verifier.py','r23/execution.json','r23/parite.json','r24/comparabilite.json')]
    for arm in ARMS:
        for s in SEEDS:
            old=historical(arm,s)
            inputs_files.extend([old/'controle.json',old/'predictions.json',
                                Path('r22' if arm=='dice' else 'r24')/'mesures'/f'{arm}-{s}-768.json'])
    files=[*sorted(ROOT.glob('*.py')),ROOT/'protocole.md',ROOT/'diagnostic-archives.json',
           Path('test_r25.py'),*inputs_files]
    save(ROOT/'configuration.json',dict(tete_R24='33946bbe308847bb406384a8f6bb70fa0188b5af',
        preuves_R24='2323ba7afbce831b96e10bc8a8bf1badf4da0339',bras=list(ARMS),graines=list(SEEDS),etat=768,
        poids=states,photos=inputs,environnement=env,reemploi=reused(worktrees),
        cas=[dict(bras=a,graine=s,photo=k,appel=(a,s,k)!=REUSE) for a,s,k in captures()],
        plafond_demande=22,appels_prevus=21,entrainement=False,partition='train',
        geometrie=dict(distance704=6,angle_non_oriente_deg=20,sondes=21,sondes_requises=11,
                       connectivite=8,seuil_logits=0,exaequo_segment_descriptif_px=1e-10),
        empreintes_gel={str(p):fingerprint(p) for p in files}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('worktrees',type=Path)
    freeze(parser.parse_args().worktrees.resolve())
