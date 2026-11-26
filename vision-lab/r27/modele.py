"""Mask R-CNN v0.6. L'entrée autonome ne reçoit que le BGR et les dimensions natives."""
import hashlib
import pickle
from pathlib import Path

import numpy as np
import torch
from detectron2 import model_zoo
from detectron2.config import get_cfg
from detectron2.layers.mask_ops import paste_masks_in_image
from detectron2.modeling import build_model
from detectron2.structures import Boxes
from torchvision.ops import nms

from r27.supervision import label, sample, region_losses, mask_loss

WEIGHTS = Path('data/r27/model_final_f10217.pkl')
EXPECTED = '9a737e290372f1f70994ebcbd89d8004dbb3ae30a605fd915a190fa4a782dd66'
RECIPE = 'COCO-InstanceSegmentation/mask_rcnn_R_50_FPN_3x.yaml'


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(4 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def tensor_hash(tensor):
    return hashlib.sha256(tensor.detach().contiguous().numpy()).hexdigest()


def fingerprint(named):
    digest = hashlib.sha256()
    for name, value in sorted(named):
        digest.update(name.encode() + b'\0')
        digest.update(str(tuple(value.shape)).encode() + b'\0')
        digest.update(value.detach().contiguous().numpy())
    return digest.hexdigest()


def frozen(model):
    return [(k, v) for k, v in model.named_parameters() if not v.requires_grad] + list(model.named_buffers())


def group(name):
    if name.startswith('proposal_generator.rpn_head.'):
        return 'rpn'
    if name.startswith(('roi_heads.box_head.', 'roi_heads.box_predictor.')):
        return 'boite'
    if name.startswith('roi_heads.mask_head.'):
        return 'masque'
    if not name.startswith('backbone.'):
        raise ValueError(name)
    return 'gele'


def architecture(classes):
    cfg = get_cfg()
    cfg.merge_from_file(model_zoo.get_config_file(RECIPE))
    cfg.MODEL.DEVICE = 'cpu'
    cfg.MODEL.ROI_HEADS.NUM_CLASSES = classes
    cfg.MODEL.RPN.POST_NMS_TOPK_TRAIN = 1000
    cfg.MODEL.ROI_HEADS.SCORE_THRESH_TEST = 0.
    cfg.TEST.DETECTIONS_PER_IMAGE = 12
    model = build_model(cfg)
    assert model.proposal_generator.box2box_transform.weights == (1., 1., 1., 1.)
    assert model.roi_heads.box_predictor.box2box_transform.weights == (10., 10., 5., 5.)
    return model


def collapse(name, tensor):
    if name.startswith('roi_heads.box_predictor.cls_score.'):
        assert tensor.shape[0] == 81
        return torch.cat((tensor[:80].double().mean(0, keepdim=True).float(), tensor[80:]))
    if name.startswith('roi_heads.box_predictor.bbox_pred.'):
        assert tensor.shape[0] == 320
        return tensor.reshape(80, 4, *tensor.shape[1:]).double().mean(0).float()
    if name.startswith('roi_heads.mask_head.predictor.'):
        assert tensor.shape[0] == 80
        return tensor.double().mean(0, keepdim=True).float()
    return tensor


def initialize():
    assert WEIGHTS.stat().st_size == 177841981 and file_hash(WEIGHTS) == EXPECTED
    with WEIGHTS.open('rb') as handle:
        source = {k: torch.from_numpy(v) for k, v in pickle.load(handle, encoding='latin1')['model'].items()}
    coco = architecture(80)
    persistent = set(coco.state_dict())
    generated = {'proposal_generator.anchor_generator.cell_anchors.' + str(i) for i in range(5)}
    assert set(source) == persistent | generated
    for name in generated:
        assert torch.equal(dict(coco.named_buffers())[name], source[name])
    coco.load_state_dict({k: source[k] for k in persistent}, strict=True)
    for name, value in coco.state_dict().items():
        assert torch.equal(source[name], value), name
    del coco
    model = architecture(1)
    assert set(model.state_dict()) == persistent
    adapted = {k: collapse(k, source[k]) for k in persistent}
    model.load_state_dict(adapted, strict=True)
    for name, value in model.state_dict().items():
        assert torch.equal(value, adapted[name]), name
    for name, parameter in model.named_parameters():
        parameter.requires_grad_(group(name) != 'gele')
    model.backbone.eval()
    params = dict(model.named_parameters())
    records = {}
    for name, value in list(model.named_parameters()) + list(model.named_buffers()):
        src = source.get(name)
        records[name] = dict(forme=list(value.shape), sha256=tensor_hash(value),
                             entraine=name in params and value.requires_grad,
                             forme_COCO=list(src.shape) if src is not None else None,
                             sha256_COCO=tensor_hash(src) if src is not None else None)
    return model, records


def descending(scores):
    return torch.from_numpy(np.argsort(-scores.detach().numpy(), kind='stable'))


def suppress(boxes, scores, xy, threshold, pre_limit=None, positive=False):
    """Ordre hérité en cas d'égalité, boîtes valides avant NMS, probabilités originales conservées."""
    if not torch.isfinite(scores).all() or not torch.isfinite(boxes).all():
        raise FloatingPointError('Boîte ou score non fini')
    order = descending(scores)
    if pre_limit is not None:
        order = order[:pre_limit]
    clipped = boxes[order].clone()
    clipped[:, 0::2].clamp_(0, xy[0])
    clipped[:, 1::2].clamp_(0, xy[1])
    valid = (clipped[:, 2:] > clipped[:, :2]).all(1)
    if positive:
        valid &= scores[order] > 0
    order, clipped = order[valid], clipped[valid]
    rank = torch.arange(len(order), 0, -1, dtype=torch.float32)
    keep = nms(clipped, rank, threshold)
    return clipped[keep], scores[order[keep]], order[keep]


def proposals(model, features, xy, training):
    rpn = model.proposal_generator
    levels = [features[name] for name in rpn.in_features]
    anchors = rpn.anchor_generator(levels)
    logits, raw_deltas = rpn.rpn_head(levels)
    scores = [s.permute(0, 2, 3, 1).flatten(1) for s in logits]
    deltas = [t.reshape(1, -1, 4, *t.shape[-2:]).permute(0, 3, 4, 1, 2).reshape(1, -1, 4)
              for t in raw_deltas]
    with torch.no_grad():
        decoded = rpn._decode_proposals(anchors, deltas)
        parts = []
        offset = 0
        for boxes, confidence in zip(decoded, scores):
            b, s, idx = suppress(boxes[0], confidence[0], xy, .7, 2000 if training else 1000)
            parts.append((b, s, idx + offset))
            offset += len(confidence[0])
        boxes, logits_out, anchor_idx = [torch.cat(values) for values in zip(*parts)]
        order = descending(logits_out)[:1000]
    raw = (Boxes.cat(anchors).tensor, torch.cat(scores, 1)[0], torch.cat(deltas, 1)[0])
    return raw, (boxes[order], logits_out[order], anchor_idx[order])


def extract(model, bgr):
    image = torch.from_numpy(bgr).permute(2, 0, 1).float()
    images = model.preprocess_image([{'image': image}])
    with torch.no_grad():
        features = model.backbone(images.tensor)
    return features


def pool(head, features, boxes, mask=False):
    names = head.mask_in_features if mask else head.box_in_features
    align = head.mask_pooler if mask else head.box_pooler
    pooled = align([features[n] for n in names], [Boxes(boxes)])
    if mask:
        return head.mask_head.layers(pooled)[:, 0]
    return head.box_predictor(head.box_head(pooled))


def objective(model, bgr, target):
    features = extract(model, bgr)
    raw, (proposed, _, anchor_idx) = proposals(model, features, target.image_xy, True)
    anchors, scores, deltas = raw
    labels, owners = label(anchors, target, True)
    pos, neg, rpn = sample(labels, 256)
    rpn['identites_pos'] = [target.ids[i] for i in owners[pos].tolist()]
    rc, rr = region_losses(anchors, scores, deltas, pos, neg, owners, target,
                           model.proposal_generator.box2box_transform, True)
    regions = torch.cat((proposed, target.boxes))
    labels, owners = label(regions, target, False)
    pos, neg, roi = sample(labels, 512)
    selected = torch.cat((pos, neg))
    roi['identites_pos'] = [target.ids[i] for i in owners[pos].tolist()]
    roi['propositions_RPN'] = len(proposed)
    roi['origine_ancre_ou_GT'] = torch.cat((anchor_idx, -1 - torch.arange(len(target.ids))))[selected].tolist()
    head = model.roi_heads
    local_pos = torch.arange(len(pos))
    local_neg = torch.arange(len(pos), len(selected))
    cls, delta = pool(head, features, regions[selected])
    bc, br = region_losses(regions[selected], cls, delta, local_pos, local_neg, owners[selected], target,
                           head.box_predictor.box2box_transform, False)
    mask_logits = pool(head, features, regions[pos], mask=True)
    mask, mass = mask_loss(mask_logits, regions[pos], owners[pos], target)
    return dict(rpn_obj=rc, rpn_L1=rr, roi_cls=bc, roi_L1=br, masque=mask), dict(rpn=rpn, roi=roi, masses=mass)


@torch.no_grad()
def autonomous(model, bgr, native_xy):
    xy = bgr.shape[1::-1]
    features = extract(model, bgr)
    _, (proposed, logits, anchor_idx) = proposals(model, features, xy, False)
    head = model.roi_heads
    cls, delta = pool(head, features, proposed)
    boxes = head.box_predictor.box2box_transform.apply_deltas(delta, proposed)
    boxes, score, origin = suppress(boxes, cls.softmax(1)[:, 0], xy, .5, positive=True)
    boxes, score, origin = boxes[:12], score[:12], origin[:12]
    prob = pool(head, features, boxes, mask=True).sigmoid()
    scale = torch.tensor(list(native_xy) * 2) / torch.tensor(list(xy) * 2)
    native = boxes * scale
    pasted = paste_masks_in_image(prob, Boxes(native), tuple(native_xy[::-1]), threshold=.5)
    # Un bit par sortie permet de conserver toutes les intersections entre instances.
    bits = np.zeros(tuple(native_xy[::-1]), dtype=np.uint16)
    for q, mask in enumerate(pasted.numpy()):
        bits |= mask.astype(np.uint16) << q
    return dict(rpn_boites=(proposed * scale).numpy(), rpn_logits=logits.numpy(), rpn_ancres=anchor_idx.numpy(),
                boites=native.numpy(), scores=score.numpy(), propositions=origin.numpy(), probabilites=prob.numpy(),
                masques=bits)
