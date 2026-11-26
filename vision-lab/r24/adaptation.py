"""Périmètre exact des groupes AdamW et mesures sans effet sur leurs gradients."""
from math import sqrt
import torch
from torch import nn

from r21.plan import tensor_state

SHAPES = {
    'features.8.block.0.0.weight': (144, 48, 1, 1),
    'features.8.block.1.0.weight': (144, 1, 5, 5),
    'features.8.block.2.fc1.weight': (40, 144, 1, 1),
    'features.8.block.2.fc1.bias': (40,),
    'features.8.block.2.fc2.weight': (144, 40, 1, 1),
    'features.8.block.2.fc2.bias': (144,),
    'features.8.block.3.0.weight': (48, 144, 1, 1),
}
RATES = {'tete': .001, 'bloc8': .0001}


def configure(model):
    head = {n:p for n,p in model.named_parameters() if p.requires_grad}
    if any(n.startswith(('features.', 'projections.')) for n in head) or sum(p.numel() for p in head.values()) != 13828:
        raise ValueError('Tête historique différente')
    block = {}
    for path, module in model.features[8].named_modules():
        if isinstance(module, nn.Conv2d):
            for name, p in module.named_parameters(recurse=False):
                block[f'features.8.{path}.{name}'] = p
    if {n:tuple(p.shape) for n,p in block.items()} != SHAPES:
        raise ValueError('Convolutions différentes de la liste publiée')
    for p in block.values():
        p.requires_grad_(True)
    return {'tete': head, 'bloc8': block}


def optimizer_for(groups, spec):
    return torch.optim.AdamW([dict(params=list(ps.values()), lr=RATES[k]) for k,ps in groups.items()],
                              betas=tuple(spec['betas']), eps=spec['eps'],
                              weight_decay=spec['weight_decay'], foreach=spec['foreach'])


def frozen_tensors(model, groups):
    active = {n for group in groups.values() for n in group}
    if {n for n,p in model.named_parameters() if p.requires_grad} != active:
        raise ValueError('Paramètre entraînable hors du périmètre')
    return {n:t for n,t in model.state_dict().items() if n not in active}


def clone(tensors):
    return {n:t.detach().clone() for n,t in tensors.items()}


def check_frozen(model, initial, current):
    if any(m.training for part in (model.features, model.projections) for m in part.modules()):
        raise ValueError('Tronc ou projection hors mode évaluation')
    for n,t in current.items():
        if not torch.equal(t.reshape(-1).view(torch.uint8), initial[n].reshape(-1).view(torch.uint8)):
            raise ValueError('Tenseur gelé modifié '+n)


def group_signature(groups):
    return {k:tensor_state(ps)['sha256'] for k,ps in groups.items()}


@torch.no_grad()
def gradients(group):
    if any(p.grad is None for p in group.values()):
        raise ValueError('Gradient manquant dans un groupe entraînable')
    return sqrt(sum(float(p.grad.double().square().sum()) for p in group.values()))


@torch.no_grad()
def displacement(group, initial):
    delta = [(p.double()-initial[n].double()) for n,p in group.items()]
    return dict(l2=sqrt(sum(float(d.square().sum()) for d in delta)),
                max_abs=max(float(d.abs().max()) for d in delta))
