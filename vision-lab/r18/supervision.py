"""Rasteriser les lectures R18 et séparer l'admission du fût, de l'axe et de l'entrée."""
import argparse
from collections import defaultdict
from hashlib import sha256
import json
from math import ceil, floor
from pathlib import Path

import cv2 as cv
import numpy as np

from r14.mesurer import read, save

ROOT = Path('r18')
UNKNOWN = 255
FIELDS = ('masque', 'axe', 'entree')


def inside(point, polygon):
    return cv.pointPolygonTest(np.asarray(polygon, np.float32), tuple(map(float, point)), False) >= 0


def paint(array, polygon, value):
    """Remplir les centres entiers à l'intérieur du polygone ou sur ses arêtes."""
    p = np.asarray(polygon, dtype=float)
    q = np.roll(p, -1, axis=0)
    for y in range(max(0, ceil(p[:, 1].min())), min(array.shape[0]-1, floor(p[:, 1].max()))+1):
        crossing = (p[:, 1] > y) != (q[:, 1] > y)
        start, end = p[crossing], q[crossing]
        xs = np.sort(start[:, 0] + (y-start[:, 1])*(end[:, 0]-start[:, 0])/(end[:, 1]-start[:, 1]))
        for left, right in xs.reshape(-1, 2):
            array[y, max(0, ceil(left-1e-8)):min(array.shape[1], floor(right+1e-8)+1)] = value
        # La règle de parité est demi-ouverte en y. Rendre les côtés horizontaux et sommets fermés.
        for u, v in zip(p[p[:, 1] == y], q[p[:, 1] == y], strict=True):
            left, right = sorted((u[0], v[0] if v[1] == y else u[0]))
            array[y, max(0, ceil(left-1e-8)):min(array.shape[1], floor(right+1e-8)+1)] = value


def cut_axis(line, forbidden):
    """Conserver les tronçons contigus dans les cellules autorisées (pixels centrés sur les entiers)."""
    out, current = [], []
    for a, b in zip(np.asarray(line)[:-1], np.asarray(line)[1:], strict=True):
        delta = b-a
        # Découpage exact à chaque franchissement d'un bord de cellule, y compris en diagonale.
        cuts = [0., 1.]
        for origin, diff in zip(a, delta, strict=True):
            if diff:
                lo, hi = sorted((origin, origin+diff))
                limits = np.arange(floor(lo+.5)+.5, hi, 1.)
                cuts.extend((limits-origin)/diff)
        ts = np.unique(np.clip(cuts, 0, 1))
        mid = a + ((ts[1:]+ts[:-1])/2)[:, None]*delta
        index = np.floor(mid+.5).astype(int)
        keep = ~forbidden[index[:, 1], index[:, 0]]
        switches = np.flatnonzero(np.r_[True, keep[1:] != keep[:-1], True])
        for i, j in zip(switches[:-1], switches[1:], strict=True):
            if keep[i]:
                first, last = (a+ts[i]*delta).tolist(), (a+ts[j]*delta).tolist()
                if not current:
                    current.append(first)
                current.append(last)
            elif current:
                out.append(current)
                current = []
    if current:
        out.append(current)
    return out


def region_masks(photo, shape):
    data = {}
    forbidden = {t: np.zeros(shape, bool) for t in FIELDS}
    for reg in photo['regions_ignorees']:
        mask = np.zeros(shape, bool)
        paint(mask, reg['polygone'], True)
        data[reg['id']] = mask
        for task in reg['taches']:
            forbidden[task] |= mask
    return data, forbidden


def polygons_mask(polygons, shape):
    mask = np.zeros(shape, bool)
    for polygon in polygons:
        paint(mask, polygon, True)
    return mask


def trace_length(lines):
    return sum(float(np.linalg.norm(np.diff(line, axis=0), axis=1).sum()) for line in lines)


