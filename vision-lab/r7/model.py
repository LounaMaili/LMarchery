"""Paired MobileNet encoders for offline reading of a scored target photograph."""
import cv2 as cv
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torchvision.models import mobilenet_v3_small

from detector import Tube
from lab_data import fingerprint
from r3.windows import offsets

IMAGENET_SHA = "047dcff4addef86ea5bc2eff13c9614dc11f47ab1160d0a71a25e7db994f4e1f"
TILE = 320


class ContactNet(nn.Module):
    def __init__(self, pretrained=None):
        super().__init__()
        base = mobilenet_v3_small(weights=None)
        if pretrained:
            if fingerprint(pretrained) != IMAGENET_SHA:
                raise ValueError("Unexpected ImageNet checkpoint")
            base.load_state_dict(torch.load(pretrained, map_location="cpu", weights_only=True))
        self.features = base.features[:9]
        self.projections = nn.ModuleList([nn.Conv2d(ch, 8, 1) for ch in (16, 16, 24, 48)])
        self.head = nn.Sequential(nn.Conv2d(32, 16, 3, padding=1), nn.BatchNorm2d(16),
                                  nn.ReLU(), nn.Conv2d(16, 2, 1))
        self.register_buffer("mean",torch.tensor([.485,.456,.406]).view(1,3,1,1))
        self.register_buffer("std",torch.tensor([.229,.224,.225]).view(1,3,1,1))

    def forward(self, rgb):
        x = (rgb / 255. - self.mean) / self.std
        pyramid = []
        for i, block in enumerate(self.features):
            x = block(x)
            if i in (0,1,3,8):
                pyramid.append(x)
        feats = [F.interpolate(layer(feat), size=pyramid[0].shape[-2:],
                               mode="bilinear", align_corners=False)
                 for layer, feat in zip(self.projections, pyramid)]
        return F.interpolate(self.head(torch.cat(feats,1)), size=rgb.shape[-2:],
                             mode="bilinear", align_corners=False)


def tensor(images):
    return torch.from_numpy(np.stack([im[:,:,::-1].transpose(2,0,1) for im in images])).contiguous(
        memory_format=torch.channels_last)


@torch.inference_mode()
def full_maps(model, frame):
    h,w = frame.shape[:2]
    padded = cv.copyMakeBorder(frame,0,max(0,TILE-h),0,max(0,TILE-w),cv.BORDER_REPLICATE)
    ph,pw = padded.shape[:2]
    tiles = [(x,y) for y in offsets(ph,TILE,64) for x in offsets(pw,TILE,64)]
    blend = np.maximum(.12,np.outer(np.hanning(TILE),np.hanning(TILE))).astype(np.float32)
    sums, weights = np.zeros((2,ph,pw),np.float32), np.zeros((ph,pw),np.float32)
    for start in range(0,len(tiles),8):
        batch = tiles[start:start+8]
        maps = model(tensor([padded[y:y+TILE,x:x+TILE] for x,y in batch])).sigmoid().numpy()
        for (x,y), patch in zip(batch,maps,strict=True):
            sums[:,y:y+TILE,x:x+TILE] += patch*blend
            weights[y:y+TILE,x:x+TILE] += blend
    return (sums/weights)[:,:h,:w]


def decode(maps):
    heat,shaft = maps
    local = (heat == cv.dilate(heat,np.ones((9,9),np.uint8))) & (heat >= .05)
    n, labels, stats, centers = cv.connectedComponentsWithStats(local.astype(np.uint8),8)
    peaks = []
    for i in range(1,n):
        x,y,w,h,_ = stats[i]
        ry,rx = np.where(labels[y:y+h,x:x+w]==i)
        k = np.argmax(heat[y+ry,x+rx])
        px,py = int(x+rx[k]),int(y+ry[k])
        peaks.append(([px,py], float(heat[py,px])))
    peaks.sort(key=lambda p:(-p[1],p[0]))
    # Separate presence measure from contact proposals. Use only visible elongated components.
    count, label, info, centers = cv.connectedComponentsWithStats((shaft >= .5).astype(np.uint8),8)
    tubes = []
    for i in np.argsort(-info[1:,cv.CC_STAT_AREA])[:48]+1:
        x,y,w,h,area = info[i]
        if area < 28:
            continue
        yy,xx = np.where(label[y:y+h,x:x+w] == i)
        points = np.column_stack((xx+x,yy+y))
        center = points.mean(0)
        var, axes = np.linalg.eigh(np.cov(points.T))
        along = (points-center) @ axes[:,-1]
        ends = center + np.quantile(along,[.01,.99])[:,None]*axes[:,-1]
        if var[1] >= 4*max(1,var[0]) and np.linalg.norm(ends[1]-ends[0]) >= 25:
            tubes.append(Tube(ends,np.empty((0,0))))
    return peaks[:96], tubes
