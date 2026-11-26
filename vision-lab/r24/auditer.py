"""Rejeu public sur CPU des preuves de l'expérience, sans entraînement ni photo source."""
import argparse
import math
from pathlib import Path
from tempfile import TemporaryDirectory

from r19.donnees import SEEDS, fingerprint, read
from r21.plan import STEPS
from r24.diagnostic import ROOT, archived
from r24.mesures import replay
from r24.presenter import figures, tables


def reconcile_distances(actual, expected):
    count = 0
    def reconcile(a,b,k):
        nonlocal count
        x,y = a[k],b[k]
        if x!=y:
            if (not isinstance(x,float) or not isinstance(y,float) or abs(x-y)>2*math.ulp(y)
                    or (x<=10)!=(y<=10)):
                raise ValueError('Distance hors tolérance géométrique')
            a[k] = y
            count += 1
    for k,photo in actual.items():
        ref = expected[k]
        for name,cells in photo['paires_par_identite'].items():
            for a,b in zip(cells,ref['paires_par_identite'][name],strict=True):
                reconcile(a,b,'erreur_entree_px704')
        for field in ('couverture','au_seuil'):
            for a,b in zip(photo[field]['paires'],ref[field]['paires'],strict=True):
                reconcile(a,b,'distance_px')
    if actual!=expected:
        raise ValueError('Mesure différente après contrôle des distances')
    return count


def verify_public():
    config = read(ROOT/'configuration.json')
    for k,h in config['empreintes_gel'].items():
        if fingerprint(Path(k))!=h:
            raise ValueError(k)
    with TemporaryDirectory() as tmp:
        out=Path(tmp)
        archives=ROOT/'archives/bloc8'
        replay(out,archives)
        tables(out)
        figures(out,archives)
        adjusted=0
        for p in sorted(x for x in out.rglob('*') if x.is_file()):
            published=ROOT/p.relative_to(out)
            if p.parent.name=='mesures' and p.name.startswith('bloc8-'):
                adjusted+=reconcile_distances(read(p),read(published))
            elif p.read_bytes()!=published.read_bytes():
                raise ValueError('Pièce de revue différente '+str(p.relative_to(out)))
    result=read(ROOT/'decision.json')
    wins=[]
    for seed in SEEDS:
        def inventory(raw):
            triples,entries=set(),set()
            for k,r in raw.items():
                used=set()
                entries.update(c['instance'] for c in r['couverture']['paires'])
                for n,i in r['identites'].items():
                    q=i['conjoint_q']
                    if q is not None:
                        if q in used or not r['paires_par_identite'][n][q]['admissible_conjoint']:
                            raise ValueError('Appariement de triplet divergent')
                        used.add(q)
                        triples.add(n)
            return triples,entries
        _,e21=inventory(archived('R21',seed,768)[1])
        t22,e22=inventory(archived('R22',seed,768)[1])
        new,entries=inventory(read(ROOT/f'mesures/bloc8-{seed}-768.json'))
        valid=read('r21/inventaires.json')
        sources={r['provenance'] for r in valid.values() for i in r['identites'] if i['instance'] in new-t22}
        if len(e21|e22)!=26:
            raise ValueError('Union historique différente')
        if len(sources-{'Crigges'})>=2 and t22<=new and (e21|e22)<=entries:
            wins.append(seed)
        ledger=read(ROOT/f'archives/bloc8/{seed}/execution.json')
        if ledger['nombre_pas']!=768 or ledger['vues_train_lues_aux_controles']!=18:
            raise ValueError('Budget réel différent')
        for step in STEPS:
            if len(ledger['controles'][str(step)]['photos_evaluees'])!=9:
                raise ValueError('Nombre de lectures différent')
    if result['graines_conformes']!=wins or result['poursuite_scientifique']!=(len(wins)>=2):
        raise ValueError('Verdict indépendant différent')
    return adjusted


if __name__=='__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    print('Rejeu R24, distances géométriques dans la tolérance de deux ULP',verify_public())