def admit(photo):
    shape = tuple(reversed(photo['dimensions']))
    region, forbidden = region_masks(photo, shape)
    pieces = {t['instance']: polygons_mask(t['polygones_observes'], shape) for t in photo['instances']}
    counts = np.zeros(shape, np.uint8)
    for mask in pieces.values():
        counts += mask
    conflict = counts > 1
    bg = polygons_mask(photo['fond_explicite'], shape)
    if photo['region_evaluation_temoin']:
        if photo['instances']:
            raise ValueError('Une vue occupée ne peut pas être un témoin vide')
        paint(bg, photo['region_evaluation_temoin'], True)
    if np.any(bg & (counts > 0)):
        raise ValueError(f"Fond explicite sur un fût lu, photo {photo['photo']}")
    values = np.full(shape, UNKNOWN, np.uint8)
    values[bg & ~forbidden['masque']] = 0
    instances = []
    for ref in photo['instances']:
        ident = ref['instance']
        raw = pieces[ident]
        valid = raw & ~conflict & ~forbidden['masque']
        values[valid] = ref['indice']
        old = ref['historique'] or {}
        contours = ref['polygones_observes']
        axis_excluded = forbidden['axe'] | conflict
        if ref['type_axe'] == 'mediane_des_bords_lus':
            # Une médiane issue des deux bords n'établit pas de supervision dans la cellule extérieure du cap.
            axis_excluded |= ~raw
        axes = [piece for axis in ref['axes_observes'] for piece in cut_axis(axis, axis_excluded)]
        contact = ref['entree']
        xy = contact['xy_lu']
        excluded = [r['id'] for r in photo['regions_ignorees'] if xy is not None and
                    'entree' in r['taches'] and inside(xy, r['polygone'])]
        contact_valid = xy is not None and not excluded
        if contact_valid and bg[floor(xy[1]+.5), floor(xy[0]+.5)]:
            raise ValueError(f'Fond sur une entrée {ident}')
        nraw, n = int(raw.sum()), int(valid.sum())
        axes_length = trace_length(axes)
        observed_length = trace_length(ref['axes_observes'])
        instances.append(dict(instance=ident, instance_R17=ref['instance_R17'], indice=ref['indice'],
            prioritaire=ref['prioritaire'], identite_fragments=ref['identite_fragments'],
            masque_lu=bool(contours), masque_valide=n>0, composantes_tracees=len(contours),
            composantes_raster_8=cv.connectedComponents(valid.astype(np.uint8), connectivity=8)[0]-1 if n else 0,
            pixels_masque_lus=nraw, pixels_masque_valides=n, pixels_conflit=int((raw & conflict).sum()),
            axe_lu=bool(ref['axes_observes']), axe_valide=bool(axes), axes_supervises=axes,
            longueur_axe_lue_px=round(observed_length, 3), longueur_axe_admise_px=round(axes_length, 3),
            etat_entree=contact['etat'], entree_lue=xy, entree_valide=contact_valid,
            entree_supervisee=xy if contact_valid else None, exclusions_entree=excluded,
            regions_rencontrees=[key for key, mask in region.items() if np.any(raw & mask) or key in excluded],
            axe_R17=bool(old.get('axe')), masque_R17=bool(old.get('masque_reference')),
            entree_R17=old.get('entree') is not None))
    pixels = dict(fut=int(((values > 0) & (values < UNKNOWN)).sum()), fond_verifie=int((values == 0).sum()),
                  ignores=int((values == UNKNOWN).sum()), conflits=int(conflict.sum()))
    return values, dict(photo=photo['photo'], partition=photo['partition'], provenance=photo['provenance'],
                        role=photo['role'], supports=photo['supports'], pixels=pixels,
                        ids_regions_ignorees=list(region), instances=instances)


def statistics(photos):
    refs = [t for p in photos for t in p['instances']]
    if len({t['instance'] for t in refs}) != len(refs):
        raise ValueError('Une identité est comptée deux fois dans le périmètre R18')
    keys = ('prioritaire','masque_R17','axe_R17','entree_R17','masque_lu','masque_valide','axe_lu','axe_valide','entree_valide')
    result = {k:sum(t[k] for t in refs) for k in keys}
    result.update(acquisitions=len(photos), provenances=len({p['provenance'] for p in photos}),
        temoins_vides=sum(p['role']=='temoin_vide' for p in photos),
        instances_R17=sum(t['instance_R17'] is not None for t in refs), instances_R18=len(refs),
        nouvelles_identites=sum(t['instance_R17'] is None for t in refs),
        triplets_R17=sum(t['masque_R17'] and t['axe_R17'] and t['entree_R17'] for t in refs),
        triplets_valides=sum(t['masque_valide'] and t['axe_valide'] and t['entree_valide'] for t in refs),
        masque_sans_entree=sum(t['masque_valide'] and not t['entree_valide'] for t in refs),
        entree_sans_masque=sum(t['entree_valide'] and not t['masque_valide'] for t in refs),
        contours_entierement_ignores=sum(t['masque_lu'] and not t['masque_valide'] for t in refs),
        composantes_tracees=sum(t['composantes_tracees'] for t in refs),
        pixels={k:sum(p['pixels'][k] for p in photos) for k in ('fut','fond_verifie','ignores','conflits')})
    return result


def compact(path, data):
    """Conserver chaque couple (x,y) numérique sur une ligne dans le registre de géométries."""
    import re
    s = json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False)
    path.write_text(re.sub(r'\[\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\s*\]', r'[\1, \2]', s)+'\n')


