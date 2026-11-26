"""Contrôles R27 sur données synthétiques dans l'environnement exact de l'essai."""
import inspect
import math
import pickle
import argparse
from pathlib import Path
import unittest

import numpy as np
import torch
from detectron2.layers.mask_ops import paste_masks_in_image
from detectron2.modeling.box_regression import Box2BoxTransform
from detectron2.structures import Boxes

from r27.essai import audited_clip, configure, optimizer, seed_rng, update, write
from r27.modele import WEIGHTS, autonomous, fingerprint, frozen, group, initialize, suppress
from r27.supervision import NativeTargets, label, mask_loss, region_losses, sample

EVIDENCE = {}


def target(mask, xy=None):
    specs = {}
    for i in sorted(set(np.unique(mask)) - {0, 255}, reverse=True):
        yy, xx = np.nonzero(mask == i)
        specs['id-' + str(i)] = dict(indice=int(i), positifs=len(xx),
                                    boite_xyxy=[xx.min(), yy.min(), xx.max()+1, yy.max()+1])
    return NativeTargets(mask, specs, xy or list(mask.shape[1::-1]))


def synthetic():
    mask = np.zeros((111, 128), np.uint8)
    mask[5:35, 18:108] = 255
    mask[38:46, 16:103] = 3
    mask[38:46, 60:64] = 255
    mask[49:55, 21:110] = 8
    image = np.random.default_rng(17).integers(0, 255, (111,128,3), dtype=np.uint8)
    image[mask==3] = [223,131,82]
    image[mask==8] = [56,71,232]
    return image, target(mask)


