"""Contrôles du pilote direct. Les exemples de gradient sont synthétiques."""
from itertools import permutations
from pathlib import Path
import unittest

import cv2 as cv
import numpy as np

from r19.affecter import minimum, pairs
from r19.donnees import R18, ROOT, SEEDS, cell_indices, eval_size, input_size, read, verify, work_to
from r19.decoder import fragments
from r19.mesures import Contacts, axis_cover, axes_report, baseline, decision, dev_refs, link_entries, masks_report, threshold_curve

try:
    import torch
    from r19.modele import Instances, parameter_counts
    from r19.pilote import predict_photo
    from r19.supervision import axis_weights, contact_weights, extra_on_background, losses, prepare
except ModuleNotFoundError:
    torch = None


class GeometrieR19(unittest.TestCase):
    def test_affectation_capacite_minimum_et_determinisme(self):
        rng = np.random.default_rng(17)
        for n,m in ((0,2),(2,2),(3,5),(4,4)):
            for _ in range(6):
                cost = rng.integers(0,6,(n,m))
                cols = minimum(cost)
                self.assertEqual(len(set(cols)),n)
                self.assertEqual(sum(cost[i,c] for i,c in enumerate(cols)),
                                 min(sum(cost[i,c] for i,c in enumerate(p)) for p in permutations(range(m),n)))
        d = np.array([[.8,.95],[.2,1.1]])
        self.assertEqual(pairs(d<=1,d),[(0,1),(1,0)])
        self.assertEqual(minimum(np.zeros((3,4))).tolist(),[0,1,2])

    def test_gel_train_et_centres_des_deux_familles_R16(self):
        specs = verify()
        plans = read(ROOT/'tirages.json')
        from collections import Counter
        for plan in plans.values():
            self.assertEqual(len(plan),96)
            self.assertEqual(set(Counter(d['provenance'] for d in plan).values()),{12})
            self.assertEqual(Counter(d['photo'] for d in plan)['70-01'],6)
            self.assertEqual({d['photo'] for d in plan},{p['photo'] for p in specs if p['partition']=='train'})
        refs = dev_refs(specs)
        self.assertEqual((len(refs),sum(len(r['entries']) for r in refs.values()),sum(len(r['ids']) for r in refs.values())),(11,16,20))
        data = baseline(refs)
        from r19.donnees import BASELINE
        raw = read(BASELINE)
        for k,r in refs.items():
            size,view = r['size'],r['view']
            self.assertTrue(np.allclose(work_to(work_to([[0,0],size-1],size,view),view,size),[[0,0],size-1]))
            if raw[k]['candidats']:
                point,h,b,v = raw[k]['candidats'][0]
                c = data[k]['contacts'][0]
                self.assertEqual(c['score'],b)
                np.testing.assert_allclose(c['xy'],(np.array(point)+.5)*raw[k]['echelle']-.5)
            self.assertEqual(list(data[k]['axes'].values()),[[p[1]] for p in raw[k]['segments']])
        np.testing.assert_allclose(work_to([0,1],[1408,1056],[704,528]),[-.25,.25])

    def test_seuil_groupes_ex_aequo_et_regions_ignorees(self):
        r = dict(entries={'i':[0,0]},ignored=[np.array([[12,0],[30,0],[30,5],[12,5]],np.float32)],exhaustive=True,empty=False)
        o = Contacts(r,[dict(id=0,score=.9,xy=[0,0]),dict(id=1,score=.9,xy=[12,1])])
        t,c,_ = threshold_curve({'a':o})
        self.assertEqual((t,len(c)),(2.,2))
        self.assertEqual((c[-1]['apparies'],c[-1]['non_resolus'],c[-1]['faux_etablis']),(1,1,0))
        empty = Contacts(dict(r,entries={},ignored=[],empty=True),[])
        t,c,_ = threshold_curve(dict(a=o,b=empty,c=empty,d=empty))
        self.assertEqual(t,.9)
        false = Contacts(dict(r,entries={},empty=True,ignored=[]),[dict(id=0,score=.1,xy=[1,1])]).report(1)
        self.assertEqual((false['faux_etablis'],false['vides_declenches']),(1,1))
        self.assertTrue(Contacts(dict(r,exhaustive=False),[dict(id=0,score=.1,xy=[70,70])]).report(1)['non_resolus'])

    def test_axes_fragmentes_retournes_et_capacite_un(self):
        ref = [[[0,0],[10,0]],[[80,0],[100,0]]]
        self.assertEqual(axis_cover(ref,[[[100,0],[80,0]],[[10,0],[0,0]]]),21)
        self.assertEqual(axis_cover(ref,[[[29,0],[61,0]]]),0)
        self.assertEqual(axis_cover(ref,[[[0,-1],[1,5]]]),0)
        self.assertEqual(axes_report({'axes':{'a':ref,'b':ref}},{0:ref})['apparies'],1)
        on = np.zeros((7,28),bool); on[4,1:10]=True; on[4,15:26]=True
        seg = fragments(on)
        self.assertEqual(len(seg),2)
        self.assertTrue(all(max(s[0][0],s[1][0])<11 or min(s[0][0],s[1][0])>13 for s in seg))

    def test_gains_nets_deux_sources_et_garde_fous(self):
        def report(a,b,axes=5,empty=0):
            rows = {}
            for prov,ids in (('alpha',a),('beta',b)):
                rows[prov] = dict(provenance=prov,selection=dict(paires=[dict(instance=prov+str(i)) for i in ids]))
            return dict(par_photo=rows,par_strate={'ensemble':dict(apparies=len(a)+len(b),axes_apparies=axes,
                       non_appariees=2,vides_declenches=empty)})
        base = report([1],[1]); gain = report([1,2],[1,2])
        systems = {'B':base,'9301':gain,'9302':gain,'9303':base}
        self.assertTrue(decision(systems)['poursuivre'])
        for bad in (report([1],[]), report([1],[1],axes=4), report([1],[1],empty=1)):
            self.assertFalse(decision(dict(systems,**{'9303':bad}))['poursuivre'])
        self.assertFalse(decision(dict(systems,**{'9301':report([1,2,3],[1])}))['poursuivre'])

    def test_dice_fusion_dedoublement_omissions_ignorance_et_entree_seule(self):
        truth = np.array([[1,1,2,2,255,3,0]],np.uint8)
        bits = np.array([[3,3,1,1,1,0,0]],np.uint16)
        result = masks_report(dict(ids={'a':1,'b':2,'c':3},mask=truth,size=np.array([7,1])),bits)
        self.assertEqual(result['fusions'],{'0':['a','b']})
        self.assertEqual(result['dedoublements'],{'a':[0,1]})
        self.assertEqual([r['sortie'] for r in result['par_identite']],[1,0,None])
        self.assertEqual([r['dice'] for r in result['par_identite']],[1.,2/3,0.])
        p = dict(paires=[dict(instance='a',sortie=0),dict(instance='sans',sortie=3)])
        self.assertEqual([r['statut'] for r in link_entries(p,result)],['autre_identite','reference_sans_masque'])


