"""Analyse rétrospective des matrices et cartes publiées. Aucune exécution de modèle."""
import argparse
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from r19.affecter import pairs
from r19.donnees import fingerprint, read, save
from r20.rejouer import compact
from r25.commun import ARMS, SEEDS, captures, tag

ROOT = Path('r26')
INPUT = Path('r25')


def assignment(edges, costs):
    names = list(edges)
    size = len(costs[names[0]]) if names else 0
    eligible = np.array([[q in edges[n] for q in range(size)] for n in names], bool).reshape(len(names),size)
    distance = np.array([costs[n] for n in names],float).reshape(len(names),size)
    return {names[i]:q for i,q in pairs(eligible,distance)}


def photo(row, spec, caps):
    matrix = row['paires_par_identite']
    all_ids = [i['instance'] for i in spec['instances'] if i['masque_valide']]
    known = [i['instance'] for i in spec['instances'] if i['masque_valide'] and i['entree_valide'] and i['axe_valide']]
    if list(matrix)!=all_ids or [n for n in all_ids if row['identites'][n]['complete']]!=known:
        raise ValueError('Dénominateur historique divergent')
    truth = {n:caps[n]['positifs_natifs'] for n in all_ids}
    covers = {q:[n for n in all_ids if 4*matrix[n][q]['intersection']>=truth[n]] for q in range(12)}
    edges = {k:{} for k in ('triplet','axe_parfait','masque_seul','masques_31','separes_31')}
    costs_m, costs_e = {}, {}
    for n,cells in matrix.items():
        if [c['q'] for c in cells]!=list(range(12)):
            raise ValueError('Ordre des requêtes historique')
        admitted=[]
        for c in cells:
            denom = truth[n]+c['aire_evaluable_sortie']
            ok = 10*c['intersection']*caps[n]['denominateur_dice']>=9*caps[n]['intersection_optimale']*denom
            if ok!=c['masque_90_pourcent']:
                raise ValueError('Désaccord du critère de masque')
            if ok:
                admitted.append(c['q'])
        edges['masques_31'][n]=admitted
        edges['separes_31'][n]=[q for q in admitted if all(m==n for m in covers[q])]
        costs_m[n]=[1-min(1.,c['fraction_plafond']) for c in cells]
        if n not in known:
            continue
        edges['masque_seul'][n]=admitted
        edges['axe_parfait'][n]=[q for q in admitted if cells[q]['erreur_entree_px704']<=10]
        edges['triplet'][n]=[q for q in edges['axe_parfait'][n] if cells[q]['sondes_axe']>=11]
        if edges['triplet'][n]!=[c['q'] for c in cells if c['admissible_conjoint']]:
            raise ValueError('Arêtes des triplets historiques')
        # Le coût d’une arête admise est dans [0,1] comme dans joint_assignment R20.
        costs_e[n]=[c['erreur_entree_px704']/10 for c in cells]
    matches={k:assignment(v,costs_e if k in ('triplet','axe_parfait') else costs_m) for k,v in edges.items()}
    archived={n:row['identites'][n]['conjoint_q'] for n in known if row['identites'][n]['conjoint_q'] is not None}
    if matches['triplet']!=archived:
        raise ValueError('Attribution historique divergente')
    merges={str(q):ids for q,ids in covers.items() if len(ids)>=2}
    duplicates={n:[q for q,ids in covers.items() if n in ids] for n in all_ids}
    duplicates={n:qs for n,qs in duplicates.items() if len(qs)>=2}
    if merges!=row['masques']['fusions'] or duplicates!=row['masques']['dedoublements']:
        raise ValueError('Fusions ou dédoublements divergents')
    return dict(provenance=spec['provenance'],supports=spec['supports'],identites_connues=known,
                identites_masque=all_ids,admissibles=edges,appariements=matches,
                fusions_25=merges,dedoublements_25=duplicates,
                effectifs=dict(entrees=len(known),masques=len(all_ids),**{k:len(v) for k,v in matches.items()},
                               sorties_fusionnees=len(merges),identites_dedoublees=len(duplicates)))


def density(axis,mask):
    if axis.shape!=mask.shape or axis.dtype!=np.float32 or mask.dtype!=np.float32 or not axis.size:
        raise ValueError('Format des cartes')
    if not np.isfinite(axis).all() or not np.isfinite(mask).all():
        raise ValueError('Logit non fini')
    a,m=axis>=0,mask>=0
    total=axis.size
    pos=int(a.sum())
    overlap=int(np.count_nonzero(a&m))
    mp=int(m.sum())
    return dict(cellules=total,axe=pos,masque=mp,intersection=overlap,entier=pos==total,
                au_moins_99=100*pos>=99*total,au_moins_95=100*pos>=95*total,identique_masque=mp==overlap)


