"""Mesures par identité à partir de la matrice publique R20/R21, sans modèle."""
from collections import Counter
import numpy as np

from r21.mesures import matching


def pixels(cell, ceiling):
    truth = ceiling['positifs_natifs']
    tp = cell['intersection'] if cell else 0
    predicted = cell['aire_evaluable_sortie'] if cell else 0
    fp, fn = predicted - tp, truth - tp
    if min(tp, fp, fn) < 0:
        raise ValueError('Comptes de pixels incohérents')
    dice = 2 * tp / (truth + predicted)
    if cell and cell['dice'] != dice:
        raise ValueError('Dice différent de la matrice R20')
    return dict(q=cell['q'] if cell else None, VP=tp, FP=fp, FN=fn, dice=dice,
                plafond=ceiling['dice_max'], fraction=dice / ceiling['dice_max'])


def describe(row, registry, ceilings):
    matrix = row['paires_par_identite']
    valid = {r['instance']:r['champs'] for r in registry['identites'] if r['champs']}
    probabilities = [s['presence'] for s in row['sorties']]
    available = matching(matrix, valid, probabilities, threshold=0)
    mask = matching(matrix, {n:['masque'] for n in valid if 'masque' in valid[n]},
                    probabilities, threshold=0)
    names = {}
    for n, fields in valid.items():
        cells = matrix[n]
        if [c['q'] for c in cells] != list(range(12)):
            raise ValueError('Ordre ou nombre de requêtes')
        assignments = row['identites'][n]
        admissible = {
            'masque':[c['q'] for c in cells if c['masque_90_pourcent']],
            'axe':[c['q'] for c in cells if c['sondes_axe'] is not None and c['sondes_axe'] >= 11],
            'entree':[c['q'] for c in cells if c['erreur_entree_px704'] is not None and c['erreur_entree_px704'] <= 10]}
        q = assignments['masque_q']
        best = max(cells, key=lambda c:c['dice'])
        names[n] = dict(champs=fields, par_champ={f:admissible[f] for f in fields},
                        masque_90_q=mask['appariements'].get(n), axe_q=assignments['axe_q'],
                        entree_q=assignments['entree_q'], triplet_q=assignments['conjoint_q'],
                        triplet_au_seuil_q=assignments['conjoint_au_seuil_q'],
                        conjoint_disponible_q=available['requetes_admissibles'][n],
                        conjoint_q=available['appariements'].get(n),
                        presence_q=row['presence_05']['appariements'].get(n),
                        masque_R19=pixels(cells[q] if q is not None else None, ceilings[n]),
                        meilleur_masque=pixels(best, ceilings[n]))
    return dict(provenance=registry['provenance'], inventaire_exhaustif=registry['admissible'],
                bilan=row['bilan'], identites=names)


def counts(photos):
    result = Counter()
    values = []
    for photo in photos:
        result.update(photo['bilan'])
        for identity in photo['identites'].values():
            fields = identity['champs']
            for field in fields:
                result[field+'_disponible'] += bool(identity['par_champ'][field])
            result['champs_disponibles_separement'] += all(identity['par_champ'].values())
            result['champs_sur_meme_requete'] += bool(identity['conjoint_disponible_q'])
            result['masque_90_un_a_un'] += identity['masque_90_q'] is not None
            result['champs_un_a_un'] += identity['conjoint_q'] is not None
            partial = 'entree' not in fields
            result['entree_inconnue'] += partial
            result['inconnue_conjointe'] += partial and identity['conjoint_q'] is not None
            result['inconnue_p05'] += partial and identity['presence_q'] is not None
            result['inconnue_masque_90'] += partial and identity['masque_90_q'] is not None
            result['inconnue_axe'] += partial and identity['axe_q'] is not None
            p = identity['masque_R19']
            for key in ('VP','FP','FN'):
                result[key] += p[key]
            values.append(p)
    quantiles = {k:np.quantile([p[k] for p in values],[0,.25,.5,.75,1]).tolist()
                 for k in ('dice','fraction','VP','FP','FN') if values}
    return dict(effectifs=dict(result), quantiles_masque_R19=quantiles)


def groups(photos):
    categories = {'toutes':list(photos.values())}
    for k,r in photos.items():
        categories.setdefault('source/'+r['provenance'],[]).append(r)
        categories.setdefault('exhaustives' if r['inventaire_exhaustif'] else 'partielles',[]).append(r)
        categories['photo/'+k] = [r]
    return {g:counts(rs) for g,rs in categories.items()}


def change(a, b):
    return dict(gagnees=sorted(b-a), perdues=sorted(a-b), conservees=sorted(a&b))


def compare(witness, candidate):
    changes = {}
    fields = ['masque_90_q','axe_q','entree_q','triplet_q','triplet_au_seuil_q','conjoint_q','presence_q']
    for f in fields:
        def selected(photos):
            return {n for r in photos.values() for n,i in r['identites'].items() if i[f] is not None}
        changes[f] = change(selected(witness),selected(candidate))
    sources = {n:r['provenance'] for r in witness.values() for n in r['identites']}
    gained = changes['triplet_q']['gagnees']
    outside = [n for n in gained if sources[n] != 'Crigges']
    provenances = sorted({sources[n] for n in outside})
    passed = (len(outside) >= 2 and len(provenances) >= 2 and
              not changes['triplet_q']['perdues'] and not changes['entree_q']['perdues'])
    return dict(champs=changes, gains_triplets_hors_Crigges=outside,
                provenances_des_gains=provenances, conforme=passed)
