"""Construire les partitions et appliquer exactement les minima et unités de R14."""
from collections import Counter
from hashlib import sha256
from pathlib import Path

from r15.recherche import read, save

LAB = Path('r15')
SPLITS = ('train', 'dev_indep', 'dev_explore')
SEALED = ('r14/partitions.json', 'r14/banque-segments.json', 'r14/portes.json',
          'r15/protocole.md', 'r15/lectures.json', 'r15/partitions.json',
          'r15/banque-segments.json', 'r15/doublons.json', 'r15/effectifs.json',
          'r15/portes.json', 'r15/configuration.json')


def fingerprint(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def counts(rows, bank):
    ids = {p['id'] for p in rows}
    negatives = [s for s in bank if s['photo'] in ids and s['statut']=='negatif_verifie']
    acquisitions = {s['photo'] for s in negatives}
    tubes = [t for p in rows for t in p['tubes']]
    return dict(photos=len(rows), provenances_occupees=len({p['provenance'] for p in rows if p['tubes']}),
        instances_distinctes=len({t['instance'] for t in tubes}),
        entrees_distinctes=len({t['instance'] for t in tubes if t['contact_visible'] or t.get('autre_vue_ou_lecture_visible')}),
        axes_traces_distincts=len({t['instance'] for t in tubes if t['axe']}),
        groupes=len({(p['provenance'],tuple(p['supports'])) for p in rows if 'grouped' in p['conditions']}),
        supports_vides_uses=len({(p['provenance'],tuple(p['supports'])) for p in rows if
                    p['complet'] and {'empty','worn'}<=set(p['conditions'])}),
        segments_negatifs=len(negatives), acquisitions_negatives=len(acquisitions),
        occupees_avec_negatifs=sum(bool(p['tubes']) and p['id'] in acquisitions for p in rows),
        classes_negatives=dict(Counter(s['lecture'] for s in negatives)))


def gates(totals, baseline):
    result = {}
    for part,ref in baseline['partitions'].items():
        totals_part = totals[part]['total']
        criteria = {}
        for name,item in ref['criteres'].items():
            n,minimum = totals_part[name],item['minimum']
            criteria[name] = dict(observe=n, minimum=minimum, manque=max(0,minimum-n))
        cats = totals_part['classes_negatives']
        diversity = bool(cats.get('blason')) and bool(cats.get('decor') or cats.get('ombre'))
        result[part] = dict(criteres=criteria, diversite_negatifs=diversity,
                            admissible=diversity and all(r['manque']==0 for r in criteria.values()))
    return dict(partitions=result, deux_portes_franchies=all(p['admissible'] for p in result.values()),
                entrainement_execute=False)


def corpus():
    return read('r14/partitions.json')['photos'] + read(LAB/'lectures.json')['photos']


def bank():
    return read('r14/banque-segments.json') + read(LAB/'banque-segments.json')


def main():
    old = read('r14/partitions.json')['photos']
    fresh = read(LAB/'lectures.json')['photos']
    rows = old + fresh
    negatives = bank()
    for key in ('id','sha256_pixels'):
        if len({r[key] for r in rows}) != len(rows):
            raise ValueError(f'Doublon {key}')
    old_summary = read('r14/effectifs.json')['partitions']
    new_ids = {r['id'] for r in fresh}
    groups = {}
    for p in rows:
        a = p['provenance'].casefold()
        if groups.setdefault(a,p['partition']) != p['partition']:
            raise ValueError(f'Provenance entre partitions: {a}')
    if len({p['provenance'] for p in fresh})!=len(fresh):
        raise ValueError('Deux vues d’une même provenance ajoutées')
    summaries = {}
    for part in SPLITS:
        current = [p for p in rows if p['partition']==part]
        inherited = [p for p in old if p['partition']==part]
        if counts(inherited,negatives)!=old_summary[part]['total']:
            raise ValueError(f'Parité historique {part}')
        summaries[part] = dict(total=counts(current,negatives),
            delta_R15=counts([p for p in current if p['id'] in new_ids],negatives),
            par_provenance={a:counts([p for p in current if p['provenance']==a],negatives)
                            for a in sorted({p['provenance'] for p in current})})
    # Un index exhaustif léger lie chaque acquisition à sa lecture immuable.
    save(LAB/'partitions.json',dict(base='r14/partitions.json',delta='r15/lectures.json',
        partitions={s:[dict(photo=p['id'],provenance=p['provenance'],supports=p['supports'],
            sha256_pixels=p['sha256_pixels'],lecture='R15' if p['id'] in new_ids else 'R14')
            for p in rows if p['partition']==s] for s in SPLITS}))
    save(LAB/'effectifs.json',dict(partitions=summaries))
    decision = gates(summaries,read('r14/portes.json'))
    save(LAB/'portes.json',decision)
    if decision['deux_portes_franchies']:
        files = {k:dict(sha256=fingerprint(k),octets=Path(k).stat().st_size) for k in SEALED}
        digest = sha256(''.join(f'{v["sha256"]}  {k}\n' for k,v in sorted(files.items())).encode()).hexdigest()
        save(LAB/'manifeste-scelle.json',dict(version=1,base='0941d6e97b41bc67ae040494bad6e1be826b3ae0',
                  protocole='ce737c4f7e86f2f07733b860878444929790ad8c',date='2026-11-24',
                  algorithme='SHA-256 de la concaténation UTF-8 triée par chemin, une ligne sha256(de la pièce) + deux espaces + chemin + LF',
                  sha256=digest,pieces=files,photos=len(rows),nouvelles=len(fresh),test='fermé',
                  prochaine_etape='revue du manifeste avant tout entraînement'))


if __name__=='__main__':
    main()