def tables(parts, by_source, by_photo):
    head = ('| Groupe | Photos | Instances R17 → R18 | Masque / axe / entrée R17 | Masque / axe / entrée R18 admis | '
            'Triplets R17 → R18 | Masque seul vis-à-vis de l’entrée | Entrée sans masque |')
    lines = ['# Avant et après la lecture R18', '', 'Les trois colonnes de tâches comptent des instances physiques distinctes. '
        'R17 mesure la présence en archive, R18 la supervision admise dans la vue, après exclusions. Les 20 photos ont des '
        'identités distinctes entre vues. Un masque documentaire entièrement ignoré compte zéro pour le masque admis. '
        'Le champ « masque seul vis-à-vis de l’entrée » peut aussi disposer d’un axe. [Mesures numériques](effectifs.json)', '']
    for title, entries in [('Par partition',parts),('Par provenance et partition',by_source),('Par acquisition',by_photo)]:
        lines.extend(['## '+title,'',head,'|---|---:|---:|---:|---:|---:|---:|---:|'])
        for key, v in entries.items():
            lines.append(f"| {key} | {v['acquisitions']} | {v['instances_R17']} → {v['instances_R18']} | "
                f"{v['masque_R17']} / {v['axe_R17']} / {v['entree_R17']} | {v['masque_valide']} / {v['axe_valide']} / {v['entree_valide']} | "
                f"{v['triplets_R17']} → {v['triplets_valides']} | {v['masque_sans_entree']} | {v['entree_sans_masque']} |")
        lines.append('')
    return '\n'.join(lines).rstrip('\n')+'\n'


def generate(out):
    folder = out/'masques'
    folder.mkdir(parents=True, exist_ok=True)
    records = []
    encodings = []
    for scope in read(ROOT/'perimetre.json')['acquisitions']:
        key = scope['photo']
        p = read(ROOT/'annotations'/(key+'.json'))
        raster, record = admit(p)
        name = folder/(key+'.png')
        if not cv.imwrite(str(name), raster, [cv.IMWRITE_PNG_COMPRESSION, 9]):
            raise OSError(name)
        record['carte'] = dict(fichier='masques/'+name.name, sha256_plan_uint8=sha256(raster.tobytes()).hexdigest())
        encodings.append(dict(photo=key, **record['carte'], octets=name.stat().st_size,
                              sha256_png=sha256(name.read_bytes()).hexdigest()))
        records.append(record)
    save(out/'empreintes-masques.json',encodings)
    compact(out/'validites.json', dict(version='R18-1', fond=0, ignore=255, vues=records))
    def group(key):
        subsets = defaultdict(list)
        for record in records:
            subsets[key(record)].append(record)
        return {k:statistics(v) for k,v in sorted(subsets.items())}
    parts = group(lambda p:p['partition'])
    prov = group(lambda p:p['partition']+' · '+p['provenance'])
    photos = group(lambda p:p['photo'])
    old = read('r17/effectifs-instances.json')
    coverage = {}
    for part, info in old.items():
        coverage[part] = {}
        for source, base in info['par_provenance'].items():
            selected = prov.get(part+' · '+source)
            coverage[part][source] = dict(acquisitions_R17=base['acquisitions'], instances_R17=base['instances_annotees'],
                masque_source_R17=base.get('avec_masque',0), axe_R17=base.get('avec_axe',0), entree_R17=base.get('entree_visible',0),
                acquisitions_hors_revue=base['acquisitions']-(selected or {}).get('acquisitions',0),
                instances_R17_hors_revue=base['instances_annotees']-(selected or {}).get('instances_R17',0), revue_R18=selected)
    save(out/'effectifs.json',dict(version='R18-1', par_partition=parts, par_provenance=prov, par_acquisition=photos,
        prioritaires=dict(n=sum(t['prioritaire'] for p in records for t in p['instances']),
          par_tache={k:sum(t['prioritaire'] and t[k] for p in records for t in p['instances']) for k in ('masque_valide','axe_valide','entree_valide')}),
        couverture_corpus_entier=coverage))
    (out/'tableaux.md').write_text(tables(parts,prov,photos))
    return records


def compare_masks(actual, expected):
    """Vérifier le type, les dimensions et tous les pixels, indépendamment du compresseur PNG."""
    names = sorted(p.name for p in actual.glob('*.png'))
    if not names or names != sorted(p.name for p in expected.glob('*.png')):
        raise ValueError('Cartes absentes ou supplémentaires')
    for name in names:
        a = cv.imread(str(actual/name), cv.IMREAD_UNCHANGED)
        b = cv.imread(str(expected/name), cv.IMREAD_UNCHANGED)
        if a is None or b is None or a.dtype != np.uint8 or b.dtype != np.uint8 or a.ndim != 2 or not np.array_equal(a, b):
            raise ValueError('Étiquettes différentes dans '+name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('--comparer-cartes',type=Path,help='Cartes PNG de référence publiées')
    args=parser.parse_args()
    generate(args.out)
    if args.comparer_cartes is not None:
        compare_masks(args.out/'masques',args.comparer_cartes)
