"""Pilote séquentiel CPU. Un seul lancement neuf, trois graines et cinq contrôles."""
import gzip
import json
import math
import os
import platform
import random
import resource
import subprocess
import sys
import time
from pathlib import Path

import cv2
import detectron2
import numpy as np
import torch
import torchvision

from r27.modele import autonomous, file_hash, fingerprint, frozen, group, initialize, objective
from r27.supervision import NativeTargets

OUT = Path('artifacts/r27')
SEEDS = (9301, 9302, 9303)
CHECKS = (0, 96, 256, 512, 768)


def read(path):
    return json.loads(Path(path).read_text())


def write(path, content):
    Path(path).write_text(json.dumps(content, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def configure():
    versions = [platform.python_version(), torch.__version__, torchvision.__version__, detectron2.__version__]
    assert versions == ['3.9.19', '1.10.1+cpu', '0.11.2+cpu', '0.6']
    assert all(os.environ.get(v) == '1' for v in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS'))
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    cv2.setNumThreads(1)
    torch.use_deterministic_algorithms(True)
    assert torch.get_num_threads() == 4 and torch.get_num_interop_threads() == cv2.getNumThreads() == 1
    assert torch.are_deterministic_algorithms_enabled() and torch.get_default_dtype() == torch.float32
    return dict(versions=versions, numpy=np.__version__, opencv=cv2.__version__, plateforme=platform.platform(),
                machine=platform.machine(), threads_torch=4, threads_interop=1, threads_cv=1,
                omp_blas_mkl=1, deterministe=True, precision='float32')


def seed_rng(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def optimizer(model):
    groups = []
    for bias in (False, True):
        params = [p for n, p in model.named_parameters() if p.requires_grad and n.endswith('.bias') == bias]
        groups.append({'params': params, 'weight_decay': 0. if bias else .0001})
    return torch.optim.SGD(groups, lr=.001, momentum=.9, dampening=0., nesterov=False)


def norm(values):
    return math.sqrt(sum(float(t.detach().norm()) ** 2 for t in values))


def update(model, optim, bgr, target, expected):
    start = time.monotonic()
    params = [(n, p) for n, p in model.named_parameters() if p.requires_grad]
    optim.zero_grad(set_to_none=True)
    losses, sampling = objective(model, bgr, target)
    loss = sum(losses.values())
    if not torch.isfinite(loss):
        raise FloatingPointError('Perte non finie')
    loss.backward()
    assert all(p.grad is not None for _, p in params)
    assert all(p.grad is None for _, p in frozen(model))
    by_group = {g: [p for n, p in params if group(n) == g] for g in ('rpn', 'boite', 'masque')}
    gradients = {g: norm(p.grad for p in pp) for g, pp in by_group.items()}
    unclipped = float(torch.nn.utils.clip_grad_norm_([p for _, p in params], 5., error_if_nonfinite=True))
    after_clip = norm(p.grad for _, p in params)
    assert after_clip <= 5.00002
    before = [p.detach().clone() for _, p in params]
    optim.step()
    movement = {g: norm(p.detach() - old for (n, p), old in zip(params, before) if group(n) == g)
                for g in by_group}
    if not all(math.isfinite(v) for v in movement.values()):
        raise FloatingPointError('Paramètre non fini')
    checksum = fingerprint(frozen(model))
    assert checksum == expected, 'Modification d’un tenseur gelé'
    return dict(pertes={k: float(v.detach()) for k, v in losses.items()}, echantillonnage=sampling,
                gradients=gradients, norme_avant=unclipped, norme_apres=after_clip, ecrete=unclipped > 5.,
                deplacement=movement, sha256_geles=checksum, secondes=time.monotonic() - start,
                rss_max_Kio=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)


def load_train():
    prepared = read('r27/entrees.json')
    assert file_hash('r26/train.json') == prepared['sha256_train']
    seal = read('r26/train.json')
    assert file_hash(seal['tirages']) == seal['sha256_tirages'] == prepared['sha256_tirages']
    assert set(prepared['photos']) == set(seal['photos']) and len(seal['photos']) == 9
    photos, targets = {}, {}
    for key, spec in seal['photos'].items():
        entry = prepared['photos'][key]
        assert file_hash(entry['fichier']) == entry['sha256']
        image = np.load(entry['fichier'], allow_pickle=False)
        assert list(image.shape[1::-1]) == spec['entree_modele_xy'] and image.dtype == np.uint8
        for field in ('masque', 'annotation'):
            assert file_hash(spec[field]) == spec['sha256_' + field]
        mask = cv2.imread(spec['masque'], cv2.IMREAD_UNCHANGED)
        assert list(mask.shape[1::-1]) == spec['dimensions'] and mask.dtype == np.uint8
        photos[key] = image
        targets[key] = NativeTargets(mask, spec['instances'], spec['entree_modele_xy'])
    return photos, targets, seal['photos'], read(seal['tirages'])


def control(model, optim, seed, step, photos, specs, path):
    state = dict(modele=model.state_dict(), optimiseur=optim.state_dict(), rng_torch=torch.get_rng_state(),
                 rng_numpy=np.random.get_state(), rng_python=random.getstate(), graine=seed, pas=step)
    torch.save(state, path / ('pas-%03d.pt' % step))
    model.eval()
    for key, bgr in photos.items():
        result = autonomous(model, bgr, specs[key]['dimensions'])
        np.savez_compressed(path / ('%03d-%s.npz' % (step, key)), **result)
    model.train()
    model.backbone.eval()
    print(f'{seed} pas {step}, {len(photos)} lectures archivées', flush=True)


def run():
    runtime = configure()
    gate = read('r27/barriere.json')
    assert gate['reussite'] and gate['environnement']['versions'] == runtime['versions']
    # Exiger une branche publiée contenant à l’identique la barrière et le code.
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    remote = subprocess.check_output(['git', 'ls-remote', 'origin', 'refs/heads/codex/vision-lab-r27'], text=True)
    subprocess.run(['git', 'merge-base', '--is-ancestor', commit, remote.split()[0]], check=True)
    assert not subprocess.check_output(['git', 'status', '--porcelain', '--', 'r27', 'r26'])
    photos, targets, specs, draws = load_train()
    assert set(draws) == set(map(str, SEEDS))
    output = OUT / 'essai'
    output.mkdir(exist_ok=False)
    write(output / 'lancement.json', dict(commit=commit, environnement=runtime, graines=SEEDS,
                                          controles=CHECKS, debut_epoch=time.time()))
    for seed in SEEDS:
        path = output / str(seed)
        path.mkdir()
        model, record = initialize()
        assert record == read('r27/tenseurs.json')
        expected = fingerprint(frozen(model))
        assert expected == gate['sha256_geles']
        seed_rng(seed)
        optim = optimizer(model)
        control(model, optim, seed, 0, photos, specs, path)
        schedule = draws[str(seed)]
        assert [row['pas'] for row in schedule] == list(range(1, 769))
        with gzip.open(path / 'pas.jsonl.gz', 'xt', encoding='utf8') as log:
            for row in schedule:
                step, key = row['pas'], row['photo']
                assert specs[key]['provenance'] == row['provenance']
                stats = update(model, optim, photos[key], targets[key], expected)
                log.write(json.dumps(dict(**row, **stats), ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
                if step % 8 == 0:
                    log.flush()
                    print(f'{seed} {step}/768, {stats["secondes"]:.2f} s/pas, RSS {stats["rss_max_Kio"]} Kio', flush=True)
                if step in CHECKS:
                    control(model, optim, seed, step, photos, specs, path)
        del model, optim
    write(output / 'fin.json', dict(mises_a_jour=2304, lectures=135, fin_epoch=time.time()))


if __name__ == '__main__':
    run()
