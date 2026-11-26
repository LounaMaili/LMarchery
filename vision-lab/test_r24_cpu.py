"""Contrôler le dégel, les BatchNorm et les unités des mesures sur des données synthétiques."""
import unittest
try:
    import torch
except ModuleNotFoundError:
    torch = None

if torch is not None:
    from r19.modele import Instances
    from r24.adaptation import SHAPES, check_frozen, clone, configure, displacement, frozen_tensors, gradients, optimizer_for


@unittest.skipIf(torch is None, 'PyTorch CPU requis')
class AdaptationR24(unittest.TestCase):
    def test_seuls_les_groupes_publies_recoivent_un_gradient(self):
        with torch.random.fork_rng(devices=[]):
            torch.manual_seed(24)
            m = Instances()
            m.train()
            groups = configure(m)
            frozen = frozen_tensors(m,groups)
            initial = clone(frozen)
            params = {id(p) for ps in groups.values() for p in ps.values()}
            opt = optimizer_for(groups,dict(betas=[.9,.999],eps=1e-8,weight_decay=.01,foreach=False))
            self.assertEqual([p['lr'] for p in opt.param_groups],[.001,.0001])
            self.assertEqual(params,{id(p) for g in opt.param_groups for p in g['params']})
            self.assertEqual(set(groups['bloc8']),set(SHAPES))
            self.assertEqual(sum(p.numel() for p in groups['bloc8'].values()),29128)
            f,p = m(torch.randint(0,256,(1,3,64,64)).float())
            (f.square().mean()+p.square().mean()).backward()
            for ps in groups.values():
                self.assertGreater(gradients(ps),0)
                self.assertTrue(all(p.grad is not None and torch.isfinite(p.grad).all() for p in ps.values()))
            self.assertTrue(all(p.grad is None for n,p in m.named_parameters() if n in frozen))
            check_frozen(m,initial,frozen)
            m.eval()
            m.train()
            check_frozen(m,initial,frozen)
            bn = next(t for t in m.features.modules() if isinstance(t,torch.nn.BatchNorm2d))
            bn.train()
            with self.assertRaises(ValueError):
                check_frozen(m,initial,frozen)
            bn.eval()
            bn.running_mean[0] += 1
            with self.assertRaises(ValueError):
                check_frozen(m,initial,frozen)

    def test_norme_et_amplitude_sur_valeurs_connues(self):
        p = torch.nn.Parameter(torch.tensor([1.,2.]))
        p.grad = torch.tensor([3.,4.])
        self.assertEqual(gradients({'p':p}),5)
        self.assertEqual(displacement({'p':p},{'p':torch.tensor([-2.,-2.])}),dict(l2=5,max_abs=4))
