"""Cas synthétiques aux limites de la géométrie et de l'instrumentation historiques."""
import unittest
import numpy as np

from r19.decoder import fragments
from r19.donnees import work_to
from r25.analyser import limiting
from r25.composantes import trace
from r25.geometrie import probe_rows
from r25.sondes import STAGES, grid_distance, read_map


class DiagnosticR25(unittest.TestCase):
    def test_eligibilite_pour_un_meme_segment_et_orientation_non_dirigee(self):
        probes=np.array([[0.,0.],[1.,0.]])
        tangent=np.array([[1.,0.],[-1.,0.]])
        # Le segment vertical est plus proche, le segment horizontal à six pixels gagne par orientation.
        lines=[[[0,-2],[0,2]],[[4,6],[-2,6]],[[2,0],[2,0]]]
        rows=probe_rows(probes,tangent,lines)
        for r in rows:
            self.assertEqual((r[0],r[2],r[3],r[4],r[5],r[-1]),(0,90.,1,6.,0.,True))
        self.assertEqual(probe_rows(probes,tangent,[])[0], [None]*7+[False,False])

    def test_composantes_portions_et_identite_avec_le_decodeur(self):
        a=np.zeros((32,40),bool)
        a[2,1]=1
        a[5:8,2:5]=1
        a[19:22,5:30]=1
        a[18,10:25]=1
        c,stages,lines=trace(a)
        self.assertEqual([r['decision'] for r in c],['taille','elongation','segments'])
        self.assertTrue(np.array_equal(lines['final'],fragments(a)))
        self.assertTrue(np.all(stages['portee']<=stages['taille']))
        for r in c:
            if r['pca'] and 'portions' in r['pca']:
                spans=r['pca']['portions']
                self.assertEqual(spans[0][0],0)
                self.assertEqual(spans[-1][1],r['pca']['echantillons'])
                self.assertTrue(all(a[1]==b[0] for a,b in zip(spans,spans[1:])))

    def test_support_discret_sur_grille_anisotrope_et_lecture_aux_bords(self):
        a=np.array([[False,True,False],[False,False,True]])
        grid=np.array([3,2]);view=np.array([7,8])
        p=np.array([[0.,0.],[4.4,5.3],[6.,7.]])
        xy=work_to(np.column_stack(np.nonzero(a)[::-1]),grid,view)
        expected=np.linalg.norm(p[:,None]-xy,axis=-1).min(1)
        np.testing.assert_allclose(grid_distance(a,p,grid,view),expected,atol=1e-14,rtol=0)
        self.assertTrue(np.isinf(grid_distance(a&False,p,grid,view)).all())
        z=np.arange(6,dtype=np.float32).reshape(2,3)
        near,bil=read_map(z,np.array([[-.4,-.4],[1.5,.5],[3.,2.]]))
        self.assertEqual(near.tolist(),[0,5,5])
        self.assertEqual(bil.tolist(),[0,3,5])

    def test_limite_aval_sans_fusion_entre_requetes(self):
        rows=[dict(comptes=dict.fromkeys(STAGES,0)) for _ in range(12)]
        rows[0]['comptes']['axe']=12
        rows[1]['comptes']['pca']=11
        result=limiting(dict(requetes=rows))
        self.assertEqual(result['limite'],'decoupe_composante')
        self.assertEqual(result['requetes_les_plus_avancees'],[1])
        self.assertEqual(result['limites_par_q'][:3],['intersection_masque','decoupe_composante','axe_seuille'])
