"""Fichiers et conventions partagés entre les deux environnements du laboratoire."""
import hashlib
import json
from pathlib import Path

import numpy as np

ROOT = Path('r29')
LOCAL = Path('artifacts/r29/passage')
SEEDS = (9302, 9303)
STAGES = ('rpn', 'regression', 'bornage', 'admission', 'nms', 'final')
LABELS = ('RPN retenu', 'Régression RoI', 'Bornage image', 'Admission', 'NMS', 'Douze sorties')
HISTORICAL = ('rpn_boites','rpn_logits','rpn_ancres','boites','scores','propositions','probabilites','masques')


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    path = Path(path)
    path.parent.mkdir(exist_ok=True, parents=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)+'\n')


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(4 << 20), b''):
            h.update(block)
    return h.hexdigest()


def array_hash(value):
    return hashlib.sha256(np.ascontiguousarray(value).tobytes()).hexdigest()


def identical(a, b):
    return a.shape == b.shape and a.dtype == b.dtype and array_hash(a) == array_hash(b)


def checked(path, record):
    path = Path(path)
    if path.stat().st_size != record['octets'] or digest(path) != record['sha256']:
        raise ValueError(f'Empreinte divergente : {path}')
    return path
