"""Contrôles sans modèle du protocole spatial R23."""
import math
import unittest
import numpy as np

from r19.donnees import read
from r23.historique import replay
from r23.spatial import measure_query, ranks, regions, strict_peaks


class SpatialR23(unittest.TestCase):
    def test_serie_avant_et_controles_apres(self):
        self.assertEqual(replay(),read('r23/historique.json'))

    def test_ex_aequo_plateaux_diagonales_et_bords(self):
        field=np.array([[8,1,1,2],[1,7,0,2],[0,0,3,2]],dtype=np.float32)
        peaks=strict_peaks(field,field).reshape(field.shape)
        self.assertTrue(peaks[0,0])
        self.assertFalse(peaks[1,1])
        self.assertFalse(peaks[0,3])
        self.assertFalse(peaks[2,2])
        self.assertEqual(ranks(np.sort(field.ravel()),2),[4,6])

    def test_disque_inclusif_et_voisin_plus_haut_hors_du_disque(self):
        spec=dict(grille=[5,3],repere_704=[50,30],
                  identites={'a':dict(xy_704=[14.5,14.5],support=[dict(index=6,poids=1.)]),
                             'b':dict(xy_704=[44.5,14.5],support=[dict(index=9,poids=1.)])})
        centers,disks=regions(spec)
        z=np.zeros((3,5),np.float32);z[1,2]=2.;z[1,3]=3.
        p=np.exp(z)/np.exp(z).sum()
        query=dict(q=0,presence=.75,identites={k:dict(log_p_support=[math.log(float(p.ravel()[i]))]) for k,i in [('a',6),('b',9)]})
        row=measure_query(z,p,spec,query,centers,disks)
        a=row['par_identite']['a']
        self.assertEqual(len(disks['a']),5)
        self.assertFalse(a['meilleure_cellule_disque']['maximum_local_strict'])
        self.assertEqual(a['modes_secondaires_stricts'],0)
        self.assertEqual(a['ecart_logit'],1)
        self.assertEqual(a['masse_disque'],sum(float(p.ravel()[i]) for i in disks['a']))
        self.assertEqual(row['pic_decode']['index'],8)
        self.assertEqual(a['perte_entree_recomposee'],-math.log(float(p.ravel()[6])))
