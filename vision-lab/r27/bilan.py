"""Rejouer sur CPU les mesures et la décision depuis les sorties GitHub, sans modèle."""
import argparse
import json
import statistics
from collections import Counter
from hashlib import sha256
from pathlib import Path

from r19.donnees import read
from r26.decision import capacity
from r27.mesurer import matching, measure
import numpy as np

STEPS = (0,96,256,512,768)
SEEDS = (9301,9302,9303)
ROOT = Path('r27')
NUM = ROOT/'mesures'


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data,indent=2,ensure_ascii=False,allow_nan=False)+'\n')


def summary(rows):
    counts = Counter()
    dice, ids, admitted = [], [], []
    for row in rows:
        counts.update(row['effectifs'])
        for n, inst in row['identites'].items():
            dice.append(inst['dice_attribue'])
            if inst['q_separe'] is not None:
                ids.append(n)
            if inst['q_admis'] is not None:
                admitted.append(n)
    return dict(**counts, identites_separees=sorted(ids), identites_admises=sorted(admitted),
                dice_median=statistics.median(dice) if dice else None)


def groups(photos):
    result = {}
    for k,row in photos.items():
        categories = ['provenance/'+row['provenance'],*['condition/'+s for s in row['conditions']],
                      'inventaire/'+('complet' if row['inventaire_complet'] else 'partiel')]
        for category in categories:
            result.setdefault(category, []).append(row)
    return {key:summary(rows) for key,rows in result.items()}


def historical(seed, step, specs, ceilings):
    archive = Path('r22/mesures')/f'dice-{seed}-{step}.json'
    rows = read(archive)
    photos = {}
    for k, spec in specs.items():
        row = rows[k]
        names = sorted(spec['instances'],key=lambda n:spec['instances'][n]['indice'])
        matrix = np.array([[c['intersection'] for c in row['paires_par_identite'][n]] for n in names])
        areas = np.array(row['masques']['pixels_evaluables_par_sortie'])
        ids, covered, a, s = matching(matrix,names,areas,ceilings[k]['instances'])
        assert int((covered.sum(0)>=2).sum()) == len(row['masques']['fusions'])
        assert int((covered.sum(1)>=2).sum()) == len(row['masques']['dedoublements'])
        photos[k] = dict(provenance=spec['provenance'], supports=spec['supports'], conditions=spec['conditions'],
            inventaire_complet=spec['inventaire_R21_complet'], identites=ids,
            effectifs=dict(photos=1,identites=len(names),sorties=12,admis=len(a),separes=len(s),
                           sorties_fusionnees=len(row['masques']['fusions']),
                           identites_dedoublees=len(row['masques']['dedoublements'])))
    if step == 768:
        historic = read(Path('r26/mesures')/f'dice-{seed}.json')['photos']
        for key,row in photos.items():
            for label,field in (('masques_31','q_admis'),('separes_31','q_separe')):
                assert {n:inst[field] for n,inst in row['identites'].items() if inst[field] is not None} == historic[key]['appariements'][label]
    return dict(source=str(archive),sha256=sha256(archive.read_bytes()).hexdigest(),
                ensemble=summary(photos.values()),groupes=groups(photos),
                par_photo={k:summary([v]) for k,v in photos.items()})


def state_for_rule(row):
    return dict(separes=row['identites_separees'],dice_median=row['dice_median'],
                sorties_fusionnees=row['sorties_fusionnees'],identites_dedoublees=row['identites_dedoublees'])


