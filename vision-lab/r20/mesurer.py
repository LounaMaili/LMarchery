"""Appariements des trois tâches séparées, puis des triplets par UNE même requête."""
from collections import Counter

import numpy as np

from r19.affecter import pairs
from r19.mesures import Contacts, axes_report, axis_cover, masks_report


def joint_assignment(matrix, names, allowed):
    """Triplets valides de cardinalité maximale, puis distance totale d'entrée minimale."""
    valid = np.array([[m['admissible_conjoint'] and m['q'] in allowed for m in matrix[k]] for k in names],bool).reshape(len(names),12)
    distances = np.array([[m['erreur_entree_px704']/10 for m in matrix[k]] for k in names],float).reshape(len(names),12)
    return {names[i]:q for i,q in pairs(valid,distances)}


def photo(ref, prediction, ceilings, threshold, cost):
    outputs,bits = prediction['contacts'],prediction['bits']
    masks = masks_report(ref,bits)
    axes = axes_report(ref,prediction['axes'])
    observer = Contacts(ref,outputs)
    kept = {q['id'] for q in outputs if q['score']>=threshold}
    coverage = observer.report(len(outputs),True)
    selected = observer.report(len(kept),True)
    complete = [n for n in ref['ids'] if n in ref['axes'] and n in ref['entries']]
    matrix = {}
    mask_owners = {m['instance']:m['sortie'] for m in masks['par_identite']}
    axis_owners = {a['instance']:a['sortie'] for a in axes['paires']}
    contacts = {c['instance']:c['sortie'] for c in coverage['paires']}
    selected_contacts = {c['instance']:c['sortie'] for c in selected['paires']}
    for i,name in enumerate(ref['ids']):
        cap = ceilings[name]
        matrix[name] = []
        for q,row in enumerate(outputs):
            area = masks['pixels_evaluables_par_sortie'][q]
            inter = masks['intersections'][i][q]
            denom = cap['positifs_natifs']+area
            dice = 2*inter/denom
            mask_ok = 10*inter*cap['denominateur_dice']>=9*cap['intersection_optimale']*denom
            hits = axis_cover(ref['axes'][name],row['segments']) if name in ref['axes'] else None
            dist = float(np.linalg.norm(ref['entries'][name]-row['xy'])) if name in ref['entries'] else None
            matrix[name].append(dict(q=q,intersection=inter,aire_evaluable_sortie=area,dice=dice,
                fraction_plafond=dice/cap['dice_max'],masque_90_pourcent=mask_ok,sondes_axe=hits,
                erreur_entree_px704=dist,admissible_conjoint=bool(mask_ok and hits is not None and hits>=11 and dist is not None and dist<=10)))
    all_slots=set(range(12))
    joint=joint_assignment(matrix,complete,all_slots)
    at_threshold=joint_assignment(matrix,complete,kept)
    fit={r['instance']:r['q'] for r in cost['par_identite']}
    identities={}
    for name in ref['ids']:
        mask_q=mask_owners.get(name)
        axis_q=axis_owners.get(name)
        contact_q=contacts.get(name)
        identities[name]=dict(complete=name in complete,masque_q=mask_q,axe_q=axis_q,entree_q=contact_q,
            entree_au_seuil_q=selected_contacts.get(name),meme_q_sur_trois_appariements_R19=mask_q is not None and mask_q==axis_q==contact_q,
            conjoint_q=joint.get(name),conjoint_au_seuil_q=at_threshold.get(name),affectation_perte_q=fit[name])
    points=Counter(tuple(p['xy']) for p in outputs)
    slots=[]
    for q,row in enumerate(outputs):
        def owner(assignment):
            return next((k for k,v in assignment.items() if v==q),None)
        slots.append(dict(q=q,score=row['score'],xy=row['xy'],garde_au_seuil=q in kept,
                autres_au_meme_maximum=points[tuple(row['xy'])]-1,affectation_cible=owner(fit),
                presence_negative_eligible=q in cost['negatifs'],masque_attribue=owner(mask_owners),
                axe_attribue=owner(axis_owners),entree_attribuee=owner(contacts),entree_au_seuil=owner(selected_contacts),
                triplet_attribue=owner(joint),hors_appariement_conjoint=q not in joint.values(),
                couverture_multiple=masks['fusions'].get(str(q),[]),fragments=len(row['segments'])))
    info = dict(photos=1,instances_completes=len(complete),masques_admis=len(ref['ids']),axes_admis=len(ref['axes']),
        entrees_admises=len(ref['entries']),masques_apparies=sum(q is not None for q in mask_owners.values()),
        somme_dice_masques_R19=sum(m['dice'] for m in masks['par_identite']),axes_apparies=axes['apparies'],
        contacts_couverts=coverage['apparies'],contacts_au_seuil=selected['apparies'],propositions_au_seuil=selected['propositions'],
        non_appariees_au_seuil=selected['non_appariees'],faux_etablis_au_seuil=selected['faux_etablis'],
        non_resolus_au_seuil=selected['non_resolus'],silences_au_seuil=selected['silencieuses'],temoins_vides=0,
        meme_q_sur_trois_appariements_R19=sum(i['meme_q_sur_trois_appariements_R19'] for i in identities.values()),
        triplets=len(joint),triplets_au_seuil=len(at_threshold),sorties=len(outputs),sorties_hors_triplet=len(outputs)-len(joint),
        sorties_sans_cible_perte=len(outputs)-len(fit),negatives_eligibles=len(cost['negatifs']),coordonnees_distinctes=len(points),
        excedent_coordonnees=len(outputs)-len(points),fusions=len(masks['fusions']),dedoublements=len(masks['dedoublements']))
    return dict(bilan=info,identites=identities,sorties=slots,paires_par_identite=matrix,masques=masks,axes=axes,
                couverture=coverage,au_seuil=selected,seuil=threshold)


def aggregate(reports):
    counts=Counter()
    for row in reports:
        counts.update(row['bilan'])
    return dict(counts)
