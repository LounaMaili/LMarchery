"""Dice natif par masque après l'affectation hongroise historique."""
import torch

from r21.regle import losses as historical


def losses(fields, presence, target, coefficient=1.):
    total, parts, audit, assignment = historical(fields, presence, target, exhaustive=False)
    assigned = assignment['par_identite']
    slots = [r['q'] for r in assigned]
    own = target['own']
    known = own.sum(dim=1) > 0
    native = own + target['others'] + target['background']
    probabilities = fields[slots,0].sigmoid()
    numerator = 2 * (probabilities * own).sum(dim=1) + 1
    denominator = own.sum(dim=1) + (probabilities * native).sum(dim=1) + 1
    scores = numerator / denominator
    dice_loss = (1 - scores[known]).mean() if known.any() else fields[:,0].sum() * 0
    for row, admitted, score in zip(assigned, known.tolist(), scores.detach().tolist(), strict=True):
        row['dice_doux'] = score if admitted else None
    parts['dice'] = dice_loss
    assignment['pertes']['dice'] = dice_loss.item()
    if coefficient:
        total = total + coefficient * dice_loss
    return total, parts, audit, assignment
