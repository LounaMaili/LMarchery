"""Appliquer B et V aux seules sorties de développement à partir des cartes conservées."""
import argparse
from pathlib import Path

import cv2 as cv
import numpy as np

from r7.model import decode
from r7.prepare import resized
from r15.restaurer import historique
from r12.comparer import save as save_predictions
from r12.geometrie import geometry_index, repere
from r13.segments import reconstruct
from r16.donnees import LAB, INPUTS, RUNS, corpus, fingerprint, matrices, operational, photo_paths, read
from r16.scores import Verificateur


def compare(cache, out, worktrees=None):
    cv.setNumThreads(1)
    model = read(LAB/'verificateur.json')
    if tuple(model['colonnes']) != INPUTS:
        raise ValueError('Colonnes différentes du gel')
    verifier = Verificateur(model)
    photos = [p for p in corpus() if p['partition'] == 'dev_indep']
    paths = {k:historique(path, worktrees) for k,path in photo_paths().items()}
    index = read(cache/'index.json')
    archives = {(a['poids'], a['photo']): a for a in index['couples']}
    if len(archives) != 66 or index['passages_reserves'] != 66:
        raise ValueError('Cache R16 incomplet')
    out.mkdir(exist_ok=True, parents=True)
    for name in RUNS:
        features = matrices(name)
        result = {}
        for p in photos:
            k = p['id']
            a = archives[name, k]
            if fingerprint(cache/a['fichier']) != a['sha256'] or fingerprint(paths[k]) != p['sha256_pixels']:
                raise ValueError(f'Empreinte différente {name}/{k}')
            with np.load(cache/a['fichier'], allow_pickle=False) as saved:
                maps = saved['cartes']
            im = resized(cv.imread(str(paths[k])))
            peaks, _ = decode(maps, max_peaks=192)
            scale, board, _, supported = repere(im, maps[1])
            segments = reconstruct(board, supported)
            rows = operational(features[k])
            for segment, row in zip(segments, rows, strict=True):
                if [format(v, '.12g') for v in segment.ravel()] != [row[v] for v in ('x0','y0','x1','y1')]:
                    raise ValueError(f'Parité coordonnées R15 {name}/{k}/{row["rang_sortie_R13"]}')
            x = verifier.matrice(rows)
            prob, kept = verifier.appliquer(x)
            ns = verifier.chronometrer(x)
            selected = [s for s, ok in zip(segments, kept, strict=True) if ok]
            pts = np.array([xy for xy, _ in peaks]).reshape(-1, 2) * scale
            heat = np.array([h for _, h in peaks])
            base = heat + (1 - heat) * geometry_index(pts, segments, board, supported)
            verified = heat + (1 - heat) * geometry_index(pts, selected, board, supported)
            if not ((heat <= verified) & (verified <= base) & (base <= 1)).all():
                raise ValueError(f'Ordre des bonus {name}/{k}')
            result[k] = dict(provenance=p['provenance'], echelle=scale.tolist(),
                sha256_tube=a['sha256_tube'], sha256_contact=a['sha256_contact'], cpu_mediane_ns=ns,
                segments=[[row['rang_longueur'], s.tolist(), float(pr), bool(ok)]
                          for row,s,pr,ok in zip(rows,segments,prob,kept,strict=True)],
                candidats=[[xy, h, float(b), float(v)] for (xy,h),b,v in zip(peaks,base,verified,strict=True)])
        save_predictions(out/(name+'.json'), result)
        print(name, len(result), 'photos conformes', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cache',type=Path)
    parser.add_argument('--worktrees',type=Path)
    parser.add_argument('out',type=Path)
    compare(**vars(parser.parse_args()))
