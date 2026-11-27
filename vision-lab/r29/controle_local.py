"""Vérifications synthétiques de la sélection et du collage, sans passage sur photo."""
import unittest

import numpy as np
import torch
from detectron2.layers.mask_ops import paste_masks_in_image

from r27.modele import suppress
from r29.selection import explain
from r29.supports import tile


class OperateursR29(unittest.TestCase):
    def test_nms_egalites_validite_et_supprimeur_garde(self):
        boxes=torch.tensor([[-2.,0.,4.,4.],[0.,0.,4.,4.],[0.,0.,2.,4.],
                            [6.,0.,6.,2.],[4.,0.,7.,2.],[8.,0.,11.,2.]])
        scores=torch.tensor([.8,.8,.7,.99,0.,.1])
        observed,confidence,idx=suppress(boxes,scores,[10,10],.5,positive=True)
        rec=explain(boxes.numpy(),scores.numpy(),[10,10],idx.numpy())
        self.assertEqual(idx.tolist(),[0,2,5])
        self.assertEqual(rec['supprime_par'].tolist(),[-1,0,-1,-1,-1,-1])
        self.assertEqual(rec['iou_suppression'][1],1.)
        self.assertFalse(rec['boite_valide'][3])
        self.assertFalse(rec['score_admis'][4])
        np.testing.assert_array_equal(observed.numpy(),rec['borne'][idx.numpy()])
        np.testing.assert_array_equal(confidence.numpy(),scores.numpy()[idx.numpy()])

    def test_support_grille_pleine_sous_fenetre_native(self):
        data=[[-3.2,3.5,79.4,81.3],[8.2,9.2,108.8,109.8],[19.8,13.1,20.1,34.8],[15.5,7.5,37.5,71.5]]
        for box in np.array(data,np.float32):
            hw=(101,121)
            expected=paste_masks_in_image(torch.ones((1,28,28)),torch.from_numpy(box[None]),hw,.5)[0].numpy()
            mask,region=tile(box,hw)
            result=np.zeros(hw,bool)
            result[region]=mask
            np.testing.assert_array_equal(expected,result)


if __name__=='__main__':
    unittest.main()
