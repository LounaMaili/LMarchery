"""Recomptage des six archives et trois conditions nominatives indépendantes."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from r24.diagnostic import ROOT, audit
from r24.mesures import decision_for


class CritereR24(unittest.TestCase):
    def test_reorientation_reproduite_sans_photographies(self):
        with TemporaryDirectory() as tmp:
            out = Path(tmp)
            stats = audit(out)
            self.assertEqual(sum(r['triplets_manquants'] for r in stats.values()),136)
            self.assertEqual(sum(r['manquants_masque_et_axe_disponibles'] for r in stats.values()),3)
            for p in (ROOT/'diagnostic').glob('*.json'):
                self.assertEqual(p.read_bytes(),(out/p.name).read_bytes())

    def test_union_et_identites_hors_crigges(self):
        fields = ('triplet_q','masque_90_q','axe_q','triplet_au_seuil_q','conjoint_q','presence_q','masque_et_axe_q')
        def state(triplets, entries, sources=('A','B','Crigges','D')):
            return {n:dict(provenance=source, identites={n:dict(champs=['masque','axe','entree'],
                        entree_q=0 if n in entries else None, **{f:0 if n in triplets else None for f in fields})})
                    for n,source in zip('abcd',sources,strict=True)}
        a,b = state('c','abd'),state('c','bcd')
        good = decision_for(a,b,state('abc','abcd'),False)
        self.assertTrue(good['conforme'])
        self.assertEqual(good['union_entrees'],list('abcd'))
        for c in (state('abc','bcd'),state('ab','abcd'),state('ac','abcd')):
            self.assertFalse(decision_for(a,b,c,False)['conforme'])
        same = ('A','A','Crigges','D')
        self.assertFalse(decision_for(state('c','abd',same),state('c','bcd',same),state('abc','abcd',same),False)['conforme'])
        with self.assertRaises(ValueError):
            decision_for(a,b,state('abc','abcd'))
