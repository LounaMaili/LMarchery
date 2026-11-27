"""Recoller les 432 grilles archivées et pleines. Aucun modèle ni poids."""
import argparse
import hashlib
import inspect
import os
import platform
from pathlib import Path

import cv2
import detectron2
import numpy as np
import torch
import torchvision
from detectron2.layers.mask_ops import paste_masks_in_image
from detectron2.structures import Boxes

from r28.commun import ROOT, REFERENCE, digest, save, scope, tag


def paste(archive, hw):
    with np.load(archive, allow_pickle=False) as data:
        boxes = torch.from_numpy(data['boites'])
        prob = torch.from_numpy(data['probabilites'])
        stored = data['masques']
    assert boxes.dtype == prob.dtype == torch.float32 and tuple(prob.shape) == (12,28,28)
    assert torch.isfinite(prob).all() and prob.min() >= 0 and prob.max() <= 1
    assert boxes.shape == (12,4) and np.all(stored < (1<<12)) and stored.shape == hw
    learned = paste_masks_in_image(prob, Boxes(boxes), hw, threshold=.5).numpy()
    full = paste_masks_in_image(torch.ones_like(prob), Boxes(boxes), hw, threshold=.5).numpy()
    bits = np.zeros(hw, dtype=np.uint16)
    audit = []
    for q, (m, omega) in enumerate(zip(learned, full)):
        historical = (stored & (1<<q)) != 0
        changed = int(np.count_nonzero(historical != m))
        outside = int(np.count_nonzero(historical & ~omega))
        if changed or outside:
            raise ValueError(f'{archive}, q={q} : {changed} divergences, {outside} hors support')
        bits |= omega.astype(np.uint16) << q
        audit.append(dict(q=q, pixels_appris=int(m.sum()), pixels_omega=int(omega.sum()),
                          divergences_R27=changed, appris_hors_omega=outside))
    return bits, audit


def run(out):
    versions = [platform.python_version(), torch.__version__, torchvision.__version__, detectron2.__version__]
    assert versions == ['3.9.19','1.10.1+cpu','0.11.2+cpu','0.6']
    assert all(os.environ.get(k) == '1' for k in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS'))
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    cv2.setNumThreads(1)
    out.mkdir(exist_ok=True, parents=True)
    records = {}
    for seed, step, key, spec, source in scope():
        code = tag(seed, step, key)
        bits, rows = paste(source, tuple(spec['dimensions'][::-1]))
        name = code + '.png'
        assert cv2.imwrite(str(out/name), bits, [cv2.IMWRITE_PNG_COMPRESSION, 7])
        assert np.array_equal(bits, cv2.imread(str(out/name), cv2.IMREAD_UNCHANGED))
        records[code] = dict(source=str(source), sha256_source=digest(source), fichier=name,
            octets=(out/name).stat().st_size, sha256=digest(out/name),
            sha256_pixels_uint16_le=hashlib.sha256(bits.astype('<u2').tobytes()).hexdigest(), sorties=rows)
    assert len(records)==36 and sum(len(r['sorties']) for r in records.values())==432
    save(out/'index.json', dict(reference=REFERENCE, versions=versions, numpy=np.__version__, opencv=cv2.__version__,
         sha256_mask_ops=digest(inspect.getfile(paste_masks_in_image)), seuil_inclusif=.5,
         etats=36, sorties=432, divergences_R27=0, pixels_appris_hors_omega=0, passages_modele=0, supports=records))


if __name__ == '__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie', type=Path, nargs='?', default=ROOT/'supports')
    run(cli.parse_args().sortie)
