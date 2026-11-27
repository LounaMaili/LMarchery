"""Couples identité–sortie, contrôle géométrique et borne conditionnelle."""
import numpy as np

from r26.archives import assignment
from r27.mesurer import best_box


POLICIES = {'separe':'q_separe', 'admis':'q_admis', 'dice_total':'q_dice_total'}


def oracle(intersections, names, capacities):
    """La surface prédite de l'oracle dépend de i ET de q."""
    details, edges, costs = {}, {}, {}
    for name in names:
        cap = capacities[name]
        t = cap['positifs_natifs']
        values = intersections[name]
        dice = [2*i/(t+i) for i in values]
        feasible = [q for q,i in enumerate(values)
                    if 10*i*cap['denominateur_dice'] >= 9*cap['intersection_optimale']*(t+i)]
        best = int(np.argmax(dice))
        details[name] = dict(intersections=values, dice=dice, admissibles=feasible,
                             meilleur_q=best, meilleur_dice=dice[best])
        edges[name] = feasible
        costs[name] = [1-min(1.,score/cap['dice_max']) for score in dice]
    matched = assignment(edges,costs)
    for name in names:
        details[name]['q_attribue'] = matched.get(name)
    return details


def partition(row, output):
    q = output['q']
    own = row['intersections'][q]
    other = output['aire_evaluable'] - output['fond'] - own
    result = dict(identite=own, autres_identites=other, fond_certifie=output['fond'], ignores=output['ignore'])
    assert min(result.values())>=0 and sum(result.values())==output['aire']
    return result


def instance(name, src, learned, full, bound, archive, gt):
    threshold = learned['seuil_dice']
    assert threshold == full['seuil_dice']
    pairs = []
    for q,box in enumerate(archive['boites']):
        box_iou = best_box(gt['boite_xyxy'],box[None])['iou']
        train_dice, box_dice = learned['dice'][q], full['dice'][q]
        upper = bound['dice'][q]
        assert max(train_dice,box_dice)<=upper and train_dice>=0 and box_dice>=0
        pairs.append(dict(q=q, iou_boite=box_iou, dice_appris=train_dice, dice_plein=box_dice,
            delta_dice_appris_moins_plein=train_dice-box_dice, marge_appris=train_dice-threshold,
            marge_plein=box_dice-threshold, appris_admis=q in learned['admissibles'],
            plein_admis=q in full['admissibles'], appris_separe=q in learned['separes'],
            plein_separe=q in full['separes'], intersection_omega=bound['intersections'][q],
            dice_oracle=upper, marge_oracle=upper-threshold, oracle_suffisant=q in bound['admissibles']))
    def describe(q):
        p=pairs[q]
        return dict(q=q,boite=archive['boites'][q].tolist(),score=float(archive['scores'][q]),
                    iou_boite=p['iou_boite'],dice=p['dice_appris'],marge=p['marge_appris'])
    rpn = learned['rpn']['q']
    info = dict(identite=name, provenance=src, indice=gt['indice'], boite_reference=gt['boite_xyxy'],
        positifs=learned['vrais'], seuil_dice=threshold, entree_valide=gt['entree_valide'],
        meilleur_RPN=dict(q=rpn,iou=learned['rpn']['iou'],boite=archive['rpn_boites'][rpn].tolist(),
                          logit=float(archive['rpn_logits'][rpn]),ancre=int(archive['rpn_ancres'][rpn])),
        meilleure_boite=describe(learned['boite']['q']),meilleur_dice=describe(learned['meilleur_q']),
        meme_q_meilleurs=learned['boite']['q']==learned['meilleur_q'])
    for key, row in [('appris',learned),('plein',full)]:
        info[key] = dict(admissibles=row['admissibles'],separes=row['separes'],
                         q_admis=row['q_admis'],q_separe=row['q_separe'],q_dice_total=row['q_dice_total'],
                         dice_attribue=row['dice_attribue'],meilleur_q=row['meilleur_q'],meilleur_dice=row['meilleur_dice'])
    info['oracle'] = dict(admissibles=bound['admissibles'],q_attribue=bound['q_attribue'],
                          meilleur_q=bound['meilleur_q'],meilleur_dice=bound['meilleur_dice'])
    return info, pairs


def compare(spec, learned, full, arrays, capacities):
    names=list(learned['identites'])
    assert names==list(full['identites'])
    truth={n:full['identites'][n]['intersections'] for n in names}
    bounds=oracle(truth,names,capacities)
    outputs=[]
    for original,control in zip(learned['sorties'],full['sorties']):
        shared=('q','q_RPN','score','boite')
        assert all(original[k]==control[k] for k in shared)
        r=original['q_RPN']
        outputs.append(dict(**{k:original[k] for k in shared},boite_RPN=arrays['rpn_boites'][r].tolist(),
            logit_RPN=float(arrays['rpn_logits'][r]),ancre_RPN=int(arrays['rpn_ancres'][r]),
            appris={k:v for k,v in original.items() if k not in shared},
            plein={k:v for k,v in control.items() if k not in shared}))
    ids={}
    for name in names:
        learn_id=learned['identites'][name]
        full_id=full['identites'][name]
        state, pairs=instance(name,spec['provenance'],learn_id,full_id,bounds[name],arrays,spec['instances'][name])
        for arm,row in [('appris',learn_id),('plein',full_id)]:
            covered=row['couverture_25']
            state[arm].update(couverture_25=covered, dedoublee=len(covered)>=2,
                              q_en_fusion=[q for q in covered if len(outputs[q][arm]['identites_25'])>=2])
        for p in pairs:
            q=p['q']
            for arm,row,output in [('appris',learn_id,learned['sorties'][q]),('plein',full_id,full['sorties'][q])]:
                pixels=partition(row,output)
                p['pixels_'+arm]=pixels
                p['fraction_ignoree_'+arm]=pixels['ignores']/output['aire'] if output['aire'] else None
            assert p['pixels_plein']['identite']==p['intersection_omega']
        ids[name]=dict(**state,couples=pairs)
    return dict(provenance=spec['provenance'],supports=spec['supports'],sorties=outputs,identites=ids,
                effectifs_appris=learned['effectifs'],effectifs_plein=full['effectifs'])


def flags(state):
    learned=state['appris']
    return dict(RPN=state['meilleur_RPN']['iou']>=.5,boite=state['meilleure_boite']['iou_boite']>=.5,
                admissible=bool(learned['admissibles']),separable=bool(learned['separes']),
                attribue=learned['q_separe'] is not None)


def transition(before, after):
    a,b=flags(before),flags(after)
    if a['attribue']==b['attribue']:
        kind='stable'
    elif a['boite']!=b['boite']:
        kind='bascule_boite'
    elif a['admissible']!=b['admissible']:
        kind='seuil_masque_boite_couvrante' if a['boite'] else 'seuil_masque_sous_IoU'
    elif a['separable']!=b['separable']:
        kind='separation_locale'
    elif a['separable'] and b['separable']:
        kind='appariement_seul'
    else:
        kind='autre'
    direction=('gain' if b['attribue'] else 'perte') if a['attribue']!=b['attribue'] else 'stable'
    return dict(sens=direction,categorie=kind,avant=a,apres=b)
