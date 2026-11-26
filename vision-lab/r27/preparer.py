"""Préparer uniquement les neuf entrées train dans l'environnement OpenCV R19."""
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from r19.donnees import load_image, input_size

DATA = Path('data/r27/inputs')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare():
    assert cv2.__version__ == '4.13.0'
    cv2.setNumThreads(1)
    root = Path('r26/train.json')
    sealed = json.loads(root.read_text())
    draws = Path(sealed['tirages'])
    assert digest(draws) == sealed['sha256_tirages']
    specs = {s['photo']: s for s in json.loads(Path('r19/donnees.json').read_text())['photos']
             if s['partition'] == 'train'}
    assert set(specs) == set(sealed['photos']) and len(specs) == 9
    DATA.mkdir(parents=True, exist_ok=True)
    prepared = {}
    for key, record in sealed['photos'].items():
        spec = specs[key]
        assert spec['sha256_BGR_travail'] == record['sha256_BGR_travail']
        for field in ('masque', 'annotation'):
            assert digest(Path(record[field])) == record['sha256_' + field]
        bgr = load_image(spec, Path.cwd().parents[1])
        shape = input_size(bgr.shape[1::-1])
        assert list(shape) == record['entree_modele_xy']
        raster = cv2.resize(bgr, shape, interpolation=cv2.INTER_AREA)
        path = DATA / (key + '.npy')
        np.save(path, raster, allow_pickle=False)
        prepared[key] = dict(fichier=str(path), octets=path.stat().st_size, sha256=digest(path),
                             sha256_pixels=hashlib.sha256(raster.tobytes()).hexdigest(),
                             dimensions_xy=list(shape), sha256_original=spec['sha256_reference'])
    info = dict(opencv=cv2.__version__, numpy=np.__version__, sha256_train=digest(root),
                sha256_tirages=digest(draws), photos=prepared)
    Path('r27/entrees.json').write_text(json.dumps(info, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    prepare()
