"""Périmètre fixé de six réseaux et onze couples photo–graine, tous sur le train."""
from pathlib import Path

from r19.donnees import read

ROOT = Path('r25')
ARMS = ('dice', 'bloc8')
SEEDS = (9301, 9302, 9303)
TRANSITIONS = {
    9301: ('5115614018','7245527662','wc11001401','wc71488242'),
    9302: ('4169106197','7245527662','wc11001401','wc71488242'),
    9303: ('wc11001401','wc71488242'),
}
ZIG = '18105652008'
REUSE = ('dice',9302,'wc71488242')


def captures():
    for seed, photos in TRANSITIONS.items():
        for k in photos:
            for arm in ARMS:
                yield arm,seed,k
    for arm in ARMS:
        yield arm,9303,ZIG


def tag(arm,seed,k):
    return f'{arm}-{seed}-{k}'


def historical(arm,seed):
    stage = {'dice':'r22','bloc8':'r24'}[arm]
    return Path(stage)/'archives'/arm/str(seed)/'768'


def matrices(arm,seed):
    stage = {'dice':'r22','bloc8':'r24'}[arm]
    return read(Path(stage)/'mesures'/f'{arm}-{seed}-768.json')
