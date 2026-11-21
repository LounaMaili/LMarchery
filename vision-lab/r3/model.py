"""Small convolutional prototype, initialized from scratch for synthetic development."""
import torch
from torch import nn
from torch.nn import functional as F


def block(in_channels, out_channels, stride=1):
    return nn.Sequential(nn.Conv2d(in_channels, out_channels, 3, stride, 1, bias=False),
                         nn.BatchNorm2d(out_channels), nn.ReLU(inplace=True))


class EntryNet(nn.Module):
    """Return shaft, entry heatmap and contact visibility logits in image order."""

    def __init__(self):
        super().__init__()
        self.local = nn.Sequential(block(3, 16, 2), block(16, 16))
        self.middle = nn.Sequential(block(16, 24, 2), block(24, 24))
        self.context = nn.Sequential(block(24, 40, 2), block(40, 40), block(40, 40))
        self.merge = nn.Sequential(block(80, 24), block(24, 16))
        self.output = nn.Conv2d(16, 3, 1)

    def forward(self, frame):
        local = self.local(frame)
        middle = self.middle(local)
        context = self.context(middle)
        detail = local.shape[-2:]
        maps = torch.cat((local, F.interpolate(middle, detail, mode="nearest"),
                          F.interpolate(context, detail, mode="nearest")), dim=1)
        return F.interpolate(self.output(self.merge(maps)), frame.shape[-2:], mode="nearest")


def loss(logits, target):
    shaft, heat, visible, supervised = target.unbind(dim=1)
    mask_logit, entry_logit, vis_logit = logits.unbind(dim=1)
    tube_loss = F.binary_cross_entropy_with_logits(mask_logit, shaft, pos_weight=logits.new_tensor(10.))
    heat_loss = F.binary_cross_entropy_with_logits(entry_logit, heat, reduction="none")
    probability = entry_logit.sigmoid()
    # Continuous targets around a contact. Empty and occulted patches still penalize spurious peaks.
    heat_weight = heat * 44 * (1-probability).square() + (1-heat) * probability.square()
    entry_loss = (heat_loss * heat_weight).mean()
    vis_loss = F.binary_cross_entropy_with_logits(vis_logit, visible, reduction="none")
    visibility_loss = (vis_loss * supervised).sum() / supervised.sum().clamp_min(1)
    return tube_loss + 3*entry_loss + .5*visibility_loss
