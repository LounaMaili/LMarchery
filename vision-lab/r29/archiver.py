"""Transférer les observations du passage unique et leurs pièces de parité."""
from pathlib import Path

import numpy as np

from r29.commun import ROOT, LOCAL, SEEDS, HISTORICAL, digest, read, save


def pack():
    launch = read(LOCAL/'lancement.json')
    finish = read(LOCAL/'fin.json')
    cfg = read(ROOT/'configuration.json')
    assert finish['gel']==launch['gel'] and finish['passages']==len(list(LOCAL.glob('*.consomme')))==18
    dest = ROOT/'traces'
    dest.mkdir(parents=True,exist_ok=True)
    records, local = {}, []
    for seed in SEEDS:
        for photo in cfg['photos']:
            tag = f'{seed}-{photo}'
            parity = read(LOCAL/(tag+'-parite.json'))
            assert set(parity['champs'])==set(HISTORICAL) and all(v['identique'] for v in parity['champs'].values())
            historical = LOCAL/(tag+'-historique.npz')
            observed = LOCAL/(tag+'-observations.npz')
            stages = LOCAL/(tag+'-etages.npz')
            with np.load(observed,allow_pickle=False) as a,np.load(stages,allow_pickle=False) as b,np.load(historical,allow_pickle=False) as h:
                payload = {k:a[k] for k in a.files}
                payload.update({k:b[k] for k in b.files})
                payload.update({k:h[k] for k in ('rpn_logits','rpn_ancres','propositions')})
                path = dest/(tag+'.npz')
                np.savez_compressed(path,**payload)
            for f,role in [(observed,'Observations dans le repère modèle'),(historical,'Champs autonomes authentiques'),
                           (stages,'Reconstruction de sélection')]:
                local.append(dict(nom=f.name,role=role,fichier=str(f),octets=f.stat().st_size,sha256=digest(f)))
            records[tag]=dict(**parity,fichier=path.name,octets=path.stat().st_size,sha256=digest(path))
    save(dest/'index.json',dict(gel=launch['gel'],environnement=launch['environnement'],budget=18,passages=18,
         appels_masque_intermediaires=0,marqueurs=[p.read_text().strip() for p in sorted(LOCAL.glob('*.consomme'))],
         traces=records))
    save(ROOT/'inventaire-local.json',dict(repere='vision-lab/ du worktree R29',capsules=local,
         conservation_R27='r27/inventaire-local.json',checkpoints_R27=cfg['checkpoints']))


if __name__=='__main__':
    pack()
