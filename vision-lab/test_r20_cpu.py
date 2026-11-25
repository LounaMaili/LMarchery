"""Audit des pertes sans mise à jour, sur des tenseurs synthétiques."""
import unittest

try:
    import torch
    from r19.supervision import losses
    from r20.instrumenter import diagnose
except ModuleNotFoundError:
    torch=None


@unittest.skipIf(torch is None,'Bibliothèques CPU Vision nécessaires')
class CoutsR20(unittest.TestCase):
    def test_attributions_pertes_partielles_et_positifs_negatifs(self):
        fields=torch.full((12,3,6),-2.,requires_grad=True)
        with torch.no_grad():
            fields[0,:,0]=4;fields[1,:,1]=3;fields[2,:,5]=3
        eye=torch.eye(6)
        t=dict(ids=['complet','partiel'],own=eye[:2],others=eye[[1,0]],axis_on=eye[:2],axis_off=eye[[1,0]],
               contact=torch.stack((eye[0],torch.zeros(6))),background=eye[5],pure_bg=eye[5].bool())
        presence=torch.zeros(12,requires_grad=True)
        total,parts,audit=losses(fields,presence,t)
        cost=diagnose(fields,presence,t,parts,audit)
        self.assertEqual([r['q'] for r in cost['par_identite']],[0,1])
        self.assertIsNone(cost['par_identite'][1]['entree'])
        self.assertEqual(cost['negatifs'],[2])
        total.backward()
        self.assertLess(float(presence.grad[0]),0)
        self.assertGreater(float(presence.grad[2]),0)
        self.assertTrue((presence.grad[3:]==0).all())
