"""Mesures natives R26 depuis un seul passage enregistré, sans chargement de réseau."""
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

from r19.affecter import minimum
from r26.archives import assignment


def best_box(box, proposals):
    if not len(proposals):
        return dict(q=None, iou=0.)
    gt = np.array(box, dtype=np.float64)
    boxes = proposals.astype(np.float64)
    inter = np.maximum(0, np.minimum(boxes[:, 2:], gt[2:]) - np.maximum(boxes[:, :2], gt[:2])).prod(1)
    area = (gt[2:] - gt[:2]).prod() + (boxes[:, 2:] - boxes[:, :2]).prod(1) - inter
    scores = inter / area
    index = int(scores.argmax())
    return dict(q=index, iou=float(scores[index]))


def matching(matrix, names, sizes, capacities):
    """Mêmes sommets, seuils entiers, coûts et départages que R26, y compris 0 sortie."""
    num_pred = matrix.shape[1]
    intersections = matrix.astype(np.int64)
    truth = np.array([capacities[n]['positifs_natifs'] for n in names], dtype=np.int64)
    denom = truth[:, None] + sizes[None, :]
    dice = 2 * intersections / denom
    covers = 4 * intersections >= truth[:, None]
    costs, edges, separate = {}, {}, {}
    per_id = {}
    for i, n in enumerate(names):
        cap = capacities[n]
        ratio = dice[i] / cap['dice_max']
        eligible = 10 * intersections[i] * cap['denominateur_dice'] >= 9 * cap['intersection_optimale'] * denom[i]
        edges[n] = np.flatnonzero(eligible).tolist()
        separate[n] = np.flatnonzero(eligible & (covers.sum(0) - covers[i] == 0)).tolist()
        costs[n] = (1 - np.minimum(1., ratio)).tolist()
        best = int(np.argmax(dice[i])) if num_pred else None
        per_id[n] = dict(vrais=int(truth[i]), intersections=intersections[i].tolist(), dice=dice[i].tolist(),
                         iou=(intersections[i] / (denom[i]-intersections[i])).tolist(),
                         seuil_dice=.9*cap['dice_max'], meilleur_q=best,
                         meilleur_dice=float(dice[i,best]) if best is not None else 0.,
                         admissibles=edges[n], separes=separate[n],
                         couverture_25=np.flatnonzero(covers[i]).tolist())
    admitted = assignment(edges, costs)
    separated = assignment(separate, costs)
    total = minimum(np.column_stack((1-dice,np.ones((len(names),len(names))))))
    for i,n in enumerate(names):
        q = int(total[i])
        valid = q < num_pred and intersections[i,q] > 0
        per_id[n].update(q_admis=admitted.get(n), q_separe=separated.get(n),
                         q_dice_total=q if valid else None, dice_attribue=float(dice[i,q]) if valid else 0.)
    return per_id, covers, admitted, separated


def measure(path, spec, capacities):
    mask = cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
    names = sorted(spec['instances'],key=lambda n:spec['instances'][n]['indice'])
    indices = [spec['instances'][n]['indice'] for n in names]
    with np.load(path,allow_pickle=False) as f:
        bits = f['masques']
        boxes, rpn = f['boites'], f['rpn_boites']
        scores, origin = f['scores'], f['propositions']
        assert bits.shape == mask.shape and bits.dtype == np.uint16
        assert bits.max() < 1 << len(boxes) and len(boxes) <= 12 and len(rpn) <= 1000
        assert np.isfinite(scores).all() and (np.diff(scores) <= 0).all() and (scores > 0).all()
        assert len(f['rpn_logits']) == len(rpn) == len(f['rpn_ancres'])
        assert np.isfinite(rpn).all() and (np.diff(f['rpn_logits']) <= 0).all()
        assert (boxes[:, 2:] > boxes[:, :2]).all() and (rpn[:, 2:] > rpn[:, :2]).all()
        hist = np.array([np.bincount(mask[(bits & (1 << q)) != 0],minlength=256) for q in range(len(boxes))],
                        dtype=np.int64).reshape(-1,256)
        known, sizes = hist[:,:255].sum(1), hist.sum(1)
        ids, overlaps, admitted, separated = matching(hist[:,indices].T,names,known,capacities)
        duplicate = overlaps.sum(1) >= 2
        for n in names:
            roi = spec['instances'][n]
            ids[n].update(entree_valide=roi['entree_valide'], etat_entree=roi['etat_entree'],
                          rpn=best_box(roi['boite_xyxy'],rpn), boite=best_box(roi['boite_xyxy'],boxes))
        outputs = []
        for q, coord in enumerate(boxes):
            covered = [names[i] for i in np.flatnonzero(overlaps[:,q])]
            if q in admitted.values():
                kind = 'apparie'
            elif sizes[q] == 0:
                kind = 'vide'
            elif sizes[q] == hist[q,0]:
                kind = 'faux_fond_verifie'
            elif len(covered) >= 2:
                kind = 'fusion'
            elif np.any(overlaps[:,q] & duplicate):
                kind = 'dedoublement'
            else:
                kind = 'non_resolu'
            outputs.append(dict(q=q, q_RPN=int(origin[q]), score=float(scores[q]), boite=coord.tolist(),
                                aire=int(sizes[q]), aire_evaluable=int(known[q]), fond=int(hist[q,0]),
                                ignore=int(hist[q,255]), identites_25=covered, statut=kind))
    counts = dict(photos=1, identites=len(ids), propositions_RPN=len(rpn), sorties=len(outputs),
                  rpn_50=sum(v['rpn']['iou'] >= .5 for v in ids.values()),
                  boites_50=sum(v['boite']['iou'] >= .5 for v in ids.values()),
                  admis=len(admitted), separes=len(separated), sorties_fusionnees=int((overlaps.sum(0)>=2).sum()),
                  identites_dedoublees=int(duplicate.sum()), masques_vides=int((sizes==0).sum()),
                  non_apparies=len(outputs)-len(admitted), fond_pixels_incidence=int(hist[:,0].sum()),
                  fond_pixels_union=int(np.count_nonzero((mask==0)&(bits!=0))), fond_disponible=int((mask==0).sum()),
                  ignore_pixels_incidence=int(hist[:,255].sum()), photos_silencieuses=int(not len(outputs)),
                  temoins_vides=0)
    statuses = Counter(v['statut'] for v in outputs)
    for k in ('faux_fond_verifie','non_resolu','fusion','dedoublement','vide'):
        counts['non_apparies_'+k] = statuses[k]
    return dict(provenance=spec['provenance'], supports=spec['supports'], conditions=spec['conditions'],
                inventaire_complet=spec['inventaire_R21_complet'], identites=ids, sorties=outputs, effectifs=counts)
