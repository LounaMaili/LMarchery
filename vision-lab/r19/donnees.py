"""Repères en centres de pixels, image publique de travail et plan par provenance."""
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path

import cv2 as cv
import numpy as np

from r14.mesurer import read, save
from r15.corpus import fingerprint
from r7.prepare import resized

ROOT = Path('r19')
R18 = Path('r18')
SEEDS = (9301, 9302, 9303)
SLOTS = 12
SIDE = 512
INITIAL = '14fb7ec245d31274b0481096b7fe878523ae99670bb33236c9201d78ec0dded1'
WEIGHT = Path('vision-lab-r9/vision-lab/artifacts/r9/train/ordinary-9301.pt')
BASELINE = Path('r16/predictions/ordinary-9301.json')


def archive(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False)+'\n')


def work_to(xy, src, dst):
    return (np.asarray(xy, dtype=float) + .5) * (np.asarray(dst) / src) - .5


def eval_size(size):
    return np.rint(np.asarray(size) * min(1., 704/max(size))).astype(int)


def input_size(size):
    return tuple(max(16, 16*round(n*SIDE/(16*max(size)))) for n in size)


def cell_indices(size, grid):
    cols, rows = size
    gw, gh = grid
    x = np.minimum(((np.arange(cols)+.5)*gw/cols).astype(int), gw-1)
    y = np.minimum(((np.arange(rows)+.5)*gh/rows).astype(int), gh-1)
    return (y[:, None]*gw+x).astype(np.int32)


def load_image(spec, worktrees):
    path = worktrees/spec['fichier_reference']
    if fingerprint(path) != spec['sha256_reference']:
        raise ValueError(f"Photographie modifiée {spec['photo']}")
    bgr = resized(cv.imread(str(path)))
    if list(bgr.shape[1::-1]) != spec['dimensions_travail'] or sha256(bgr.tobytes()).hexdigest() != spec['sha256_BGR_travail']:
        raise ValueError(f"Pixels orientés différents {spec['photo']}")
    return bgr


def plan(photos, seed):
    groups = defaultdict(list)
    for p in photos:
        if p['partition'] == 'train':
            groups[p['provenance']].append(p['photo'])
    rng = np.random.default_rng(seed)
    result = []
    for cycle in range(12):
        for group in rng.permutation(sorted(groups)):
            # Alterner Crigges à chaque tour, y compris lorsque son ordre dans le bloc change.
            options = sorted(groups[group])
            result.append(dict(pas=len(result)+1, provenance=str(group), photo=options[cycle % len(options)]))
    return result


def dimensions_and_flags():
    from r15.corpus import corpus
    return {r['id']:r['conditions'] for r in corpus()}


def scope():
    sources = {p['photo']:p for p in read(R18/'sources.json')}
    flags = dimensions_and_flags()
    result = []
    for p in read(R18/'perimetre.json')['acquisitions']:
        k = p['photo']
        result.append(dict(photo=k, partition=p['partition'], provenance=p['provenance'], role=p['role'],
            supports=p['supports'], conditions=flags[k], dimensions_travail=p['dimensions_travail'],
            fichier_reference=p['fichier_reference'], sha256_reference=p['sha256_reference'],
            sha256_BGR_travail=sources[k]['sha256_BGR_travail'], sha256_source=p['sha256_source'],
            sha256_annotation=fingerprint(R18/'annotations'/(k+'.json')),
            sha256_masque=fingerprint(R18/'masques'/(k+'.png'))))
    return result


def freeze():
    photos = scope()
    save(ROOT/'donnees.json', dict(checkpoint_initial=INITIAL, reference_B=fingerprint(BASELINE),
         validites=fingerprint(R18/'validites.json'), photos=photos))
    save(ROOT/'tirages.json', {str(seed):plan(photos,seed) for seed in SEEDS})


def verify():
    seal = read(ROOT/'donnees.json')
    if seal['photos'] != scope() or seal['validites'] != fingerprint(R18/'validites.json') or seal['reference_B'] != fingerprint(BASELINE):
        raise ValueError('Pièces R18 ou sorties B différentes du gel')
    if read(ROOT/'tirages.json') != {str(s):plan(seal['photos'],s) for s in SEEDS}:
        raise ValueError('Plans modifiés')
    return seal['photos']


if __name__ == '__main__':
    freeze()
