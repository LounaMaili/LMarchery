"""Décrire les échecs de la lecture autonome archivée, sans injecter de référence dans le réseau."""
from collections import Counter

import numpy as np

from r27.bilan import NUM, ROOT, read, save


def diagnose(out=NUM):
    index=read(ROOT/'sorties/index.json')['lectures']
    summary={}
    details={}
    for seed,steps in index.items():
        summary[seed],details[seed]={},{}
        for step,registry in steps.items():
            measured=read(out/f'{seed}-{step}.json')
            photos={}
            total=Counter(dict.fromkeys((
                'sans_proposition_RPN_50','sans_boite_finale_50','boite_50_sans_masque_admissible',
                'aucune_arete_masque','admissible_mais_recouvre_autrui','arete_separee_non_attribuee',
                'identites_separees','identites_non_separees','entree_inconnue','separe_entree_inconnue',
                'grille_sans_case_05','vide_apres_collage_avec_case_05','masques_vides'),0))
            for photo,rec in registry.items():
                row=measured[photo]
                ids={}
                for name,inst in row['identites'].items():
                    separated=inst['q_separe'] is not None
                    tags=[]
                    if not separated:
                        for condition,label in [
                            (inst['rpn']['iou']<.5,'sans_proposition_RPN_50'),
                            (inst['boite']['iou']<.5,'sans_boite_finale_50'),
                            (inst['boite']['iou']>=.5 and not inst['admissibles'],'boite_50_sans_masque_admissible'),
                            (not inst['admissibles'],'aucune_arete_masque'),
                            (bool(inst['admissibles']) and not inst['separes'],'admissible_mais_recouvre_autrui'),
                            (bool(inst['separes']),'arete_separee_non_attribuee')]:
                            if condition:
                                tags.append(label)
                    ids[name]=dict(separe=separated,entree_connue=inst['entree_valide'],observations=tags)
                    total.update(tags)
                    total['identites_separees']+=separated
                    total['identites_non_separees']+=not separated
                    total['entree_inconnue']+=not inst['entree_valide']
                    total['separe_entree_inconnue']+=separated and not inst['entree_valide']
                with np.load(ROOT/rec['fichier'],allow_pickle=False) as stored:
                    grids=stored['probabilites']
                outputs=[]
                for output,grid in zip(row['sorties'],grids):
                    active=int(np.count_nonzero(grid>=.5))
                    empty=output['aire']==0
                    total['grille_sans_case_05']+=active==0
                    total['vide_apres_collage_avec_case_05']+=empty and active>0
                    total['masques_vides']+=empty
                    outputs.append(dict(q=output['q'],maximum_probabilite=float(grid.max()),cases_05=active,
                                        aire_native=output['aire']))
                photos[photo]=dict(provenance=row['provenance'],identites=ids,grilles=outputs)
            details[seed][step]=photos
            summary[seed][step]=dict(total)
    save(out/'diagnostic.json',dict(ensemble=summary,photos=details))


if __name__=='__main__':
    diagnose()
