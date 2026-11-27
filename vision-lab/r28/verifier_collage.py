"""Contrôles synthétiques du seul opérateur de collage Detectron2 historique."""
import unittest

import torch
from detectron2.layers.mask_ops import paste_masks_in_image
from detectron2.structures import Boxes


class CollageR28(unittest.TestCase):
    def test_seuil_exact_et_bord_fractionnaire(self):
        box=Boxes(torch.tensor([[8.2,9.2,108.8,109.8]],dtype=torch.float32))
        ones=torch.ones((1,28,28))
        def paste(grid):
            return paste_masks_in_image(grid,box,(120,128),threshold=.5)[0]
        full=paste(ones)
        self.assertFalse(bool(full[9,8]))
        self.assertTrue(bool(full[9,20]))
        self.assertTrue(bool(full[50,8]))
        self.assertTrue(bool(full[49,51]))
        self.assertTrue(bool(paste(ones*.5)[49,51]))
        self.assertFalse(bool(paste(ones*.499).any()))
        # Un rectangle entier couvrant les deux pixels de bord couvrirait aussi leur coin.
        self.assertTrue(bool((~paste(ones*.75) | full).all()))


if __name__=='__main__':
    unittest.main()
