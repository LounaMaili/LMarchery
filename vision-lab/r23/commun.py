"""Périmètre fixe et pointeurs vers les archives historiques de la graine 9302."""
from pathlib import Path
from r19.donnees import read

ROOT=Path('r23')
SEED=9302
GAGNON='wc71488242'
LYNCH='5115614018'
PHOTOS=(GAGNON,LYNCH)
ARMS=('temoin','dice')
STEPS=(96,768)
FOCUS={GAGNON:(11,9),LYNCH:(4,)}


def archive(arm):
    return (Path('r21/archives/temoin') if arm=='temoin' else Path('r22/archives/dice'))/str(SEED)


def measures(arm,step):
    if arm=='temoin':
        return read(f'r21/mesures/temoin-{SEED}-{step}.json')['par_photo']
    return read(f'r22/mesures/dice-{SEED}-{step}.json')


def cases():
    for arm in ARMS:
        for step in STEPS:
            for photo in PHOTOS:
                yield arm,step,photo


def tag(arm,step,photo):
    return f'{arm}-{step}-{photo}'
