"""Consolider les six lectures R21 à 768 pas exclusivement à partir des JSON."""
import argparse
from pathlib import Path

from r19.donnees import SEEDS, read, save
from r20.rejouer import compact
from r22.lecture import counts, describe, pixels


def diagnostic(out):
    caps = read('r20/plafonds.json')
    registry = read('r21/inventaires.json')
    out.mkdir(exist_ok=True, parents=True)
    totals, losses = {}, []
    for seed in SEEDS:
        pair = {}
        for arm in ('temoin','candidat'):
            data = read(f'r21/mesures/{arm}-{seed}-768.json')['par_photo']
            photos = {k:describe(row, registry[k], caps[k]['instances']) for k,row in data.items()}
            total = counts(photos.values())
            totals[f'{arm}-{seed}'] = total
            compact(out/f'{arm}-{seed}.json',photos)
            pair[arm] = data, photos
        for k,left in pair['temoin'][1].items():
            right = pair['candidat'][1][k]
            for name,before in left['identites'].items():
                after = right['identites'][name]
                lost = [f for f in ('presence_q','triplet_q') if before[f] is not None and after[f] is None]
                if not lost:
                    continue
                details = {}
                for arm in pair:
                    row = pair[arm][0][k]
                    idrow = pair[arm][1][k]['identites'][name]
                    per_q = []
                    for c in row['paires_par_identite'][name]:
                        q = c['q']
                        failed = [f for f,qs in idrow['par_champ'].items() if q not in qs]
                        per_q.append(dict(**pixels(c,caps[k]['instances'][name]), champs_manquants=failed,
                            sondes_axe=c['sondes_axe'], erreur_entree_px704=c['erreur_entree_px704'],
                            presence=row['sorties'][q]['presence']))
                    missing = [set(c['champs_manquants']) for c in per_q]
                    minimal = sorted({tuple(sorted(m)) for m in missing if not any(o < m for o in missing)})
                    details[arm] = dict(identite=idrow, requetes=per_q, blocages_minimaux=minimal)
                losses.append(dict(graine=seed, photo=k, provenance=left['provenance'], instance=name,
                                   pertes=lost, **details))
    expected = {'temoin':[4,2,5], 'candidat':[4,4,2]}
    for arm in expected:
        vals = [totals[f'{arm}-{s}']['effectifs'] for s in SEEDS]
        if [v['masque_disponible'] for v in vals] != expected[arm] or any(v['masques_admis'] != 31 for v in vals):
            raise ValueError('Diagnostic des masques historiques divergent')
        if arm == 'temoin' and [(v['contacts_couverts'],v['entrees_admises']) for v in vals] != [(26,26),(25,26),(26,26)]:
            raise ValueError('Couverture historique des entrées divergente')
    save(out/'synthese.json',totals)
    save(out/'blocages.json',losses)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=Path('r22/diagnostic'))
    diagnostic(p.parse_args().out)
