"""Suivre chaque requête jusqu'au segment géométriquement compatible."""
import argparse
from collections import Counter
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.donnees import fingerprint, read, save
from r20.corpus import references
from r20.rejouer import compact
from r25.commun import ARMS, ROOT, captures, historical, matrices, tag
from r25.composantes import trace
from r25.geometrie import rounded
from r25.sondes import SAMPLE_COLUMNS, STAGES, observe, ref_probes

LIMITS=('axe_seuille','intersection_masque','taille_composante','elongation_PCA','portee_PCA',
        'ligne_PCA','decoupe_composante','portions_trop_courtes','orientation','attribution')


def last(counts):
    return max((j for j,stage in enumerate(STAGES) if counts[stage]>=11),default=-1)


def limiting(weak):
    depth=[last(q['comptes']) for q in weak['requetes']]
    best=max(depth)
    return dict(limite=LIMITS[best+1],requetes_les_plus_avancees=[q for q,v in enumerate(depth) if v==best],
                limites_par_q=[LIMITS[x+1] for x in depth], effectifs_limites=dict(Counter(LIMITS[x+1] for x in depth)))


def compact_components(records):
    # Les extrémités définitives sont disponibles à leur pleine précision dans les prédictions R22/R24.
    for row in records:
        row['centre']=[round(v,9) for v in row['centre']]
        p=row['pca']
        if p:
            for k in ('valeurs_propres','direction'):
                p[k]=[round(v,9) for v in p[k]]
            for k in ('t_min','t_max'):
                if k in p:
                    p[k]=round(p[k],9)
    return records