class Geometry(unittest.TestCase):
    def test_global_clip_across_long_vectors(self):
        p = torch.nn.Parameter(torch.ones(4096,1024))
        p.grad = torch.full_like(p,.003)
        initial, factor, measured = audited_clip([p])
        self.assertGreater(measured,5.00002)
        self.assertEqual(float(p.grad.min()),float(p.grad.max()))
        # La seconde réduction FP32 peut excéder 5 en présence du multiplicateur exact.
        EVIDENCE['controle_reduction_FP32'] = dict(elements=p.numel(),avant=initial,coefficient=factor,
            apres_reduction_FP32=measured,apres_reduction_FP64=float(p.grad.double().norm()))

    def test_cells_oracle_thin_occluded_boundary_and_padding(self):
        mask = np.full((70, 85), 255, np.uint8)
        mask[10:28, 9:37] = 0
        mask[15, 3:72] = 2
        mask[13:52, 46] = 6
        np.fill_diagonal(mask[21:58, 11:48], 9)
        mask[15, 22:26] = 255
        t = target(mask, [43, 39])
        for b in ([0.,0.,43.,39.], [5.25,2.875,41.,38.5], [-.25,7.,46.,45.], [8.,8.,22.,9.]):
            box = torch.tensor(b)
            l, top, r, bottom = box.numpy().astype(float) * t.scale
            for j, idx in enumerate(t.labels):
                pos, neg = t.cells(box, j)
                oracle = np.zeros((2,28,28), int)
                for (y,x), value in np.ndenumerate(mask):
                    if l<=x+.5<r and top<=y+.5<bottom and value!=255:
                        col = math.floor(28*(x+.5-l)/(r-l))
                        row = math.floor(28*(y+.5-top)/(bottom-top))
                        oracle[int(value!=idx),row,col] += 1
                np.testing.assert_array_equal([pos,neg], oracle)
        self.assertEqual(t.ids, ['id-2','id-6','id-9'])
        # Même boîte physique, deux identités différentes, égalité attribuée au plus petit indice.
        small = np.array([[1, 2], [2, 1]], np.uint8)
        same = target(small)
        self.assertEqual(label(same.boxes[:1], same, False)[1].tolist(), [0])

    def test_partial_labels_rescue_zero_IoU_whole_rectangle_and_quota(self):
        mask = np.zeros((40,40), np.uint8)
        mask[15:18, 10:28] = 4
        mask[3,3] = 255
        t = target(mask)
        boxes = torch.tensor([[10,14,28,20], [12,13,22,21], [24,24,31,31], [0,0,6,6],
                              [-1,24,3,28], [29.6,29.6,30.3,30.3], [34,14,53,22]], dtype=torch.float32)
        self.assertEqual(label(boxes,t,True)[0].tolist(), [1,-1,0,-1,-1,-1,-1])
        self.assertEqual(label(boxes,t,False)[0].tolist(), [1,-1,0,-1,-1,-1,-1])
        far = boxes[[2,3,4,5]]
        self.assertEqual(label(far,t,True)[0].tolist(), [0,-1,-1,-1])
        self.assertEqual(label(boxes[1:2],t,True)[0].tolist(), [1])
        self.assertEqual(label(boxes[1:2],t,False)[0].tolist(), [-1])
        labs = torch.tensor([1]*300+[0]*700+[-1]*10)
        for quota in (256,512):
            seed_rng(19)
            a,b,record = sample(labs,quota)
            seed_rng(19)
            c,d,_ = sample(labs,quota)
            self.assertTrue(torch.equal(a,c) and torch.equal(b,d))
            self.assertEqual((len(a.unique()),len(b.unique()),record['ignores']), (128,quota-128,10))
        a,b,_ = sample(torch.tensor([1,1,0,-1,-1]),256)
        self.assertEqual((len(a),len(b)),(2,1))

    def test_losses_actual_counts_and_unknown_weight(self):
        mask = np.full((56,56),255,np.uint8)
        mask[0,0:2] = [1,2]
        mask[1,0] = 0
        t = target(mask)
        b = torch.tensor([[0.,0.,56.,56.], [0.,0.,28.,28.], [0.,0.,56.,56.]])
        z = torch.zeros(3,28,28,requires_grad=True)
        value,masses = mask_loss(z,b,torch.tensor([0,0,1]),t)
        self.assertEqual(masses,[['id-1',1,2],['id-1',1,2],['id-2',1,2]])
        self.assertAlmostEqual(float(value),math.log(2),6)
        value.backward()
        self.assertAlmostEqual(z.grad[0,0,0].item(),1/24,6)
        self.assertEqual(z.grad[0,1:,1:].abs().sum().item(), 0)
        no = torch.ones(1,28,28,requires_grad=True)
        ignored,_ = mask_loss(no,torch.tensor([[13.,13.,25.,25.]]),torch.tensor([0]),t)
        ignored.backward()
        self.assertEqual((float(ignored),float(no.grad.abs().sum())),(0.,0.))
        src = t.boxes[[0,1]].clone()
        for rpn in (True,False):
            scores = torch.zeros(2) if rpn else torch.zeros(2,2)
            cls,reg = region_losses(src, scores, torch.ones(2,4), torch.tensor([0]),torch.tensor([1]),
                                    torch.tensor([0,1]),t,Box2BoxTransform((1.,1.,1.,1.)),rpn)
            self.assertAlmostEqual(float(cls),math.log(2),6)
            self.assertEqual(float(reg),2.)
            empty = torch.empty(0,dtype=torch.long)
            a,beta = region_losses(src,scores,torch.ones(2,4),empty,empty,torch.tensor([0,1]),t,
                                   Box2BoxTransform((1.,1.,1.,1.)),rpn)
            self.assertEqual((float(a),float(beta)), (0.,0.))

    def test_native_paste_threshold_half_and_subcell_loss(self):
        mask = np.zeros((112,112),np.uint8)
        mask[57,:] = 1
        t = target(mask)
        box = torch.tensor([[0.,0.,112.,112.]])
        p,n = t.cells(box[0],0)
        self.assertEqual(int(p.sum()),112)
        prob = torch.from_numpy((p/(p+n)).astype(np.float32))[None]
        out = paste_masks_in_image(prob,Boxes(box),(112,112),threshold=.5)
        self.assertEqual(out.sum().item(),0)
        square = torch.full((1,28,28),.75)
        inner = paste_masks_in_image(square,Boxes(torch.tensor([[2.,3.,30.,31.]])),(33,34),threshold=.5)
        self.assertEqual(inner.sum().item(),28*28)
        self.assertTrue(inner[0,3:31,2:30].all())
        # À .5 pile, l'intérieur constant atteint le seuil inclusif. Aux bords,
        # l'arrondi du repère float32 peut interpoler un poids infinitésimal de zéro.
        crop = Boxes(torch.tensor([[2.,3.,30.,31.]]))
        exact = paste_masks_in_image(square.fill_(.5),crop,(33,34),threshold=.5)
        below = paste_masks_in_image(square.fill_(.499),crop,(33,34),threshold=.5)
        self.assertTrue(exact[0,5:29,4:28].all())
        self.assertEqual(below.sum().item(),0)
        EVIDENCE['exemple_sous_cellulaire'] = dict(positifs_comptes=112,pic_occupation=float(prob.max()),
                                                   pixels_colles=int(out.sum()))

    def test_stable_nms_empty_before_limit(self):
        b = torch.tensor([[0,0,0,2],[2,0,4,2],[2,0,4,2],[0,3,2,5],[8,8,10,10]],dtype=torch.float32)
        _, scores, ids = suppress(b,torch.tensor([.99,.8,.8,.8,.7]),[8,8],.5,positive=True)
        self.assertEqual(ids.tolist(),[1,3])
        self.assertEqual(len(scores),2)
        _,_,ids = suppress(b,torch.zeros(5),[8,8],.5,positive=True)
        self.assertEqual(len(ids),0)


