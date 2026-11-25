"""Rapprocher la relecture des neuf images entières, les références R18 et les archives R20."""
from collections import Counter
from pathlib import Path

import numpy as np

from r19.donnees import cell_indices, read, save, work_to
from r19.mesures import candidate
from r20.corpus import references
from r20.rejouer import compact

ROOT=Path('r21')
# Constat visuel de la photo entière. Le nombre et le motif précèdent le rapprochement avec les cibles.
LECTURES={
    '13-01':(1,True,'Support numéroté 4 et bords du cadre. Une tige sombre à empennage bleu traverse les anneaux. Les perforations du centre et attaches du papier sont distinctes.'),
    '70-01':(1,True,'Support 4, bord du support voisin et fond du cadre. Une tige à encoche verte et ailettes bleues. Les petites pièces colorées sur les bords sont des attaches.'),
    '7245527662':(4,True,'Blason partiellement cadré. Les quatre tiges sortent séparément par la droite, deux obliques et deux presque horizontales. Le recouvrement central et le papier soulevé limitent les jonctions. Le rectangle ignoré coupe le groupe et laisse les corps suivis de part et d’autre.'),
    '5115614018':(5,True,'Butte blanche, feuille et pied. Une flèche isolée en haut et quatre dans le groupe inférieur. Les ombres partent des jonctions vers le bas. Les empennages et les portions séparables du groupe rendent compte des cinq corps.'),
    '18105652008':(11,False,'Deux sacs dans le cadre. Deux flèches actives à gauche. À droite, neuf identités documentaires recouvrent notamment les six relevées lors de R18. Le rectangle exclu contient des présences physiques.'),
    '24614689941':(3,True,'Trois empennages et trois corps convergent vers le blason central. Les portions des blasons voisins visibles au bord du cadre ne montrent pas d’autre fût. Le corps venant du bas garde une jonction incertaine.'),
    '4169106197':(1,True,'Les trois spots, le panneau rouge et le bois autour sont visibles. Une flèche à ailettes vertes entre dans le spot 2. La longue trace oblique descendante est son ombre.'),
    'wc71488242':(11,False,'Butte verticale, groupe central et fûts isolés au-dessous. Les identités 4 et 5 restent accolées devant le centre et documentées sans champ actif. Les exclusions du groupe sont conservées.'),
    'wc11001401':(5,True,'Butte ronde, chevalet, herbe et rubalise de droite. Cinq fûts à ailettes colorées viennent de gauche, un en haut et quatre groupés en bas. Les piquets à rubalise sont visibles à droite du chevalet.')
}


def inventory(refs):
    rows={}
    for k,(count,confirmed,description) in LECTURES.items():
        ref=refs[k]
        a=ref['annotation']
        native={i['instance']:i for i in a['instances']}
        admitted=[]
        for t in ref['record']['instances']:
            name=t['instance']
            fields=[short for short,flag in [('masque','masque_valide'),('axe','axe_valide'),('entree','entree_valide')] if t[flag]]
            original=native[name]
            admitted.append(dict(instance=name,indice=t['indice'],champs=fields,
                        entree_lue=original['entree']['etat'],lecture_du_fut=original['observation']))
        inactive=[t['instance'] for t in admitted if not t['champs']]
        if set(native)!={t['instance'] for t in admitted} or len(native)!=count or confirmed!=bool(not inactive and count<=12):
            raise ValueError('Correspondance des présences physiques à revoir '+k)
        rows[k]=dict(provenance=a['provenance'],supports=a['supports'],champ_entier_xyxy=[0,0,*a['dimensions']],
            lecture_visuelle=description,physiques_documentees=count,admissible=confirmed,
            actives=count-len(inactive),completes=sum(len(t['champs'])==3 for t in admitted),
            capacite_requetes=12,capacite_suffisante=count<=12,documentaires_sans_cible=inactive,
            presences_hors_inventaire='aucune autre présence observable relevée dans le champ' if confirmed else 'inventaire actif incomplet',
            drapeau_historique=a['historique_R17_complet'],exclusions=a['regions_ignorees'],identites=admitted,
            sha256_image=ref['spec']['sha256_BGR_travail'],sha256_annotation=ref['spec']['sha256_annotation'],
            sha256_masque=ref['spec']['sha256_masque'])
    return rows


