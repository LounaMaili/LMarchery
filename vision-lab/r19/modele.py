"""Tête légère par instance sur la pyramide figée R9. Une image entière par appel."""
from math import sqrt

import cv2 as cv
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from r7.model import ContactNet, tensor
from r19.donnees import SLOTS, input_size


class Instances(nn.Module):
    def __init__(self, checkpoint=None):
        super().__init__()
        old = ContactNet()
        if checkpoint is not None:
            old.load_state_dict(torch.load(checkpoint, weights_only=True, map_location='cpu'), strict=True)
        self.features = old.features.requires_grad_(False)
        self.projections = old.projections.requires_grad_(False)
        self.register_buffer('mean', old.mean)
        self.register_buffer('std', old.std)
        self.basis = nn.Sequential(nn.Conv2d(34, 24, 3, padding=1), nn.GroupNorm(4, 24), nn.ReLU(),
                                   nn.Conv2d(24, 24, 1))
        self.queries = nn.Parameter(torch.empty(SLOTS, 24))
        self.key = nn.Linear(24, 24)
        self.value = nn.Linear(24, 24)
        self.update = nn.Sequential(nn.LayerNorm(24), nn.Linear(24, 48), nn.ReLU(), nn.Linear(48, 24))
        self.coefficients = nn.Linear(24, 3*25)
        self.presence = nn.Linear(24, 1)
        for mod in (self.basis, self.key, self.value, self.update, self.coefficients, self.presence):
            for layer in mod.modules():
                if isinstance(layer, (nn.Conv2d, nn.Linear)):
                    nn.init.xavier_uniform_(layer.weight)
                    nn.init.zeros_(layer.bias)
        nn.init.normal_(self.queries, std=.2)
        self.train(False)

    def train(self, mode=True):
        super().train(mode)
        self.features.eval()
        self.projections.eval()
        return self

    def forward(self, rgb):
        if rgb.shape[0] != 1:
            raise ValueError('Le pilote traite une photo entière à la fois')
        x = (rgb/255.-self.mean)/self.std
        pyramid = []
        for i, block in enumerate(self.features):
            x = block(x)
            if i in (0, 1, 3, 8):
                pyramid.append(x)
        h, w = pyramid[0].shape[-2:]
        parts = [F.interpolate(layer(f), (h,w), mode='bilinear', align_corners=False)
                 for layer, f in zip(self.projections, pyramid, strict=True)]
        y, x = torch.meshgrid(2*(torch.arange(h)+.5)/h-1, 2*(torch.arange(w)+.5)/w-1, indexing='ij')
        parts.append(torch.stack((x,y)).to(rgb.device).unsqueeze(0))
        basis = self.basis(torch.cat(parts, dim=1))
        memory = F.adaptive_avg_pool2d(basis, (12,12)).flatten(2)[0].T
        attention = (self.queries @ self.key(memory).T / sqrt(24)).softmax(dim=1)
        query = self.queries + attention @ self.value(memory)
        query = query + self.update(query)
        kernel = self.coefficients(query).reshape(SLOTS,3,25)
        fields = (kernel[:,:,:24] @ basis[0].reshape(24,-1))/sqrt(24) + kernel[:,:,24:]
        return fields, self.presence(query)[:,0]


def photograph(bgr):
    size = input_size(bgr.shape[1::-1])
    return tensor([cv.resize(bgr, size, interpolation=cv.INTER_AREA)])


def parameter_counts(model):
    return {key:sum(p.numel() for p in model.parameters() if key=='total' or p.requires_grad)
            for key in ('total','entrainables')}
