"""Recompter les six états finaux depuis leurs matrices, avant toute adaptation."""
import argparse
from collections import Counter
from pathlib import Path

from r19.donnees import SEEDS, fingerprint, read, save
from r20.rejouer import compact

ROOT = Path('r24')
FOCUS = {'wc71488242', '5115614018'}


def archived(arm, seed, step):
    if arm == 'R21':
        path = Path(f'r21/mesures/temoin-{seed}-{step}.json')
        return path, read(path)['par_photo']
    if arm == 'R22':
        path = Path(f'r22/mesures/dice-{seed}-{step}.json')
        return path, read(path)
    raise ValueError(arm)


def admissible(cells):
    if [c['q'] for c in cells] != list(range(12)):
        raise ValueError('Matrice incomplète')
    mask = {c['q'] for c in cells if c['masque_90_pourcent']}
    axis = {c['q'] for c in cells if c['sondes_axe'] is not None and c['sondes_axe'] >= 11}
    return dict(masque=sorted(mask), axe=sorted(axis), masque_et_axe=sorted(mask & axis),
                seulement_sur_requetes_distinctes=bool(mask and axis and not mask & axis))


def audit(out):
    registry = read('r21/inventaires.json')
    summary = {}
    for seed in SEEDS:
        for arm in ('R21', 'R22'):
            path, views = archived(arm, seed, 768)
            observations, counts = [], Counter()
            if len(views) != 9 or set(views) != set(registry):
                raise ValueError('Périmètre des matrices')
            for photo, row in views.items():
                counts['photos_hors_duo'] += photo not in FOCUS
                for name, cells in row['paires_par_identite'].items():
                    a = row['identites'][name]
                    accepted = admissible(cells)
                    covered = a['entree_q'] is not None
                    complete = a['complete']
                    missing = complete and a['conjoint_q'] is None
                    observations.append(dict(photo=photo, provenance=registry[photo]['provenance'],
                        identite=name, entree_connue=complete, entree_q=a['entree_q'],
                        triplet_q=a['conjoint_q'], **accepted))
                    counts['entrees_connues'] += complete
                    counts['entrees_couvertes'] += covered
                    counts['connues_hors_duo'] += complete and photo not in FOCUS
                    counts['couvertes_hors_duo'] += covered and photo not in FOCUS
                    counts['triplets_manquants'] += missing
                    counts['manquants_masque_et_axe_disponibles'] += bool(missing and accepted['masque'] and accepted['axe'])
                    counts['manquants_masque_et_axe_meme_q'] += bool(missing and accepted['masque_et_axe'])
            key = f'{arm}-{seed}'
            summary[key] = dict(counts)
            compact(out/f'{key}.json', dict(graine=seed, bras=arm, pas=768, matrice=str(path),
                   sha256=fingerprint(path), identites=observations, effectifs=dict(counts)))
    save(out/'synthese.json', summary)
    for key, counts in summary.items():
        if (counts['photos_hors_duo'], counts['connues_hors_duo'], counts['couvertes_hors_duo'],
                counts['entrees_connues'], counts['manquants_masque_et_axe_meme_q']) != (7, 13, 13, 26, 0):
            raise ValueError('Recomptage divergent '+key+' '+str(counts))
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path, nargs='?', default=ROOT/'diagnostic')
    audit(parser.parse_args().out)
