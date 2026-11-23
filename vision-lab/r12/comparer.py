"""Recalculer les cartes figées une seule fois, puis enregistrer les trois scores."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from time import perf_counter

import cv2 as cv
import numpy as np
import torch

from lab_data import fingerprint
from r7.model import ContactNet, decode, full_maps
from r7.prepare import resized
from r10.diagnostic import read
from r10.tableaux import RUNS
from r11.comparer import inputs
from r12.geometrie import repere, geometry_index, axis_diagnostics

LAB = Path(__file__).resolve().parents[1]


def save(path, content):
    # Une ligne par candidat pour que les scores soient lisibles dans GitHub.
    with path.open('w') as f:
        f.write('{\n')
        for index,(ident,photo) in enumerate(content.items()):
            if index:
                f.write(',\n')
            f.write('  '+json.dumps(ident)+': {\n')
            for key,value in photo.items():
                if key != 'candidats':
                    f.write('    '+json.dumps(key)+': '+json.dumps(value,allow_nan=False)+',\n')
            f.write('    "candidats": [\n')
            f.write(',\n'.join('      '+json.dumps(p,allow_nan=False) for p in photo['candidats']))
            f.write('\n    ]\n  }')
        f.write('\n}\n')


def compare(r8, r9, media, out):
    historical, images, weights, _ = inputs(r8, r9)
    new = read(LAB/'r12/nouvelles.json')
    if new['apprentissage_admissible']:
        raise ValueError('La porte C est ouverte, préparer les deux variantes et les cartes train.')
    rows = list(historical)
    for row in new['photos']:
        if row['split'] != 'nouveau':
            continue
        path = media/(row['id']+'.jpg')
        if fingerprint(path) != row['source']['sha256']:
            raise ValueError('Dérivé de publication modifié')
        im = resized(cv.imread(str(path)))
        if list(im.shape[1::-1]) != row['size']:
            raise ValueError('Dimensions différentes')
        images[row['id']] = im
        rows.append(row)
    audit = read(LAB/'r11/parite.json')['essais']
    out.mkdir(parents=True, exist_ok=False)
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    for name in RUNS:
        torch.manual_seed(int(name.split('-')[1]))
        model = ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(weights[name], map_location='cpu', weights_only=True))
        model.eval()
        existing = read(LAB/'r11/pics'/(name+'.json'))
        result = {}
        for row in rows:
            ident = row['id']
            im = images[ident]
            start = perf_counter()
            maps = full_maps(model, im)
            peaks, tubes = decode(maps, max_peaks=192)
            common = perf_counter()-start
            digest = sha256(maps.tobytes(order='C')).hexdigest()
            if ident in existing:
                archived = existing[ident]
                same_points = len(peaks) == len(archived) and all(
                    xy == old_xy and score.hex() == old_score.hex()
                    for (xy, score), (old_xy, old_score) in zip(peaks,archived,strict=True))
                same_map = digest == audit[name]['photos'][ident]['sha256_cartes']
                if not (same_map and same_points):
                    raise ValueError(f'Parité R11 {name}/{ident} : carte={same_map}, pics={same_points}')
            start = perf_counter()
            scale, board, mask, supported = repere(im, maps[1])
            detected = [tube.ends*scale for tube in tubes]
            coords = np.asarray([p for p,_ in peaks]).reshape(-1,2)*scale
            base = np.array([s for _,s in peaks])
            b = geometry_index(coords, detected, board, supported)
            bscore = base+(1-base)*b
            geometry_seconds = perf_counter()-start
            start = perf_counter()
            axes = [np.array(mark['axis'])*scale for mark in row['marks']]
            oracle = geometry_index(coords, axes, board, supported, annotated=True)
            oscore = base+(1-base)*oracle
            oracle_seconds = perf_counter()-start
            diag = axis_diagnostics(row, detected, scale, mask, supported)
            result[ident] = dict(carte_sha256=digest, historique=ident in existing, diagnostic=diag,
                                 temps_s=dict(generateur=common,B=geometry_seconds,O=oracle_seconds),
                                 axes_detectes_repere704=[a.tolist() for a in detected],
                                 candidats=[[p,s,float(bb),float(oo),float(i),float(j)] for (p,s),bb,oo,i,j in
                                            zip(peaks,bscore,oscore,b,oracle,strict=True)])
        save(out/(name+'.json'),result)
        print(name,len(result),'photos',flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for s in ('r8','r9','media','out'):
        p.add_argument(s,type=Path)
    compare(**vars(p.parse_args()))
