"""Contrôles sur les preuves R12 publiées. Aucun calcul de carte ni poids local."""
import json
from pathlib import Path
import unittest

import numpy as np

from r12.geometrie import geometry_index

ROOT=Path(__file__).resolve().parent


def read(path):
    return json.loads(path.read_text())


class R12Evidence(unittest.TestCase):
    def test_unsigned_geometry_and_unknown(self):
        image=np.full((60,80,3),110,np.uint8)
        support=np.ones((60,80),np.uint8)
        axis=np.array([[15.,30.],[60.,30.]])
        points=np.array([[15.,30.],[60.,30.],[35.,30.],[18.,45.]])
        scores=geometry_index(points,[axis],image,support)
        np.testing.assert_allclose(scores,geometry_index(points,[axis[::-1]],image,support))
        self.assertGreater(scores[0],scores[2])
        self.assertGreater(scores[1],scores[3])
        np.testing.assert_equal(geometry_index(points,[],image,support),np.zeros(4))
        np.testing.assert_equal(geometry_index(points,[axis],image,0*support),np.zeros(4))

    def test_pairs_partitions_and_decision(self):
        new=read(ROOT/'r12/nouvelles.json')
        self.assertFalse(new['apprentissage_admissible'])
        self.assertEqual(len({r['group'] for r in new['photos']}),12)
        holdout={r['id'] for r in new['photos'] if r['split']=='nouveau'}
        historical=read(ROOT/'r11/parite.json')['essais']
        gains=[]
        for name,baseline in historical.items():
            r11=read(ROOT/'r11/pics'/(name+'.json'))
            raw=read(ROOT/'r12/predictions'/(name+'.json'))
            result=read(ROOT/'r12/mesures'/(name+'.json'))
            self.assertEqual(raw.keys(),holdout|r11.keys())
            for key,row in raw.items():
                peaks=row['candidats']
                self.assertEqual(len(peaks),192)
                if key in r11:
                    self.assertEqual([p[:2] for p in peaks],r11[key])
                    self.assertEqual(row['carte_sha256'],baseline['photos'][key]['sha256_cartes'])
                for _,a,b,o,i,j in peaks:
                    self.assertEqual(b,a+(1-a)*i)
                    self.assertEqual(o,a+(1-a)*j)
                    self.assertTrue(0<=i<=1 and 0<=j<=1)
            a,b=(result['methodes'][m] for m in ('A','B'))
            for split in ('historique','nouveau'):
                for m in result['methodes'].values():
                    self.assertEqual(m[split]['couverture'],a[split]['couverture'])
                    self.assertEqual(m['nouveau']['seuil'],m['historique']['seuil'])
                    t=m[split]['operation']['all']
                    self.assertEqual(t['unmatched'],t['false']+t['unresolved'])
            if b['historique']['premier_contact']['unmatched']<a['historique']['premier_contact']['unmatched']:
                gains.append(name)
            self.assertEqual(b['nouveau']['operation']['all']['silent'],5)
        decision=read(ROOT/'r12/mesures/decision.json')
        self.assertEqual(sorted(gains),sorted(decision['B_cout_reduit']))
        self.assertEqual(decision['indice_geometrique'],len(gains)>=4)
        self.assertFalse(decision['critere_transport'])
