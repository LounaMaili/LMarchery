"""Diagnostic R29 sur fichiers GitHub. Aucune dépendance envers Torch ou Detectron2."""
import tempfile
import unittest
from pathlib import Path

import numpy as np

from r29.commun import ROOT, STAGES, read
from r29.mesurer import measure_stage, run
from r29.publier import publish
from r29.selection import explain


def maximum(edges):
    """Chemins augmentants, indépendants de l'affectation historique de coût minimal."""
    owners={}
    def augment(n,seen):
        for j in edges[n]:
            if j in seen:
                continue
            seen.add(j)
            if j not in owners or augment(owners[j],seen):
                owners[j]=n
                return True
        return False
    for n in edges:
        augment(n,set())
    return len(owners)


class DiagnosticR29(unittest.TestCase):
    def test_suppression_par_le_premier_survivant(self):
        # B recouvre A et C, mais B supprimée ne peut éliminer C.
        raw=np.array([[0,0,6,4],[1,0,7,4],[2,0,8,4],[8,0,8,2],[8,0,10,2]],np.float32)
        score=np.array([.9,.9,.8,1.,0.],np.float32)
        result=explain(raw,score,[10,10],np.array([0,2],np.int64))
        self.assertEqual(result['supprime_par'].tolist(),[-1,0,-1,-1,-1])
        self.assertEqual(result['admission'].tolist(),[0,1,2])
        self.assertAlmostEqual(result['iou_suppression'][1],5/7)
        with self.assertRaises(ValueError):
            explain(raw,score,[10,10],np.array([0]))

    def test_seuil_inclusif_affectation_et_certificat_minimal(self):
        names=['A','B','C','D','E']
        cap={n:dict(positifs_natifs=11,intersection_optimale=11,denominateur_dice=22,dice_max=1.) for n in names}
        # A,B,C ne disposent que de la même boîte ; D dispose aussi d'une autre.
        mat=np.array([[9,10,11,9,0],[8,8,8,11,0],[8,0,8,0,8]],np.int64)
        state=measure_stage([0,1,2],mat,names,cap)
        self.assertEqual(state['individuelles'],4)
        self.assertEqual(state['un_a_un'],2)
        self.assertEqual(state['sans_possibilite'],['E'])
        self.assertEqual(state['par_identite']['A']['borne_max'],.9)
        cert=state['concurrences'][0]
        self.assertEqual((cert['identites_temoin'],cert['origines_temoin'],cert['deficit']),(['A','B','C'],[0],2))
        self.assertEqual(len(state['possibles_non_attribuees']),2)
        empty=measure_stage([],mat,names,cap)
        self.assertEqual((empty['individuelles'],empty['un_a_un'],empty['concurrences']),(0,0,[]))

    def test_graphes_publics_par_chemins_augmentants(self):
        visited=0
        for p in sorted((ROOT/'mesures/photos').glob('*.json')):
            row=read(p)
            for k in STAGES:
                state=row['etages'][k]
                edges={n:v['possibles_j'] for n,v in state['par_identite'].items()}
                self.assertEqual(maximum(edges),state['un_a_un'],(p.name,k))
                for c in state['concurrences']:
                    neighbors=set().union(*(set(edges[n]) for n in c['identites_temoin']))
                    self.assertEqual(sorted(neighbors),c['origines_temoin'])
                    self.assertEqual(len(c['identites_temoin'])-len(neighbors),c['deficit'])
                visited+=1
        self.assertEqual(visited,108)

    def test_historique_et_budget_sans_modele(self):
        rec=read(ROOT/'traces/index.json')
        self.assertEqual((rec['budget'],rec['passages'],rec['appels_masque_intermediaires']),(18,18,0))
        charged=[s.split() for s in rec['marqueurs']]
        self.assertEqual(sorted(int(n) for n,_,_,_ in charged),list(range(1,19)))
        self.assertEqual({(int(seed),photo) for _,seed,step,photo in charged if step=='768'},
                         {(v['graine'],v['photo']) for v in rec['traces'].values()})
        for v in rec['traces'].values():
            self.assertEqual(v['appels'],['rpn','roi','selection','masque'])
            self.assertEqual(len(v['champs']),8)
            self.assertTrue(all(r['identique'] and r['sha256_instrumente']==r['sha256_R27'] for r in v['champs'].values()))
        self.assertEqual(read(ROOT/'intersections/index.json')['supports_finaux_identiques_R28'],216)

    def test_rejeu_complet_et_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            dest=Path(folder)
            run(dest/'mesures')
            publish(dest,dest/'mesures')
            expected={p.relative_to(ROOT) for name in ('mesures','figures') for p in (ROOT/name).rglob('*') if p.is_file()}
            expected.update(Path(name) for name in ('identites.md','provenances.md','retraits.md'))
            actual={p.relative_to(dest) for p in dest.rglob('*') if p.is_file()}
            self.assertEqual(actual,expected)
            for path in sorted(actual):
                self.assertEqual((dest/path).read_bytes(),(ROOT/path).read_bytes(),str(path))
        print('Rejeu R29 : 18 traces, 18 000 ancêtres, 108 graphes, 62 000 couples par géométrie, aucun appel de modèle.')