def analyze(out,maps=ROOT,geometry=ROOT,portable=False):
    cv.setNumThreads(1)
    refs=references()
    scope=read(ROOT/'configuration.json')
    proof=read(maps/'parite.json')
    files=read(maps/'cartes/inventaire.json')
    if not proof['parite_complete'] or len(files)!=22:
        raise ValueError('Archives non validées')
    anchors={k:ref_probes(ref) for k,ref in refs.items()}
    compact(out/'references-sondes.json',anchors)
    stats={}
    for case in captures():
        arm,seed,k=case
        ncase=tag(*case)
        ref=refs[k]
        meas=matrices(arm,seed)[k]
        old=read(historical(arm,seed)/'predictions.json')[k]['sorties']
        geom=read(geometry/'geometrie'/f'{arm}-{seed}'/(k+'.json'))['identites']
        axes=anchors[k]
        group={}
        for n,ax in axes.items():
            i=meas['identites'][n]
            group[n]=dict(axe_q=i['axe_q'],triplet_q=i['conjoint_q'],entree_q=i['entree_q'],
                          entree_connue=i['complete'],requetes=[])
        for q,asset in enumerate(files[ncase]):
            path=maps/asset['fichier']
            if asset['q']!=q or fingerprint(path)!=asset['sha256']:
                raise ValueError('Carte publiée différente '+ncase)
            with np.load(path,allow_pickle=False) as bundle:
                m,a=bundle['masque'],bundle['axe']
            if m.dtype!=np.float32 or a.dtype!=np.float32 or m.shape!=tuple(ref['grid'][::-1]) or m.shape!=a.shape:
                raise ValueError('Grille différente')
            components,stages,lines=trace((a>=0)&(m>=0))
            rows,final=observe(ref,axes,m,a,stages,lines,old[q]['segments'],not portable)
            for j,(n,details) in enumerate(rows.items()):
                archived=geom[n]['requetes'][q]
                if details['comptes']['oriente']!=archived['couvertes'] or details['comptes']['segment']!=archived['proches']:
                    raise ValueError('Couverture de sonde divergente')
                pairs=final[21*j:21*(j+1)]
                if any(x[-2:]!=y[-2:] for x,y in zip(pairs,archived['sondes'],strict=True)):
                    raise ValueError('Décisions par sonde différentes')
                # Les distances et angles de segment sont publiés dans la géométrie de cette photo/requête.
                cell=meas['paires_par_identite'][n][q]
                group[n]['requetes'].append(dict(q=q,comptes=details['comptes'],
                    masque_admis=cell['masque_90_pourcent'],erreur_entree_px704=rounded(cell['erreur_entree_px704'])
                        if cell['erreur_entree_px704'] is not None else None,
                    entree_admise=cell['erreur_entree_px704'] is not None and cell['erreur_entree_px704']<=10,
                    triplet_admissible=cell['admissible_conjoint']))
            relative=Path(ncase)/f'q{q:02}.json'
            props=dict(q=q,forme=list(a.shape),cellules_axe=int((a>=0).sum()),cellules_masque=int((m>=0).sum()),
                       cellules_intersection=int(((a>=0)&(m>=0)).sum()),segments=len(lines['final']),
                       colonnes_portions=['debut_inclus','fin_exclue','decision','index_segment_final'])
            compact(out/'composantes'/relative,dict(**props,composantes=compact_components(components)))
            compact(out/'sondes'/relative,dict(q=q,colonnes=SAMPLE_COLUMNS,identites=rows))
        stats[ncase]=group
        compact(out/'identites'/(ncase+'.json'),dict(photo=k,provenance=ref['spec']['provenance'],bras=arm,
                                                      graine=seed,identites=group))
    transitions=read(geometry/'diagnostic-archives.json')['transitions']
    by_key={(t['graine'],t['photo'],t['identite']):t for t in transitions}
    rows=[]
    for arm,seed,k in captures():
        if arm!=ARMS[0]:
            continue
        for n in anchors[k]:
            states={s:stats[tag(s,seed,k)][n] for s in ARMS}
            t=by_key.get((seed,k,n))
            if t:
                weak='bloc8' if t['statut']=='pertes' else 'dice'
                strong='dice' if weak=='bloc8' else 'bloc8'
                q=states[strong]['axe_q']
                reason=limiting(states[weak])
                result=dict(statut=t['statut'],bras_en_defaut=weak,q_appariee_autre_bras=q,**reason,
                            concurrence=t['concurrence'],comptes_q_appariee={s:states[s]['requetes'][q]['comptes'] for s in ARMS})
                if bool(t['concurrence'])!=(result['limite']=='attribution'):
                    raise ValueError('Concurrence divergente')
            else:
                result=dict(statut='conserves' if states['dice']['axe_q'] is not None else 'absents_deux')
            rows.append(dict(graine=seed,photo=k,identite=n,provenance=refs[k]['spec']['provenance'],**result,
                appariements={s:i['axe_q'] for s,i in states.items()},
                admissibles={s:[q['q'] for q in i['requetes'] if q['comptes']['oriente']>=11] for s,i in states.items()},
                triplets={s:i['triplet_q'] for s,i in states.items()}))
    compact(out/'bilan-identites.json',rows)
    summary={}
    for status in ('pertes','gains'):
        subset=[r for r in rows if r['statut']==status]
        summary[status]={stage:dict(cas=len(rr),provenances=sorted({r['provenance'] for r in rr}))
                         for stage in LIMITS if (rr:=[r for r in subset if r['limite']==stage])}
    save(out/'synthese.json',dict(cas_logiques=len(stats),graines=list(scope['graines']),observations_identite_graine=len(rows),
        profils=summary,statuts=dict(Counter(r['statut'] for r in rows)),nouveaux_entrainements=0,
        test_ferme=True,developpement_ferme=True,NO_GO_Android=True))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--cartes',type=Path,default=ROOT)
    p.add_argument('--geometrie',type=Path,default=ROOT)
    p.add_argument('--rejeu-portable',action='store_true')
    a=p.parse_args()
    analyze(a.out,a.cartes,a.geometrie,a.rejeu_portable)
