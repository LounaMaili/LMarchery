"""Comparer les deux polygones Crigges et publier les champs partiels ou exclus."""
import argparse
from pathlib import Path

import numpy as np

from r14.mesurer import read, save
from r18.supervision import ROOT, polygons_mask


def generate(out):
    validity = read(out/'validites.json')['vues']
    source = read('r17/polygones.json')['polygones']
    examples, issues = [], []
    for view in validity:
        k = view['photo']
        photo = read(ROOT/'annotations'/(k+'.json'))
        for row, t in zip(view['instances'], photo['instances'], strict=True):
            missing = []
            if not row['masque_lu']:
                missing += ['masque et axe sans attribution individuelle']
            elif not row['masque_valide']:
                missing += ['masque et axe entièrement dans une exclusion']
            elif row['pixels_masque_valides'] < row['pixels_masque_lus']:
                missing += ['portion de masque ignorée']
            if row['longueur_axe_admise_px'] < row['longueur_axe_lue_px'] and row['axe_valide']:
                missing += ['portion d’axe ignorée']
            if not row['entree_valide']:
                missing += ['entrée dans une exclusion' if row['exclusions_entree'] else 'entrée '+row['etat_entree'].replace('_',' ')]
            if missing:
                issues.append(dict(photo=k,provenance=view['provenance'],instance=t['instance'],champs=missing,
                    observation=t['observation'],motif_entree=t['entree']['motif'],regions_intersectees=row['regions_rencontrees'],
                    regions_documentaires=t.get('regions_identite_indecidable',[]),
                    pixels_observes=row['pixels_masque_lus'],pixels_admis=row['pixels_masque_valides']))
            if view['role'] != 'compatibilite_Crigges':
                continue
            src = t['historique']['masque_reference']
            old = polygons_mask([source[src]['points_travail']], tuple(reversed(photo['dimensions'])))
            new = polygons_mask(t['polygones_observes'], old.shape)
            both, union = int((old & new).sum()), int((old | new).sum())
            examples.append(dict(photo=k,instance=t['instance'],reference_polygone=src,statut='compatible_sur_ce_cas_avec_ecarts_de_bord',
                source_px=int(old.sum()),R18_px=int(new.sum()),intersection_px=both,union_px=union,iou=both/union,
                source_seule_px=int((old & ~new).sum()),R18_seul_px=int((new & ~old).sum()),
                observation='Les deux contours partent après les ailettes bleues et suivent le corps sombre. Écarts de bord sur la tige et à la lèvre de la perforation. R18 utilise son contour relu.'))
    save(out/'compatibilite.json',dict(identites_source=129,verifiees=2,non_verifiees=127,exemples=examples,
        autres='Convention non vérifiée individuellement. Les polygones sources R7 et R8 restent les références historiques.'))
    save(out/'champs-exclus.json',dict(version='R18-1',cas=issues,
        residuel='Tout pixel hors contour ou plage de fond admise est ignoré sur les vues occupées. Les portions non tracées ne sont pas extrapolées.'))
    lines = ['# Champs exclus et portions ignorées','','Chaque ligne concerne une instance dans une vue. Les champs admis restent disponibles dans '
        'le [registre des validités](validites.json). Le [registre détaillé](champs-exclus.json) conserve les motifs, les régions et les aires.','',
        '| Acquisition | Instance | Champ concerné |','|---|---|---|']
    lines.extend(f"| {i['photo']} | {i['instance']} | {', '.join(i['champs'])} |" for i in issues)
    (out/'champs-exclus.md').write_text('\n'.join(lines)+'\n')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    generate(parser.parse_args().out)
