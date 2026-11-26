"""Supervision partielle sur les centres natifs et identité physique R18."""
import numpy as np
import torch
from torch.nn import functional as F
from torchvision.ops import box_iou


def integral(mask):
    table = np.zeros((mask.shape[0] + 1, mask.shape[1] + 1), dtype=np.int32)
    table[1:, 1:] = mask.cumsum(0, dtype=np.int32).cumsum(1, dtype=np.int32)
    return table


def rectangle(table, x0, y0, x1, y1):
    return table[y1, x1] - table[y0, x1] - table[y1, x0] + table[y0, x0]


class NativeTargets:
    def __init__(self, mask, instances, image_xy):
        self.height, self.width = mask.shape
        self.image_xy = image_xy
        self.scale = np.tile(np.array([self.width, self.height], dtype=np.float64) / image_xy, 2)
        ordered = sorted(instances.items(), key=lambda item: item[1]['indice'])
        self.ids = [name for name, _ in ordered]
        self.labels = [item['indice'] for _, item in ordered]
        assert len(set(self.labels)) == len(self.labels) and not {0, 255}.intersection(self.labels)
        assert set(np.unique(mask)).issubset({0, 255, *self.labels})
        boxes = []
        self.individual = []
        for _, spec in ordered:
            positive = mask == spec['indice']
            yy, xx = np.nonzero(positive)
            box = [xx.min(), yy.min(), xx.max() + 1, yy.max() + 1]
            assert box == spec['boite_xyxy'] and len(xx) == spec['positifs']
            boxes.append(box)
            self.individual.append(integral(positive))
        self.boxes = torch.tensor(np.asarray(boxes).reshape(-1, 4) / self.scale, dtype=torch.float32)
        self.known = integral(mask != 255)
        self.background = integral(mask == 0)
        self.xcentres = np.arange(self.width, dtype=np.float64) + .5
        self.ycentres = np.arange(self.height, dtype=np.float64) + .5

    def fully_background(self, boxes):
        native = boxes.detach().numpy().astype(np.float64) * self.scale
        inside = ((native[:, :2] >= 0).all(1) &
                  (native[:, 2:] <= [self.width, self.height]).all(1))
        corners = np.clip(np.ceil(native - .5), 0, [self.width, self.height] * 2).astype(np.intp)
        x0, y0, x1, y1 = corners.T
        area = (x1 - x0) * (y1 - y0)
        return torch.from_numpy(inside & (x1 > x0) & (y1 > y0) &
                                (rectangle(self.background, x0, y0, x1, y1) == area))

    @staticmethod
    def boundaries(centres, left, right):
        # searchsorted sur la formule publiée évite les erreurs de côté aux frontières flottantes.
        bins = np.floor(28 * (centres - left) / (right - left))
        return np.searchsorted(bins, np.arange(29))

    def cells(self, box, index):
        x0, y0, x1, y1 = box.detach().numpy().astype(np.float64) * self.scale
        assert x1 > x0 and y1 > y0
        xs = self.boundaries(self.xcentres, x0, x1)
        ys = self.boundaries(self.ycentres, y0, y1)
        bounds = (xs[:-1], ys[:-1, None], xs[1:], ys[1:, None])
        p = rectangle(self.individual[index], *bounds)
        known = rectangle(self.known, *bounds)
        return p, known - p


def label(boxes, target, rpn):
    """Valeurs 1, 0, -1 avant quotas. Propriété des égalités par indice R18 croissant."""
    labels = torch.full((len(boxes),), -1, dtype=torch.int8)
    owners = torch.zeros(len(boxes), dtype=torch.long)
    if len(boxes) == 0:
        return labels, owners
    if len(target.ids):
        overlaps = box_iou(boxes, target.boxes)
        high, owners = overlaps.max(dim=1)
        if rpn:
            centres = (boxes[:, :2] + boxes[:, 2:]) / 2
            inside = ((centres >= 0) & (centres < torch.tensor(target.image_xy))).all(1)
            best = overlaps.masked_fill(~inside[:, None], -1).max(dim=0).values
            rescue = ((overlaps == best) & (best > 0)).any(dim=1)
            positive = inside & ((high >= .7) | rescue)
        else:
            positive = high >= .5
    else:
        high = torch.zeros(len(boxes))
        positive = torch.zeros(len(boxes), dtype=torch.bool)
    negative = (high < (.3 if rpn else .5)) & target.fully_background(boxes)
    labels[negative] = 0
    labels[positive] = 1
    return labels, owners


def sample(labels, quota, positive_limit=128):
    available = [(labels == value).nonzero(as_tuple=True)[0] for value in (1, 0)]
    selected = []
    remaining = quota
    for pool, limit in zip(available, (positive_limit, quota)):
        count = min(len(pool), limit, remaining)
        selected.append(pool[torch.randperm(len(pool))[:count]])
        remaining -= count
    pos, neg = selected
    record = dict(disponibles_pos=len(available[0]), disponibles_neg=len(available[1]),
                  ignores=int((labels == -1).sum()), utilises_pos=pos.tolist(), utilises_neg=neg.tolist(),
                  hors_quota=sum(map(len, available)) - len(pos) - len(neg))
    return pos, neg, record


def region_losses(boxes, scores, deltas, positive, negative, owners, target, transform, rpn):
    indices = torch.cat((positive, negative))
    n = max(1, len(indices))
    foreground = torch.arange(len(indices)) < len(positive)
    if rpn:
        classification = F.binary_cross_entropy_with_logits(scores[indices], foreground.float(), reduction='sum')
    else:
        classification = F.cross_entropy(scores[indices], (~foreground).long(), reduction='sum')
    regression = deltas[positive].sum() * 0
    if len(positive):
        desired = transform.get_deltas(boxes[positive], target.boxes[owners[positive]])
        regression = F.l1_loss(deltas[positive], desired, reduction='sum')
    return classification / n, regression / n


def mask_loss(logits, boxes, owners, target):
    totals, masses = {}, []
    for z, box, owner in zip(logits, boxes, owners.tolist()):
        p, n = target.cells(box, owner)
        count_p, count_n = int(p.sum()), int(n.sum())
        masses.append([target.ids[owner], count_p, count_n])
        if count_p:
            pt, nt = torch.from_numpy(p), torch.from_numpy(n)
            term = (pt * F.softplus(-z) + nt * F.softplus(z)).sum() / (count_p + count_n)
            totals.setdefault(owner, []).append(term)
    if not totals:
        return logits.sum() * 0, masses
    return torch.stack([torch.stack(values).mean() for values in totals.values()]).mean(), masses