@unittest.skipIf(torch is None,'R19 requiert Torch et torchvision CPU pour les gradients')
class ApprentissageR19(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(4)

    def test_presence_ceinture_de_fond_et_inconnues(self):
        f = torch.full((4,3,5),-4.)
        f[0,0,1] = f[0,2,1] = 2
        f[1,0,1] = f[1,2,3] = 2
        f[2,0,3] = f[2,2,1] = 2
        pure = torch.tensor([False,True,True,False,False])
        self.assertEqual(extra_on_background(f,[0,1,2,3],pure),[0])
        t = dict(ids=[],pure_bg=torch.zeros(5,dtype=torch.bool),background=torch.ones(5))
        for k in ('own','others','axis_on','axis_off','contact'):
            t[k] = torch.empty((0,5))
        f.requires_grad_(); presence = torch.zeros(4,requires_grad=True)
        value,_,_ = losses(f,presence,t); value.backward()
        self.assertEqual(value.item(),0)
        self.assertTrue((f.grad==0).all() and (presence.grad==0).all())

    def test_supervisions_independantes_gradients_et_distributions(self):
        f = torch.zeros(2,3,5,requires_grad=True)
        p = torch.zeros(2,requires_grad=True)
        unit = torch.tensor([[1.,0,0,0,0]])
        t = dict(ids=['a'],own=unit,others=unit.roll(1,1),axis_on=unit,axis_off=unit.roll(2,1),
                 background=unit[0].roll(3),contact=unit*0,pure_bg=torch.zeros(5,dtype=torch.bool))
        total,_,audit = losses(f,p,t); total.backward()
        self.assertEqual(audit['entrees'],0)
        self.assertTrue((f.grad[:,2]==0).all() and (f.grad[:,:,4]==0).all())
        self.assertLess(float(f.grad[0,0,0]),0); self.assertGreater(float(f.grad[0,0,1]),0)
        f.grad.zero_(); p.grad.zero_()
        t.update(own=unit*0,axis_on=unit*0,contact=unit)
        total,parts,audit = losses(f,p,t); total.backward()
        self.assertTrue((f.grad[:,:2]==0).all())
        self.assertEqual(audit['entrees'],1)
        self.assertGreater(abs(float(f.grad[0,2,0])),0)

    def test_poids_des_troncons_et_plan_uint8(self):
        photo = dict(dimensions_travail=[100,80])
        p = dict(instance='a',indice=1,masque_valide=True,axe_valide=True,entree_valide=False,entree_supervisee=None,
                 axes_supervises=[[[10,40],[25,40]],[[70,40],[88,40]]])
        mask = np.full((80,100),255,np.uint8); mask[36:45,10:26]=1; mask[36:45,70:89]=1; mask[45:60]=0
        grid = np.array([100,80]); a,b = axis_weights(p,mask,grid,[])
        flipped=dict(p,axes_supervises=[line[::-1] for line in p['axes_supervises'][::-1]])
        aa,bb=axis_weights(flipped,mask,grid,[])
        np.testing.assert_allclose(aa,a); np.testing.assert_allclose(bb,b)
        self.assertFalse(a.reshape(80,100)[:,33:66].any())
        t=prepare(photo,dict(instances=[p,dict(p,instance='doc',masque_valide=False,axe_valide=False)]),
                  dict(regions_ignorees=[]),mask)
        self.assertEqual(t['ids'],['a']); self.assertEqual(t['own'].sum(),(mask==1).sum())
        self.assertEqual(t['background'].sum(),(mask==0).sum())

    def test_modele_synthetique_photo_seule_et_BN_figee(self):
        model=Instances()
        self.assertEqual(parameter_counts(model),read(ROOT/'configuration.json')['parametres'])
        model.train()
        self.assertFalse(any(b.training for b in model.features.modules() if isinstance(b,torch.nn.BatchNorm2d)))
        model.eval()
        record,bits=predict_photo(model,np.full((64,80,3),110,np.uint8))
        self.assertEqual(len(record['sorties']),12)
        self.assertEqual(bits.dtype,np.uint16)
        self.assertTrue(all(s['score']==s['presence']*s['pic_spatial'] for s in record['sorties']))
