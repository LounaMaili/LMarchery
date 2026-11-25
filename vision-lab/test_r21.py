import unittest

from r19.donnees import SEEDS, fingerprint, read
from r21.inventorier import ROOT, inventory
from r20.corpus import references
from r21.mesures import matching, passes
from r21.plan import draws


class GelR21(unittest.TestCase):
    def test_registre_coherent_avec_toutes_les_validites(self):
        result=inventory(references())
        self.assertEqual(result,read(ROOT/'inventaires.json'))
        admitted=[v for v in result.values() if v['admissible']]
        self.assertEqual((len(admitted),sum(v['actives'] for v in admitted),sum(v['completes'] for v in admitted)),(7,20,16))
        self.assertEqual(sum(len(v['documentaires_sans_cible']) for v in result.values()),11)
        self.assertEqual(len({v['provenance'] for v in admitted if v['actives']>1}),4)

    def test_tirages_identiques_par_bras_et_prefixe_historique(self):
        conf=read(ROOT/'configuration.json');plans=read(ROOT/'tirages.json')
        for k,val in conf['empreintes'].items():
            from pathlib import Path
            self.assertEqual(fingerprint(Path(k)),val,k)
        for seed in SEEDS:
            plan=draws(conf['photos'],seed)
            self.assertEqual(len(plan),768)
            self.assertEqual(plan,plans[str(seed)])
            self.assertEqual(plan[:96],read('r19/tirages.json')[str(seed)])
            for i in range(0,768,8):
                self.assertEqual(len({r['provenance'] for r in plan[i:i+8]}),8)
            self.assertEqual([r['photo'] for r in plan if r['provenance']=='Crigges'],['13-01','70-01']*48)

    def test_champs_partiels_seuil_inclus_et_concurrence(self):
        def cell(q,ok,d):
            return dict(q=q,masque_90_pourcent=ok,fraction_plafond=float(ok),sondes_axe=20,erreur_entree_px704=d)
        mat={'avec':[cell(0,True,0),cell(1,False,0),cell(2,True,0)],
             'sans':[cell(0,True,None),cell(1,True,None),cell(2,False,None)]}
        result=matching(mat,{'avec':['masque','axe','entree'],'sans':['masque','axe']},[.5,.7,.49999999])
        self.assertEqual(result['appariements'],{'avec':0,'sans':1})
        result=matching(mat,{'avec':['masque','axe','entree'],'sans':['masque','axe']},[.5,.3,.4])
        self.assertEqual(len(result['appariements']),1)
        self.assertEqual(len(result['manquees']),1)

    def test_decision_refuse_silence_remplacement_et_perte_de_triplet(self):
        compare={'certifiees':{'variation':{'supplementaires_p05':-1},'presence':{'perdues':[]}},
                 'toutes':{'triplets':{'perdues':[]}}}
        end={'certifiees':{'effectifs':{'representees_p05':0}}}
        self.assertFalse(passes(compare,end))
        end['certifiees']['effectifs']['representees_p05']=1
        self.assertTrue(passes(compare,end))
        compare['certifiees']['presence']['perdues']=['ancienne']
        self.assertFalse(passes(compare,end))
        compare['certifiees']['presence']['perdues']=[]
        compare['toutes']['triplets']['perdues']=['ancienne']
        self.assertFalse(passes(compare,end))