def decide(curves, refs, specs, protected, config):
    sources = {n:s['provenance'] for s in specs.values() for n in s['instances']}
    complete = {}
    result = {}
    for seed in SEEDS:
        key = str(seed)
        points = curves[key]
        missing = [step for step in STEPS if str(step) not in points]
        if missing:
            result[key] = dict(statut='incomplet',controles_manquants=missing,conforme=None,stable=None)
            continue
        end, mid = points['768'], points['512']
        local = dict(config,graines=[seed])
        values = capacity({key:state_for_rule(end)}, {key:state_for_rule(mid)}, sources, protected, local)
        r = values['graines'][key]
        separated, before = set(end['identites_separees']), set(mid['identites_separees'])
        base = refs[f'{seed}-768']['ensemble']
        old = set(protected[key]['identites'])
        result[key] = dict(**r, statut='complet',proteges=sorted(old),conserves=sorted(old & separated),
            gagnes_depuis_512=sorted(separated-before), perdus_depuis_512=sorted(before-separated),
            fusions_R22=base['sorties_fusionnees'],fusions_R27=end['sorties_fusionnees'],
            maximum_fusions=protected[key]['maximum_fusions'],
            dedoublements_R22=base['identites_dedoublees'],dedoublements_R27=end['identites_dedoublees'],
            maximum_dedoublements=protected[key]['maximum_dedoublements'])
        complete[key] = True
    go = None
    if len(complete) == len(SEEDS):
        go = capacity({k:state_for_rule(v['768']) for k,v in curves.items()},
                      {k:state_for_rule(v['512']) for k,v in curves.items()},sources,protected,config)['GO_capacite']
    n_conform = sum(r['conforme'] is True for r in result.values())
    max_conform = n_conform + len(SEEDS) - len(complete)
    lost_known = any(result[key]['perdus'] for key in complete)
    return dict(GO_capacite=go,statut='non_concluant_technique' if go is None else ('GO' if go else 'NO-GO'),
                graines=result,graines_conformes_observees=n_conform,
                maximum_conformes_si_tous_manquants_conformes=max_conform,
                GO_logiquement_possible_avec_les_fins_manquantes=(
                    max_conform>=config['decision']['graines_conformes_min'] and not lost_known),
                graines_completes=len(complete),NO_GO_Android=True)


def replay(out):
    registry = read(ROOT/'sorties/index.json')
    specs = read('r26/train.json')['photos']
    ceilings = read('r20/plafonds.json')
    for k,s in specs.items():
        assert sha256(Path(s['masque']).read_bytes()).hexdigest() == s['sha256_masque']
    curves, provenance, changes = {}, {}, {}
    refs = {f'{seed}-{step}':historical(seed,step,specs,ceilings) for seed in SEEDS for step in (96,768)}
    for seed in SEEDS:
        key = str(seed)
        curves[key], provenance[key], changes[key] = {}, {}, {}
        prior = set()
        for step in STEPS:
            records = registry['lectures'][key].get(str(step))
            if records is None:
                continue
            assert set(records) == set(specs)
            rows = {}
            for k, record in records.items():
                path = ROOT / record['fichier']
                assert path.stat().st_size == record['octets'] and sha256(path.read_bytes()).hexdigest() == record['sha256']
                rows[k] = measure(path,specs[k],ceilings[k]['instances'])
            state = summary(rows.values())
            curves[key][str(step)] = state
            provenance[key][str(step)] = groups(rows)
            present = set(state['identites_separees'])
            change = dict(entrees_depuis_controle_precedent=sorted(present-prior) if step else None,
                          sorties_depuis_controle_precedent=sorted(prior-present) if step else None)
            if step in (96,768):
                base = set(refs[f'{seed}-{step}']['ensemble']['identites_separees'])
                change.update(gains_vs_R22_separe=sorted(present-base), pertes_vs_R22_separe=sorted(base-present),
                              conservation_vs_R22_separe=sorted(base&present))
            changes[key][str(step)] = change
            prior = present
            save(out/f'{seed}-{step}.json',rows)
    decision = decide(curves,refs,specs,read('r26/temoins.json'),read('r26/configuration.json'))
    save(out/'courbes.json',curves)
    save(out/'groupes.json',provenance)
    save(out/'transitions.json',changes)
    save(out/'references-R22.json',refs)
    save(out/'decision.json',decision)


if __name__ == '__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=NUM)
    replay(cli.parse_args().sortie)
