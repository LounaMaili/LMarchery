import json
from pathlib import Path
import unittest

import numpy as np
from r13.diagnostic import shortened
from r13.segments import reconstruct

ROOT=Path(__file__).resolve().parent
def read(p):
    return json.loads(p.read_text())


class ReconstructionTests(unittest.TestCase):
    def test_shorten_correct_end_and_keep_unknown(self):
        row={'marks':[{'axis':[[80,20],[10,20]],'entry':[81,19]},
                      {'axis':[[5,5],[11,5]],'entry':[11,5]},
                      {'axis':[[1,1],[8,8]],'entry':None}]}
        near,n=shortened(row,np.ones(2),20,True)
        far,f=shortened(row,np.ones(2),20,False)
        self.assertEqual((n,f),(1,1))
        np.testing.assert_equal(near[0],[[60,20],[10,20]])
        np.testing.assert_equal(far[0],[[80,20],[30,20]])
        np.testing.assert_equal(near[1],far[1])
        self.assertEqual(row['marks'][0]['axis'][0],[80,20])

    def test_segment_from_pixels_without_mask(self):
        board=np.full((100,160,3),210,np.uint8)
        mask=np.zeros((100,160),np.uint8)
        self.assertEqual(reconstruct(board,mask),[])
        board[45:51,12:148]=30
        axes=reconstruct(board,mask)
        self.assertTrue(any(np.linalg.norm(axis[1]-axis[0])>100 and abs(np.mean(axis[:,1])-48)<4 for axis in axes))

    def test_published_pairing_and_gates(self):
        verdict=read(ROOT/'r13/mesures/decision.json')
        self.assertEqual(len(verdict['essais']),6)
        for name,v in verdict['essais'].items():
            raw=read(ROOT/'r13/sorties'/(name+'.json'))
            ref=read(ROOT/'r12/predictions'/(name+'.json'))
            self.assertEqual(raw.keys(),ref.keys())
            for k,p in raw.items():
                self.assertEqual(p['carte_sha256'],ref[k]['carte_sha256'])
                self.assertEqual([[v[0],v[1],v[2],v[4]] for v in p['candidats']],[q[:4] for q in ref[k]['candidats']])
                self.assertLessEqual(len(p['axes_B']),32)
            metrics=read(ROOT/'r13/mesures'/(name+'.json'))
            gain=all(x['delta']>0 and x['acquisitions']>=2 for x in v['gains'].values())
            self.assertEqual(v['geo'],gain and v['parasites']['B']<=v['parasites']['A']+5)
            for s in ['historique','nouveau']:
                for m in metrics['contacts'].values():
                    self.assertEqual(m[s]['couverture'],metrics['contacts']['H'][s]['couverture'])
                    self.assertEqual(m['historique']['seuil'],m['nouveau']['seuil'])
        for key,name in [('geometrie','geo'),('contacts','contact')]:
            self.assertEqual(verdict[key],sum(x[name] for x in verdict['essais'].values())>=4)