def inspect_slots(ref,report,pred):
    mask=ref['mask'];grid=ref['grid'];cells=cell_indices(ref['size'],grid)
    known=np.bincount(cells[mask==0],minlength=int(grid.prod()))
    total=np.bincount(cells.ravel(),minlength=int(grid.prod()))
    pure=(known==total)&(total>0)
    anchors=report['paires_par_identite']
    qs=[]
    for r in report['sorties']:
        q=r['q'];cs={k:v[q] for k,v in anchors.items()}
        near={k for k,c in cs.items() if c['erreur_entree_px704'] is not None and c['erreur_entree_px704']<=10}
        shafts={k for k,c in cs.items() if c['masque_90_pourcent'] and c['sondes_axe']>=11}
        mixed={k for k,c in cs.items() if c['sondes_axe']>=11 and 4*c['intersection']>=
               next(m['pixels_reference'] for m in report['masques']['par_identite'] if m['instance']==k)}
        peak=np.rint(work_to(r['xy'],ref['view'],grid)).astype(int)
        peak_on_bg=bool(pure[peak[1]*grid[0]+peak[0]])
        active=(pred['bits'].ravel()&(1<<q))!=0
        full_bg=bool(peak_on_bg and active.any() and np.all(pure[active]))
        known_shafts=shafts.intersection(ref['entries'])
        conflict=bool(known_shafts and near and not (known_shafts&near)) or len(mixed)>1
        # Le recouvrement et l'axe doivent corroborer l'entrée. Une entrée inconnue exige un masque fort.
        claim=(mixed & near) | shafts.difference(ref['entries'])
        qs.append(dict(q=q,score=r['score'],presence=pred['contacts'][q]['presence'],xy=r['xy'],
            autres_meme_coord=[s['q'] for s in report['sorties'] if s['q']!=q and s['xy']==r['xy']],
            affectation_perte=r['affectation_cible'],entrees_proches=sorted(near),futs_masque_axe=sorted(shafts),
            futs_recouvrement_axe=sorted(mixed),fusion_masque_25=r['couverture_multiple'],
            revendication=next(iter(claim)) if len(claim)==1 and not conflict else None,confusion_inter_fleches=conflict,
            raccord_non_verifiable=bool(shafts.difference(ref['entries']) and near),
            maximum_fond_etabli=peak_on_bg,sortie_fond_etabli=full_bg))
    claims=Counter(v['revendication'] for v in qs if v['revendication'])
    for v in qs:
        v['revendication_multiple']=claims[v['revendication']]>1 if v['revendication'] else False
        if v['confusion_inter_fleches']:status='confusion_inter_fleches'
        elif v['revendication_multiple']:status='revendication_multiple'
        elif v['revendication']:status='revendication_unique'
        elif v['sortie_fond_etabli']:status='fond_etabli'
        else:status='non_resolue'
        v['statut_champs']=status
    return dict(sorties=qs,statuts=dict(Counter(q['statut_champs'] for q in qs)),
                positions_distinctes=len({tuple(q['xy']) for q in qs}),
                requetes_avec_coord_repetee=sum(bool(q['autres_meme_coord']) for q in qs),
                pics_sur_fond_etabli=sum(q['maximum_fond_etabli'] for q in qs))


def audit(out):
    refs=references()
    if set(refs)!=set(LECTURES):raise ValueError('Périmètre train modifié')
    registry=inventory(refs)
    save(out/'inventaires.json',registry)
    archive=Path('r20/archives')
    rows={}
    for seed in (9301,9302,9303):
        pred=candidate(refs,archive/'A'/str(seed))
        reports=read(f'r20/mesures/A-{seed}.json')['par_photo']
        rows['A-'+str(seed)]={k:inspect_slots(r,reports[k],pred[k]) for k,r in refs.items()}
    k=read('r20/configuration.json')['photo_B']
    pred=candidate({k:refs[k]}, archive/'B/0096')[k]
    rows['B-96']={k:inspect_slots(refs[k],read('r20/mesures/B-0096.json'),pred)}
    compact(out/'lecture-sorties-R20.json',rows)
    return registry,rows


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    args=p.parse_args();args.out.mkdir(exist_ok=True,parents=True)
    audit(args.out)
