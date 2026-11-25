"""Contrôles antérieurs aux opérations R20, sur annotations ou sorties construites."""
from itertools import product
import unittest

import numpy as np

from r19.donnees import read
from r20.champs import field_summary
from r20.corpus import frozen, integral_ceiling
from r20.mesurer import joint_assignment, photo


class R20Tests(unittest.TestCase):
    def test_plafond_exact_y_compris_cellules_mixtes_et_inconnues(self):
        rng=np.random.default_rng(20)
        for _ in range(30):
            known=rng.integers(0,7,size=6);known[0]=5
            own=np.array([rng.integers(n+1) for n in known]);own[0]=2
            ans,selected=integral_ceiling(own,known)
            brute=max(2*np.dot(mask,own)/(own.sum()+np.dot(mask,known)) for mask in product((0,1),repeat=6))
            self.assertAlmostEqual(ans['dice_max'],brute)
            self.assertEqual(ans['intersection_optimale'],int(own[selected].sum()))
            self.assertFalse(selected[known==0].any())
        ans,_=integral_ceiling([2,1,0],[2,5,0])
        self.assertEqual((ans['intersection_optimale'],ans['aire_evaluable_sortie_optimale']),(2,2))
        self.assertEqual(ans['dice_max'],.8)

    def test_meme_requete_capacite_un_et_sous_ensemble_au_seuil(self):
        names=['a','b']
        def row(valid):return [dict(q=q,admissible_conjoint=q in valid,erreur_entree_px704=float(q)) for q in range(12)]
        mat={'a':row([1,2]),'b':row([1])}
        self.assertEqual(joint_assignment(mat,names,set(range(12))),{'a':2,'b':1})
        self.assertEqual(len(joint_assignment(mat,names,{1})),1)
        self.assertEqual(joint_assignment({k:row([]) for k in names},names,set(range(12))),{})
        self.assertEqual(joint_assignment({},[],set()),{})

    def test_champs_distincts_meme_maximum_et_invariance_au_decalage(self):
        fields=np.zeros((12,3,2,3),dtype=np.float32)
        fields[:,:,0,1]=2;fields[1,2]+=5;fields[2,2,0,1]=4
        summary=field_summary(fields)
        a,b=summary['paires'][:2]
        self.assertTrue(a['entree']['meme_maximum'])
        self.assertEqual(a['entree']['distance_variation_totale'],0)
        self.assertAlmostEqual(a['entree']['correlation_centree'],1.)
        self.assertEqual(a['entree']['rmse_logit'],5.)
        self.assertGreater(b['entree']['distance_variation_totale'],0)
        self.assertEqual(summary['paires_meme_pic'],66)

    def test_trois_bonnes_taches_separees_ne_font_pas_un_triplet(self):
        size=np.array([100,70]);mask=np.zeros((70,100),np.uint8)
        mask[5,8:41]=1;mask[55,60:95]=2
        axes={'a':[[[8,5],[40,5]]], 'b':[[[60,55],[94,55]]]}
        ref=dict(size=size,mask=mask,ids={'a':1,'b':2},entries={'a':np.array([40,5]),'b':np.array([94,55])},
                 axes=axes,ignored=[],empty=False,exhaustive=True)
        bits=np.zeros_like(mask,dtype=np.uint16);bits[mask==1]=1;bits[mask==2]=8
        rows=[dict(id=q,xy=([40,5] if q==2 else [94,55] if q==3 else [1,67]),score=.5,segments=[]) for q in range(12)]
        rows[1]['segments']=axes['a'];rows[3]['segments']=axes['b']
        out=dict(contacts=rows,axes={1:axes['a'],3:axes['b']},bits=bits)
        cap={n:dict(positifs_natifs=t,denominateur_dice=2*t,intersection_optimale=t,dice_max=1.) for n,t in [('a',33),('b',35)]}
        cost=dict(par_identite=[dict(instance='a',q=0),dict(instance='b',q=3)],negatifs=[])
        result=photo(ref,out,cap,.5,cost)
        self.assertEqual((result['bilan']['masques_apparies'],result['bilan']['axes_apparies'],result['bilan']['contacts_couverts']),(2,2,2))
        self.assertIsNone(result['identites']['a']['conjoint_q'])
        self.assertEqual(result['identites']['b']['conjoint_q'],3)
        self.assertEqual(result['bilan']['triplets'],1)

    def test_gel_sans_reseau_et_temoins_geometriques(self):
        seal,caps=frozen()
        published=read('r20/configuration.json')
        for current,old in zip(seal['eligibles_par_ordre'],published['eligibles_par_ordre'],strict=True):
            self.assertEqual(current['photo'],old['photo'])
            for field in ('largeur_min_cellules','separation_min_cellules'):
                self.assertAlmostEqual(current[field],old[field],places=10)
        self.assertEqual({k:v for k,v in seal.items() if k!='eligibles_par_ordre'},
                         {k:v for k,v in published.items() if k!='eligibles_par_ordre'})
        self.assertEqual(seal['photo_B'],'24614689941')
        self.assertEqual(len(seal['cibles_B']),2)
        original=read('r20/plafonds.json')
        for photo,r in caps.items():
            for name,a in r['instances'].items():
                b=original[photo]['instances'][name]
                for key in ('positifs_natifs','intersection_optimale','denominateur_dice','histogramme_p_n_effectif','dice_max'):
                    self.assertEqual(a[key],b[key])
        for name in seal['cibles_B']:
            c=caps[seal['photo_B']]['instances'][name]
            self.assertGreaterEqual(c['temoin_grille']['sondes_axe'],11)
            self.assertLessEqual(c['erreur_centre_px704'],10.)
            self.assertGreaterEqual(c['temoin_grille']['dice'],c['dice_requis'])