def counts(rows):
    result=Counter()
    for r in rows:
        result.update(r)
    return dict(result)


def run(out=ROOT/'mesures'):
    out.mkdir(exist_ok=True,parents=True)
    validity=Path('r18/validites.json')
    specs={v['photo']:v for v in read(validity)['vues'] if v['partition']=='train'}
    if len(specs)!=9:
        raise ValueError('Périmètre du train')
    targets=Path('r20/plafonds.json')
    ceilings=read(targets)
    pca_file=INPUT/'bilan-identites.json'
    density_inventory=INPUT/'cartes/inventaire.json'
    inputs=[validity,targets,pca_file,density_inventory]
    a_cases=[v for v in read(pca_file) if v.get('limite')=='ligne_PCA']
    evidence,states=[],{}
    for arm in ARMS:
        for seed in SEEDS:
            source=Path('r22' if arm=='dice' else 'r24')/'mesures'/f'{arm}-{seed}-768.json'
            inputs.append(source)
            raw=read(source)
            if raw.keys()!=specs.keys():
                raise ValueError('Partition différente')
            by_photo={k:photo(raw[k],spec,ceilings[k]['instances']) for k,spec in specs.items()}
            origin=defaultdict(list)
            for record in by_photo.values():
                origin[record['provenance']].append(record['effectifs'])
            key=f'{arm}-{seed}'
            compact(out/(key+'.json'),dict(source=str(source),bras=arm,graine=seed,photos=by_photo))
            states[key]=dict(par_provenance={name:counts(rr) for name,rr in origin.items()},
                             ensemble=counts(r['effectifs'] for r in by_photo.values()))
            for c in a_cases:
                if c['bras_en_defaut']!=arm or c['graine']!=seed:
                    continue
                k,n=c['photo'],c['identite']
                evidence.append(dict(photo=k,identite=n,graine=seed,bras=arm,provenance=c['provenance'],
                    transition=c['statut'],entree_connue=n in by_photo[k]['identites_connues'],
                    requetes_masque_admissibles=by_photo[k]['admissibles']['masques_31'][n],
                    fractions_plafond_par_q=[v['fraction_plafond'] for v in raw[k]['paires_par_identite'][n]]))
    maps=read(density_inventory)
    density_rows=[]
    for case in captures():
        arm,seed,k=case
        name=tag(*case)
        assets=maps[name]
        if [t['q'] for t in assets]!=list(range(12)):
            raise ValueError('Périmètre de cartes')
        for rec in assets:
            path=INPUT/rec['fichier']
            if fingerprint(path)!=rec['sha256']:
                raise ValueError(str(path))
            with np.load(path,allow_pickle=False) as f:
                d=density(f['axe'],f['masque'])
            density_rows.append(dict(bras=arm,graine=seed,photo=k,provenance=specs[k]['provenance'],
                                     q=rec['q'],carte=str(path),**d))
    if set(maps)!={tag(*c) for c in captures()}:
        raise ValueError('Registre R25 différent')
    flags=('entier','au_moins_99','au_moins_95','identique_masque')
    def summary(rows):
        return dict(cartes=len(rows),**{key:sum(r[key] for r in rows) for key in flags})
    stats={key:summary([r for r in density_rows if key==f"{r['bras']}-{r['graine']}"]) for key in states}
    compact(out/'cartes.json',density_rows)
    save(out/'ligne-pca.json',evidence)
    save(out/'bilan.json',dict(etats=states,cartes_par_etat=stats,cartes_ensemble=summary(density_rows),
                               cas_PCA=len(evidence),cas_PCA_avec_masque=sum(bool(v['requetes_masque_admissibles']) for v in evidence)))
    save(out/'sources.json',dict(revision_R25='5cee1e0b287faab138f0d8ae4e961dec7b0b9ed8',
        fichiers=[dict(fichier=str(p),octets=p.stat().st_size,sha256=fingerprint(p)) for p in inputs],
        empreintes_des_264_cartes=str(density_inventory)))


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('out',nargs='?',type=Path,default=ROOT/'mesures')
    run(cli.parse_args().out)
