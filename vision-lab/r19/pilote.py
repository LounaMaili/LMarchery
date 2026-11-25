"""Trois ajustements préenregistrés, puis onze lectures de photos par poids final."""
import argparse
from collections import Counter
from hashlib import sha256
import json
import os
from pathlib import Path
import platform
import resource
import sys
import time

import cv2 as cv
import numpy as np
import torch

from r19.decoder import decode
from r19.donnees import INITIAL, ROOT, SEEDS, WEIGHT, archive, fingerprint, load_image, read, save, verify
from r19.modele import Instances, parameter_counts, photograph
from r19.supervision import losses, train_targets


def rss():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss


def setup(seed):
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    cv.setNumThreads(1)
    torch.manual_seed(seed)


@torch.inference_mode()
def predict_photo(model, bgr):
    rgb = photograph(bgr)
    fields, present = model(rgb)
    return decode(fields,present,rgb.shape[-1:-3:-1],bgr.shape[1::-1])


def execute(seed, worktrees, output):
    if seed not in SEEDS:
        raise ValueError(seed)
    photos = verify()
    plan = read(ROOT/'tirages.json')[str(seed)]
    checkpoint = worktrees/WEIGHT
    if fingerprint(checkpoint)!=INITIAL:
        raise ValueError('Mauvais checkpoint initial')
    folder = output/str(seed)
    folder.mkdir(parents=True,exist_ok=False)
    setup(seed)
    start, cpu0 = time.monotonic(), time.process_time()
    model = Instances(checkpoint)
    params = parameter_counts(model)
    if params != read(ROOT/'configuration.json')['parametres']:
        raise ValueError('Nombre de paramètres différent du gel')
    model.train()
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=.001,
                                  betas=(.9,.999),eps=1.e-8,weight_decay=.01,foreach=False)
    targets = train_targets(photos)
    images = {p['photo']:photograph(load_image(p,worktrees)) for p in photos if p['partition']=='train'}
    logs = []
    training_start, utc_start = time.monotonic(), time.time()
    with (folder/'progression.jsonl').open('x',buffering=1) as log:
        for draw in plan:
            key = draw['photo']
            before = time.monotonic()
            optimizer.zero_grad(set_to_none=True)
            fields, presence = model(images[key])
            total, parts, audit = losses(fields,presence,targets[key])
            total.backward()
            norm = torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad],
                                                  5.,error_if_nonfinite=True)
            if not torch.isfinite(total):
                raise ValueError(f'Perte non finie au pas {draw["pas"]}')
            optimizer.step()
            row = dict(**draw, **{k:v.item() for k,v in parts.items()}, total=total.item(), norme_gradient=float(norm),
                       **audit, duree_s=time.monotonic()-before)
            logs.append(row)
            log.write(json.dumps(row,allow_nan=False,ensure_ascii=False)+'\n')
    duration = time.monotonic()-training_start
    cp = folder/'final.pt'
    torch.save(model.state_dict(),cp)
    # Une seule inférence finale par photo. Aucun champ d'annotation dans ce point d'entrée.
    model.eval()
    preds = {}
    masks = folder/'masques'
    masks.mkdir()
    for p in photos:
        if p['partition'] != 'dev_explore_R16':
            continue
        frame = load_image(p,worktrees)
        before = time.monotonic()
        record, packed = predict_photo(model,frame)
        record['duree_inference_decodage_s'] = time.monotonic()-before
        path = masks/(p['photo']+'.png')
        if not cv.imwrite(str(path),packed,[cv.IMWRITE_PNG_COMPRESSION,9]):
            raise OSError(path)
        record['sha256_plan_uint16_le'] = sha256(packed.astype('<u2').tobytes()).hexdigest()
        preds[p['photo']] = record
    archive(folder/'predictions.json',preds)
    save(folder/'execution.json',dict(graine=seed,initial_sha256=INITIAL,final_sha256=fingerprint(cp),
        poids_octets=cp.stat().st_size, parametres=params, pas=len(logs),debut_unix=utc_start, entrainement_s=duration,
        total_s=time.monotonic()-start,cpu_processus_s=time.process_time()-cpu0,rss_max_Kio=rss(), anomalies_non_finies=0,
        expositions_photos=dict(Counter(r['photo'] for r in logs)),expositions_provenances=dict(Counter(r['provenance'] for r in logs)),
        photos_inferees=len(preds),threads_torch=torch.get_num_threads(),threads_interop=torch.get_num_interop_threads(),
        threads_cv=cv.getNumThreads(), processus=os.getpid(),python=sys.version.split()[0],torch=str(torch.__version__),
        numpy=np.__version__,opencv=cv.__version__,plateforme=platform.platform()))
    print(seed,len(logs),len(preds),round(duration,2),'s',fingerprint(cp),flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('seed',type=int,choices=SEEDS)
    parser.add_argument('worktrees',type=Path)
    parser.add_argument('output',type=Path)
    execute(**vars(parser.parse_args()))
