"""Rejouer les appariements et la décision depuis les prédictions publiques."""
import argparse
from collections import Counter
import csv
from math import dist
from pathlib import Path
from statistics import median

import numpy as np

from r14.mesurer import metrics as geometry_metrics
from r16.donnees import (LAB, RUNS, corpus, crosses_ignored, documentary, lab_row,
                         matrices, operational, read, reference, save)

ARMS = ('B', 'V')
CRITERIA = (('parasites_sur_vide', 'réduction', 60), ('faux_occupes', 'réduction', 60),
            ('strict_1a1', 'conservation', 90), ('bout_strict_1a1', 'conservation', 90))
STATUS = ('faux_occupes', 'tubes_lus_occupes', 'incertains_occupes', 'non_lus_occupes')


def criteria(b, v):
    result = {}
    for key, mode, minimum in CRITERIA:
        before, after = b[key], v[key]
        if not 0 <= after <= before:
            raise ValueError(key)
        num = before-after if mode == 'réduction' else after
        result[key] = dict(B=before, V=after, numerateur=num, denominateur=before, minimum_pct=minimum,
                           atteint=(100*num >= minimum*before if before else None))
    return result


def decision(reports):
    return all(c['atteint'] is True for c in reports.values())


def segment_photo(photo, lines, data, bank):
    records = operational(lines)
    segments = [np.array(s[1]) for s in data['segments']]
    row, raw = reference(photo, segments)
    empty = 'empty' in row['flags']
    if empty and not (row['exhaustive'] and not row['marks'] and not row['ignore']):
        raise ValueError('Un témoin vide doit être exhaustif')
    scale = raw['echelle']
    strict = [set(ref['strict_ids']) for ref in raw['B']['reference_axes']]
    refs = []
    for ids, mark in zip(strict, row['marks'], strict=True):
        xy = mark['entry']
        near = None if xy is None else sorted(i for i in ids if min(dist(q, np.array(xy)*scale)
                                                                     for q in segments[i]) <= 10)
        refs.append(dict(instance=mark['instance'], stricts=sorted(ids), propres_bouts=near))
    audit = []
    for i, (line, (rank, _, prob, keep)) in enumerate(zip(records, data['segments'], strict=True)):
        if line['rang_longueur'] != rank:
            raise ValueError('Rang différent de la matrice')
        entry = bank.get((photo['id'], rank))
        if entry and not np.array_equal(segments[i]/scale, entry['axe_px_travail']):
            raise ValueError('Géométrie de la lecture documentaire différente')
        status = entry['statut'] if entry else 'non_lu'
        ignored = crosses_ignored(segments[i], photo)
        if status == 'negatif_verifie' and ignored:
            raise ValueError('Lecture négative dans une zone ignorée')
        audit.append(dict(sortie=i, rang_longueur=rank, statut_lecture=status,
                          lecture=entry['lecture'] if entry else None, traverse_ignore=ignored,
                          verifier=prob, garde=keep))
    variants = {}
    for arm in ARMS:
        ids = {i for i, s in enumerate(audit) if arm == 'B' or s['garde']}
        counts = geometry_metrics(row, raw, ids)
        for key, status in zip(STATUS, ('negatif_verifie','tube','incertain','non_lu'), strict=True):
            counts[key] = sum(audit[i]['statut_lecture'] == status for i in ids) if not empty else 0
        if sum(counts[key] for key in STATUS) + counts['parasites_sur_vide'] != len(ids):
            raise ValueError('Statut de sortie non ventilé')
        counts['non_resolus_occupes'] = counts['incertains_occupes'] + counts['non_lus_occupes']
        counts['dont_non_resolus_avec_voisin_strict'] = sum(
            not empty and audit[i]['statut_lecture'] in ('incertain','non_lu') and any(i in s for s in strict)
            for i in ids)
        counts['dont_traversant_ignore'] = sum(audit[i]['traverse_ignore'] for i in ids)
        variants[arm] = counts
    off = []
    for line in lines:
        if line['rang_sortie_R13'] is None:
            rank = line['rang_longueur']
            entry = bank[photo['id'], rank]
            off.append(dict(rang_longueur=rank, statut=entry['statut'], lecture=entry['lecture']))
    return dict(provenance=photo['provenance'], supports=photo['supports'], conditions=photo['conditions'],
                inventaire_complet=photo['complet'], regions_ignorees=len(row['ignore']),
                candidats_contacts=len(data['candidats']), cpu_mediane_ns=data['cpu_mediane_ns'],
                bras=variants, references=refs, sorties=audit, documentaires_hors_sortie=off)


