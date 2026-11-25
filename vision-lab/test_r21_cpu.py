"""Contrôles synthétiques de la seule modification de perte, sans ajustement."""
import unittest

try:
    import torch
    from r19.modele import Instances
    from r19.supervision import losses as original
    from r21.plan import tensor_state
    from r21.regle import losses
except ModuleNotFoundError:
    torch=None


@unittest.skipIf(torch is None,'Bibliothèques CPU Vision nécessaires')
class PresenceR21(unittest.TestCase):
    def tensors(self):
        eye=torch.eye(6)
        t=dict(ids=['complet','sans_entree'],own=eye[:2],others=eye[[1,0]],axis_on=eye[:2],axis_off=eye[[1,0]],
               contact=torch.stack((eye[0],torch.zeros(6))),background=eye[5],pure_bg=eye[5].bool())
        f=torch.full((12,3,6),-4.)
        f[0,:,0]=4.;f[1,:,1]=4.;f[2,:,5]=4.
        return f.requires_grad_(),torch.zeros(12,requires_grad=True),t

    def test_partiel_restitue_exactement_la_perte_et_les_gradients(self):
        f,p,t=self.tensors()
        old,parts,_=original(f,p,t)
        orig=torch.autograd.grad(old,(f,p))
        new,newparts,_,audit=losses(f,p,t,False)
        self.assertTrue(torch.equal(old,new))
        for k in parts:self.assertTrue(torch.equal(parts[k],newparts[k]))
        for a,b in zip(orig,torch.autograd.grad(new,(f,p)),strict=True):self.assertTrue(torch.equal(a,b))
        self.assertEqual(audit['negatifs'],[2])

    def test_exhaustif_protege_le_fut_sans_entree_et_isole_la_presence(self):
        f,p,t=self.tensors()
        old,op,_,a=losses(f,p,t,False); new,np_,_,b=losses(f,p,t,True)
        self.assertEqual(a['par_identite'],b['par_identite'])
        self.assertEqual(b['par_identite'][1]['q'],1)
        self.assertIsNone(b['par_identite'][1]['entree'])
        for key in ('axe','masque','entree'):self.assertTrue(torch.equal(op[key],np_[key]))
        orig=torch.autograd.grad(old,(f,p));candidate=torch.autograd.grad(new,(f,p))
        self.assertTrue(torch.equal(orig[0],candidate[0]))
        self.assertTrue((candidate[1][:2]<0).all())
        self.assertTrue((candidate[1][2:]>0).all())
        self.assertEqual(b['negatifs'],list(range(2,12)))

    def test_affectation_recalculee_si_les_requetes_permutent(self):
        f,p,t=self.tensors()
        order=[8,1,2,3,4,5,6,7,0,9,10,11]
        _,_,_,audit=losses(f[order],p[order],t,True)
        self.assertEqual([v['q'] for v in audit['par_identite']],[8,1])
        self.assertIn(0,audit['negatifs']);self.assertNotIn(8,audit['negatifs'])

    def test_empreinte_des_parametres_et_initialisation_appariee(self):
        for seed in (9301,9302,9303):
            torch.manual_seed(seed);m=Instances()
            torch.manual_seed(seed);replica=Instances()
            self.assertEqual(tensor_state(m.state_dict()),tensor_state(replica.state_dict()))
        with torch.no_grad():replica.queries[0,0]+=1
        self.assertNotEqual(tensor_state(m.state_dict())['sha256'],tensor_state(replica.state_dict())['sha256'])
