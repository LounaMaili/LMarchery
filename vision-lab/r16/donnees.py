"""Relier les rangs publics R15 et les métriques géométriques R13/R14."""
from collections import defaultdict
import csv
from pathlib import Path

import numpy as np

from r13.diagnostic import geometry
from r14.mesurer import metrics, read, save
from r15.corpus import corpus, bank, fingerprint

LAB = Path('r16')
FIRST = 'ordinary-9301'
INDEX = Path('r15/caracteristiques')
INPUTS = tuple(read(INDEX/'index.json')['colonnes_entree'])
RUNS = tuple(read(INDEX/'index.json')['poids'])


def matrices(name):
    spec = read(INDEX/'index.json')['poids'][name]
    path = INDEX/spec['fichier']
    if fingerprint(path) != spec['sha256']:
        raise ValueError(path)
    rows = defaultdict(list)
    with path.open() as stream:
        for row in csv.DictReader(stream):
            row['rang_longueur'] = int(row['rang_longueur'])
            out = row['rang_sortie_R13']
            row['rang_sortie_R13'] = int(out) if out else None
            rows[row['photo']].append(row)
    return rows


def operational(rows):
    output = sorted((r for r in rows if r['rang_sortie_R13'] is not None),key=lambda r:r['rang_sortie_R13'])
    if [r['rang_sortie_R13'] for r in output] != list(range(len(output))) or len(output)>32:
        raise ValueError('Rangs R13 non contigus')
    return output


def axis(row):
    return np.array([[row['x0'],row['y0']],[row['x1'],row['y1']]],dtype=np.float64)


def lab_row(photo, axes_only=False):
    tubes = [t for t in photo['tubes'] if not axes_only or t['axe']]
    return dict(id=photo['id'],group=photo['provenance'],boards=photo['supports'],flags=photo['conditions'],
                size=photo['taille'],exhaustive=photo['complet'],ignore=photo['regions_ignorees'],
                marks=[dict(axis=t['axe'],entry=t['entree'],instance=t['instance']) for t in tubes])


def scaling(photo):
    size = np.asarray(photo['taille'])
    return np.rint(size*min(1.,704/max(size)))/size


def reference(photo, segments):
    row = lab_row(photo,axes_only=True)
    scale = scaling(photo)
    # Les strict_ids de R13 dépendent exclusivement des deux géométries.
    # Les champs de soutien du masque calculés en interne ne sont pas consommés.
    empty = np.zeros((1,1),np.uint8)
    result = geometry(row,segments,scale,empty,empty)
    raw = dict(axes_B=segments,echelle=scale,B=result)
    return row,raw


def strict_metrics(photo, segments, kept):
    row,raw = reference(photo,segments)
    return metrics(row,raw,kept)


def crosses_ignored(segment, photo):
    """Intersection fermée segment–rectangle, dans le repère des annotations."""
    a,b = segment/scaling(photo)
    for lo,hi in photo['regions_ignorees']:
        begin,end = 0.,1.
        for x,d,left,right in zip(a,b-a,lo,hi,strict=True):
            if abs(d)<1.e-12:
                if x<left or x>right:
                    begin,end = 1.,0.
                    break
            else:
                u,v = sorted(((left-x)/d,(right-x)/d))
                begin,end = max(begin,u),min(end,v)
        if begin<=end:
            return True
    return False


def documentary():
    return {(s['photo'],s['rang']):s for s in bank()}


def photo_paths():
    paths = {r['sha256']:Path(r['emplacement']) for r in read('r14/inventaire-pixels.json')}
    for row in read('r15/inventaire-local.json')['acquisitions']:
        paths[row['sha256']] = Path(row['emplacement'])
    return {p['id']:paths[p['sha256_pixels']] for p in corpus()}