def segment_totals(photos):
    groups = {}
    sources = sorted({p['provenance'] for p in photos})
    groups['ensemble'] = photos
    for condition in ('grouped','worn','empty','occlusion'):
        groups[condition] = [p for p in photos if condition in p['conditions']]
    groups['vides_uses'] = [p for p in photos if {'empty','worn'} <= set(p['conditions'])]
    groups['occupees'] = [p for p in photos if 'empty' not in p['conditions']]
    groups.update({'source/'+s:[p for p in photos if p['provenance']==s] for s in sources})
    result = {}
    for name, pop in groups.items():
        stats = {arm:dict(sum((Counter(p['bras'][arm]) for p in pop), Counter())) for arm in ARMS}
        # Counter addition discards zeroes; every denominator must remain visible.
        for arm, sums in stats.items():
            for key in photos[0]['bras'][arm]:
                sums.setdefault(key, 0)
        off = Counter(r['statut'] for p in pop for r in p['documentaires_hors_sortie'])
        strict_scores = [p['sorties'][i]['verifier'] for p in pop
                         for i in sorted({i for ref in p['references'] for i in ref['stricts']})]
        result[name] = dict(segments_compatibles_stricts=len(strict_scores),
                            proba_max_segment_strict=max(strict_scores, default=None), photos=len(pop), candidats=sum(p['candidats_contacts'] for p in pop),
                            cpu_mediane_ns=median(p['cpu_mediane_ns'] for p in pop) if pop else None,
                            bras=stats, criteres=criteria(stats['B'],stats['V']),
                            documentaires_hors_sortie=dict(off))
    return result


def contacts(photos, predictions, inherited, curves, name):
    from r9.experiment import threshold
    from r12.mesurer import metrics
    rows = [lab_row(p) for p in photos]
    columns = {arm:{k:[(xy, vals[j]) for xy,*vals in d['candidats']] for k,d in predictions.items()}
               for j,arm in enumerate(ARMS, start=1)}
    local = {arm:threshold([(r, peaks[r['id']]) for r in rows]) for arm,peaks in columns.items()}
    grid = sorted({*(i/40 for i in range(41)), 2., inherited, *local.values()})
    points = {}
    for arm, peaks in columns.items():
        chosen = {'historique':inherited, 'local_R9':local[arm], 'couverture':0.}
        points[arm] = {}
        for t in grid:
            result = metrics(rows, peaks, t)
            strata = {'ensemble':result['all'], 'grouped':result['grouped'], 'worn':result['worn'],
                      'empty':result['empty'], **{'source/'+s:v for s,v in result['per_group'].items()}}
            for group, nums in strata.items():
                curves.writerow(dict(poids=name, bras=arm, seuil=t, strate=group, **nums))
            for mode, value in chosen.items():
                if t == value:
                    points[arm][mode] = dict(seuil=t, budget_tenu=result['all']['unmatched'] <= len(rows)/4, **result)
        if not points[arm]['local_R9']['budget_tenu']:
            raise ValueError('Budget R9 dépassé')
    for b,v in zip(points['B']['historique']['photos'], points['V']['historique']['photos'], strict=True):
        if b['id'] != v['id'] or v['matched'] > b['matched'] or v['proposals'] > b['proposals']:
            raise ValueError('Monotonie contact à seuil hérité')
    if points['B']['couverture'] != points['V']['couverture']:
        raise ValueError('Couverture différente à seuil nul')
    return points


def fit_observations():
    from r16.scores import Verificateur
    model = Verificateur(read(LAB/'verificateur.json'))
    rows = read(LAB/'exemples-fit.json')['exemples']
    _, kept = model.appliquer(np.array([r['x'] for r in rows], dtype=float))
    return {label:dict(exemples=sum(r['y']==y for r in rows),
                       gardes=sum(r['y']==y and bool(k) for r,k in zip(rows,kept,strict=True)))
            for y,label in ((0,'negatifs'),(1,'positifs'))}


def evaluate(predictions, out):
    photos = [p for p in corpus() if p['partition']=='dev_indep']
    bank = documentary()
    cutoffs = read(LAB/'verification.json')['seuils_B_herites']
    out.mkdir(parents=True, exist_ok=True)
    summary = {}
    fields = ['poids','bras','seuil','strate','photos','known','matched','proposals','unmatched','false',
              'unresolved','silent','empty_with_points']
    with (out/'courbes.csv').open('w', newline='') as stream:
        curves = csv.DictWriter(stream,fields,lineterminator='\n')
        curves.writeheader()
        for name in RUNS:
            data = read(predictions/(name+'.json'))
            if set(data) != {p['id'] for p in photos}:
                raise ValueError('Population différente du sceau')
            features = matrices(name)
            detail = {p['id']:segment_photo(p,features[p['id']],data[p['id']],bank) for p in photos}
            strata = segment_totals(list(detail.values()))
            observed = contacts(photos,data,cutoffs[name],curves,name)
            save(out/(name+'.json'), dict(segments_par_photo=detail, segments_par_strate=strata, contacts=observed))
            counts = strata['ensemble']
            compact = {arm:{op:dict(seuil=v['seuil'],budget_tenu=v['budget_tenu'],**v['all'])
                            for op,v in measures.items()} for arm,measures in observed.items()}
            summary[name] = dict(segments=counts, contacts=compact, qualifie=decision(counts['criteres']))
            print(name, summary[name]['qualifie'], flush=True)
    passed = sum(v['qualifie'] for v in summary.values())
    save(out/'synthese.json', dict(poids=summary, poids_qualifies=passed, minimum=4, succes=passed>=4,
                                   sur_les_exemples_du_fit=fit_observations()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('predictions',type=Path)
    parser.add_argument('out',type=Path)
    evaluate(**vars(parser.parse_args()))
