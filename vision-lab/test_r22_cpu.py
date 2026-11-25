"""Contrôles du Dice natif sur tenseurs synthétiques, sans ajustement de modèle."""
import unittest
try:
    import torch
except ModuleNotFoundError:
    torch = None

if torch is not None:
    from r19.supervision import losses as historical
    from r22.regle import losses


@unittest.skipIf(torch is None,'PyTorch CPU nécessaire')
class DiceR22(unittest.TestCase):
    def fixture(self):
        eye = torch.eye(7)
        own = torch.stack((19*eye[0]+eye[3],2*eye[1]))
        target = dict(ids=['a','b'],own=own,others=own.flip(0),background=3*eye[2]+4*eye[3],
                      axis_on=eye[:2],axis_off=eye[[1,0]],contact=torch.stack((eye[0],torch.zeros(7))),
                      pure_bg=eye[2].bool())
        f = torch.full((12,3,7),-4.)
        f[0,:,0]=4.
        f[1,:,1]=4.
        f[2,:,2]=4.
        return f.requires_grad_(),torch.zeros(12,requires_grad=True),target

    def test_coefficient_zero_restitue_valeurs_et_gradients_exacts(self):
        f,p,t = self.fixture()
        old,parts,audit = historical(f,p,t)
        new,extended,check,_ = losses(f,p,t,0)
        self.assertTrue(torch.equal(old,new))
        self.assertEqual(audit,check)
        for k,val in parts.items():
            self.assertTrue(torch.equal(val,extended[k]))
        for a,b in zip(torch.autograd.grad(old,(f,p)),torch.autograd.grad(new,(f,p)),strict=True):
            self.assertTrue(torch.equal(a,b))

    def test_masses_natives_et_moyenne_par_identite(self):
        f,p,t = self.fixture()
        _,parts,_,cost = losses(f,p,t)
        ds = []
        for row,truth in zip(cost['par_identite'],([19,0,0,1],[0,2,0,0]),strict=True):
            probs = f[row['q'],0,:4].detach().sigmoid().tolist()
            d = (2*sum(x*y for x,y in zip(probs,truth))+1)/(sum(truth)+sum(x*y for x,y in zip(probs,[19,2,3,5]))+1)
            self.assertAlmostEqual(row['dice_doux'],d,places=6)
            ds.append(d)
        self.assertAlmostEqual(parts['dice'].item(),1-sum(ds)/2,places=6)
        t['own'][1]=0
        _,parts,_,cost = losses(f,p,t)
        self.assertIsNone(cost['par_identite'][1]['dice_doux'])
        self.assertAlmostEqual(parts['dice'].item(),1-cost['par_identite'][0]['dice_doux'],places=6)

    def test_cellules_ignorees_invariantes_et_gradient_nul(self):
        f,p,t = self.fixture()
        _,parts,_,_ = losses(f,p,t)
        gradient = torch.autograd.grad(parts['dice'],f)[0]
        self.assertTrue((gradient[:,:,4:]==0).all())
        self.assertTrue(gradient[:,:1,:4].abs().sum()>0)
        modified = f.detach().clone()
        modified[:,0,4:] = 80.
        _,other,_,_ = losses(modified,p,t)
        self.assertTrue(torch.equal(parts['dice'],other['dice']))

    def test_entree_inconnue_garde_fut_et_presence_positive(self):
        f,p,t = self.fixture()
        new,_,audit,record = losses(f,p,t)
        old,_,_ = historical(f,p,t)
        grad = torch.autograd.grad(new,(f,p))
        baseline = torch.autograd.grad(old,(f,p))
        q = record['par_identite'][1]['q']
        self.assertIsNone(record['par_identite'][1]['entree'])
        self.assertIsNotNone(record['par_identite'][1]['dice_doux'])
        self.assertLess(grad[1][q].item(),0)
        self.assertEqual((audit['masques'],audit['entrees']),(2,1))
        self.assertTrue(torch.equal(grad[0][:,1:],baseline[0][:,1:]))
        self.assertTrue(torch.equal(grad[1],baseline[1]))
        self.assertTrue((grad[0][q,2]==0).all())

    def test_permutation_recalcule_affectation_sans_dice_dans_le_cout(self):
        from r21.regle import losses as witness
        f,p,t = self.fixture()
        order = [8,1,2,3,4,5,6,7,0,9,10,11]
        for x,prob,expected in [(f,p,[0,1]),(f[order],p[order],[8,1])]:
            _,_,_,w = witness(x,prob,t,False)
            _,_,_,c = losses(x,prob,t)
            self.assertEqual(w['matrice_couts_affectation'],c['matrice_couts_affectation'])
            self.assertEqual(c['negatifs'],w['negatifs'])
            self.assertEqual([row['q'] for row in c['par_identite']],expected)
