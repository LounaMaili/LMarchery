"""Conserver les sorties brutes sans photos, les journaux complets et l'inventaire local."""
import gzip
import json
import shutil
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path

from r27.bilan import ROOT, NUM, SEEDS, STEPS, read, save

LOCAL = Path('artifacts/r27/essai')


def asset(path, role):
    digest = sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(8<<20),b''):
            digest.update(block)
    return dict(nom=path.name,role=role,fichier=str(path),octets=path.stat().st_size,sha256=digest.hexdigest())


def compact_log(row):
    fields = ('pas','provenance','photo','pertes','gradients','norme_avant','norme_apres','ecrete',
              'deplacement','sha256_geles','secondes','rss_max_Kio')
    small = {k:row[k] for k in fields}
    small['coefficient_global'] = row.get('coefficient_global')
    sampling = row['echantillonnage']
    for name in ('rpn','roi'):
        s = sampling[name]
        small[name] = dict(eligibles_pos=s['disponibles_pos'],eligibles_neg=s['disponibles_neg'],
                           neutres=s['ignores'],hors_quota=s['hors_quota'],
                           positifs=len(s['utilises_pos']),negatifs=len(s['utilises_neg']))
    small['pixels_masque_incidence'] = dict(positifs=sum(p for _,p,_ in sampling['masses']),
                                           negatifs=sum(n for _,_,n in sampling['masses']))
    return small


def archive_logs(out, seed, rows):
    with (out/f'pas-{seed}.jsonl').open('w') as outlog:
        for row in rows:
            outlog.write(json.dumps(row,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n')
    def average(samples):
        summary = {k:sum(s[k] for s in samples)/len(samples) for k in ('norme_avant','norme_apres','secondes')}
        for k in ('pertes','gradients','deplacement'):
            summary[k] = {h:sum(s[k][h] for s in samples)/len(samples) for h in samples[0][k]}
        for k in ('rpn','roi','pixels_masque_incidence'):
            counter = Counter()
            for s in samples:
                counter.update(s[k])
            summary[k+'_sommes'] = dict(counter)
        return dict(pas=len(samples),ecretes=sum(s['ecrete'] for s in samples),
                    rss_max_Kio=max(s['rss_max_Kio'] for s in samples),**summary)
    cycles, sources = defaultdict(list), defaultdict(list)
    for row in rows:
        cycles[1+(row['pas']-1)//8].append(row)
        sources[row['provenance']].append(row)
    return dict(ensemble=average(rows),presentations_par_photo=dict(Counter(r['photo'] for r in rows)),
                cycles={k:average(v) for k,v in cycles.items()},
                provenances={k:average(v) for k,v in sources.items()})


def package():
    end = read(LOCAL/'fin.json')
    draws = read('r21/tirages.json')
    srcs = read('r26/inventaire.json')['photos']
    train = read('r26/train.json')['photos']
    token = read(ROOT/'barriere.json')['sha256_geles']
    registry, inventory, journals, seen = {}, [], {}, {}
    out = ROOT/'sorties'
    out.mkdir(exist_ok=True)
    NUM.mkdir(exist_ok=True)
    counts = Counter()
    for seed in SEEDS:
        key = str(seed)
        folder = LOCAL/key
        registry[key] = {}
        steps = [s for s in STEPS if (folder/f'pas-{s:03d}.pt').exists()]
        assert steps == ([0] if seed==9301 and (LOCAL/'suite.json').exists() else list(STEPS))
        for step in steps:
            checkpoint = folder/f'pas-{step:03d}.pt'
            inventory.append(asset(checkpoint,f'Poids, SGD, RNG · {seed} / {step}'))
            records = {}
            for photo in train:
                path = folder/f'{step:03d}-{photo}.npz'
                rec = asset(path,f'Lecture autonome · {seed} / {step} / {photo}')
                inventory.append(rec)
                digest = rec['sha256']
                if digest not in seen:
                    dest = out/f'{seed}-{path.name}'
                    shutil.copyfile(path,dest)
                    seen[digest] = str(dest.relative_to(ROOT))
                records[photo] = dict(fichier=seen[digest],octets=rec['octets'],sha256=digest)
                counts['lectures'] += 1
            registry[key][str(step)] = records
        log = folder/'pas.jsonl.gz'
        inventory.append(asset(log,'Journal complet des mises à jour et indices utilisés · '+key))
        shutil.copyfile(log,NUM/f'echantillonnage-{seed}.jsonl.gz')
        with gzip.open(log,'rt') as stream:
            rows = [compact_log(json.loads(line)) for line in stream]
        assert len(rows)==(3 if steps==[0] else 768)
        for i,row in enumerate(rows):
            assert all(row[k]==v for k,v in draws[key][i].items())
            assert row['sha256_geles']==token
            for stage,quota in (('rpn',256),('roi',512)):
                s = row[stage]
                assert s['positifs']<=min(128,s['eligibles_pos']) and s['negatifs']<=s['eligibles_neg']
                assert s['positifs']+s['negatifs']<=quota
        journals[key] = archive_logs(NUM,seed,rows)
        counts['mises_a_jour'] += len(rows)
    assert all(counts[k]==end[k] for k in ('lectures','mises_a_jour'))
    for name in ('lancement.json','suite.json','fin.json'):
        if (LOCAL/name).exists():
            inventory.append(asset(LOCAL/name,'Provenance et chronologie du pilote'))
    inventory.append(asset(Path('data/r27/model_final_f10217.pkl'),'Poids COCO complets de départ'))
    for entry in read(ROOT/'entrees.json')['photos'].values():
        rec=asset(Path(entry['fichier']),'Raster BGR R19 transmis au modèle')
        assert rec['sha256']==entry['sha256']
        inventory.append(rec)
    save(out/'index.json',dict(format='NPZ CPU float32, boîtes en bords natifs, bit q du uint16 pour le masque q',
         lectures=registry,lectures_reelles=counts['lectures'],capsules_distinctes=len(seen)))
    save(NUM/'optimisation.json',journals)
    save(ROOT/'inventaire-local.json',dict(repere='vision-lab/ de la branche R27, sauf les neuf photos depuis build/worktrees',
         photos=srcs,environnement='environnement.json',capsules=inventory,compteurs=dict(counts),
         erreur_avant_SGD={'graine':9301,'tentative':4} if (LOCAL/'suite.json').exists() else None,
         lancement=read(LOCAL/'lancement.json'),suite=read(LOCAL/'suite.json') if (LOCAL/'suite.json').exists() else None,fin=end))


if __name__=='__main__':
    package()
