"""Reconstitution en FP32 de la sélection et du premier témoin de suppression."""
import numpy as np


def ious(box, others):
    inter = np.maximum(0, np.minimum(box[2:], others[:,2:]) - np.maximum(box[:2], others[:,:2])).prod(1)
    own = (box[2:] - box[:2]).prod()
    area = (others[:,2:] - others[:,:2]).prod(1)
    return inter / (own + area - inter)


def explain(raw, scores, xy, observed):
    if raw.dtype != np.float32 or scores.dtype != np.float32 or not np.isfinite(raw).all() or not np.isfinite(scores).all():
        raise ValueError('Sorties RoI non finies ou hors FP32')
    order = np.argsort(-scores, kind='stable')
    clipped = np.clip(raw, np.zeros(4,np.float32), np.array(list(xy)*2,np.float32))
    valid = (clipped[:,2:] > clipped[:,:2]).all(1)
    positive = scores > 0
    admitted = order[(valid & positive)[order]]
    # À chaque survivante, supprimer parmi les rangs encore actifs.
    pending = admitted
    kept = []
    suppressed_by = np.full(len(scores),-1,np.int64)
    overlap = np.zeros(len(scores),np.float32)
    while len(pending):
        first = int(pending[0])
        kept.append(first)
        rest = pending[1:]
        ratios = ious(clipped[first],clipped[rest])
        removed = ratios > np.float32(.5)
        suppressed_by[rest[removed]] = first
        overlap[rest[removed]] = ratios[removed]
        pending = rest[~removed]
    if not np.array_equal(kept,observed):
        raise ValueError('Les survivantes reconstruites diffèrent de torchvision.ops.nms')
    return dict(ordre_score=order, borne=clipped, boite_valide=valid, score_admis=positive,
                admission=admitted, supprime_par=suppressed_by, iou_suppression=overlap)
