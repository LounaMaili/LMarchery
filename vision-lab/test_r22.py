"""Couverture historique et critères d'identité, indépendants de PyTorch."""
import tempfile
import unittest
from pathlib import Path

from r19.donnees import read
from r22.diagnostic import diagnostic
from r22.lecture import compare


class MesuresR22(unittest.TestCase):
    def test_diagnostic_historique_reproductible(self):
        with tempfile.TemporaryDirectory() as tmp:
            diagnostic(Path(tmp))
            for path in Path('r22/diagnostic').glob('*.json'):
                self.assertEqual(path.read_bytes(),(Path(tmp)/path.name).read_bytes())
        rows = read('r22/diagnostic/blocages.json')
        zig = next(r for r in rows if r['provenance']=='zigazou76')
        self.assertIn('triplet_q',zig['pertes'])
        self.assertEqual(zig['candidat']['blocages_minimaux'],[['masque']])
        self.assertEqual([zig['candidat']['requetes'][10][k] for k in ('VP','FP','FN')],[262,144,0])

    def test_decision_sans_compensation_sur_les_identites(self):
        fields = ('masque_90_q','axe_q','entree_q','triplet_q','triplet_au_seuil_q','conjoint_q','presence_q')
        def trial(triplets,entries,sources=('A','B','Crigges')):
            return {n:dict(provenance=source,identites={n:{f:0 if n in
                    (entries if f=='entree_q' else triplets) else None for f in fields}})
                    for n,source in zip('abc',sources,strict=True)}
        before = trial('c','abc')
        self.assertTrue(compare(before,trial('abc','abc'))['conforme'])
        for after in (trial('ab','abc'),trial('abc','bc'),trial('ac','abc')):
            self.assertFalse(compare(before,after)['conforme'])
        same = ('A','A','Crigges')
        self.assertFalse(compare(trial('c','abc',same),trial('abc','abc',same))['conforme'])