class FullChain(unittest.TestCase):
    def test_complete_weights_three_seeds_and_synthetic_update(self):
        image,t = synthetic()
        initial = []
        with WEIGHTS.open('rb') as stream:
            src = pickle.load(stream,encoding='latin1')['model']
        for seed in (9301,9302,9303):
            seed_rng(seed)
            model, rec = initialize()
            initial.append(fingerprint(list(model.named_parameters())+list(model.named_buffers())))
        self.assertEqual(len(set(initial)),1)
        current = model.state_dict()
        for n,v in src.items():
            if '.cls_score.' in n:
                expected = np.concatenate([v[:80].mean(0,dtype=np.float64,keepdims=True).astype('float32'),v[80:]])
            elif '.bbox_pred.' in n:
                expected = v.reshape(80,4,*v.shape[1:]).mean(0,dtype=np.float64).astype('float32')
            elif '.mask_head.predictor.' in n:
                expected = v.mean(0,dtype=np.float64,keepdims=True).astype('float32')
            else:
                continue
            np.testing.assert_array_equal(current[n].numpy(),expected)
        layers = {n.rsplit('.',1)[0] for n,p in model.named_parameters() if p.requires_grad}
        self.assertEqual(layers,{'proposal_generator.rpn_head.'+s for s in ('conv','objectness_logits','anchor_deltas')}
            | {'roi_heads.box_head.fc1','roi_heads.box_head.fc2','roi_heads.box_predictor.cls_score','roi_heads.box_predictor.bbox_pred'}
            | {'roi_heads.mask_head.'+s for s in ('mask_fcn1','mask_fcn2','mask_fcn3','mask_fcn4','deconv','predictor')})
        expected = fingerprint(frozen(model))
        x = torch.from_numpy(image).permute(2,0,1).float()
        padded = model.preprocess_image([{'image':x}]).tensor
        self.assertEqual(tuple(padded.shape),(1,3,128,128))
        self.assertEqual(padded[:,:,111:].abs().sum().item(),0.)
        self.assertTrue(torch.equal(padded[0,:,:111],x-model.pixel_mean))
        # Tester aussi le bourrage de droite.
        p2 = model.preprocess_image([{'image':x[:,:,:117]}]).tensor
        self.assertEqual(p2[:,:,:,117:].abs().sum().item(),0.)
        optim = optimizer(model)
        stats = update(model, optim, image, t, expected)
        for val in list(stats['gradients'].values())+list(stats['deplacement'].values()):
            self.assertGreater(val,0.)
        combined = math.sqrt(sum(v*v for v in stats['gradients'].values()))
        self.assertAlmostEqual(combined/stats['norme_avant'],1.,places=5)
        self.assertGreater(combined,5.)
        self.assertAlmostEqual(stats['norme_apres'],5.,places=4)
        self.assertEqual(list(inspect.signature(autonomous).parameters),['model','bgr','native_xy'])
        model.eval()
        first = autonomous(model,image,[128,111])
        second = autonomous(model,image,[128,111])
        for k in first:
            np.testing.assert_array_equal(first[k],second[k])
        self.assertLessEqual(len(first['boites']),12)
        self.assertTrue(np.all(np.diff(first['scores'])<=0))
        self.assertEqual(first['masques'].shape,(111,128))
        self.assertEqual(fingerprint(frozen(model)),expected)
        EVIDENCE.update(sha256_initiaux=initial,sha256_geles=expected,tenseurs_source=len(src),
            parametres_entrainables=sum(p.numel() for p in model.parameters() if p.requires_grad),
            parametres_geles=sum(p.numel() for p in model.parameters() if not p.requires_grad),
            entrainables=sorted(layers), mesures_synthetiques=stats, sorties_autonomes=len(first['scores']))
        write(DEST/'tenseurs.json',rec)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sortie', type=Path, default=Path('data/r27/verification-synthetique'))
    DEST = parser.parse_args().sortie
    DEST.mkdir(exist_ok=True, parents=True)
    env = configure()
    suite = unittest.defaultTestLoader.loadTestsFromModule(__import__(__name__,fromlist=['']))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful() or result.skipped or result.testsRun != 7:
        raise SystemExit(1)
    write(DEST/'barriere.json',dict(reussite=True,tests=result.testsRun,environnement=env,**EVIDENCE))
