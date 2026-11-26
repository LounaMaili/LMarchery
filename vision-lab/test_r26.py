"""Rejeu intégral des archives R26 et contrôles indépendants de cardinalité."""
from pathlib import Path
from functools import lru_cache
import tempfile
import unittest

import numpy as np
from r19.donnees import read
from r26.archives import ROOT, assignment, density, run
from r26.figurer import draw


def cardinality(edges):
    rows=list(edges.values())
    @lru_cache(None)
    def best(i,occupied):
        if i==len(rows):
            return 0
        gains=[1+best(i+1,occupied|1<<q) for q in rows[i] if not occupied>>q&1]
        return max([best(i+1,occupied),*gains])
    return best(0,0)


class ArchivesR26(unittest.TestCase):
    def test_rejouer_tous_les_fichiers_et_verifier_les_maxima(self):
        with tempfile.TemporaryDirectory() as folder:
            dest=Path(folder)
            run(dest)
            files=set((ROOT/'mesures').glob('*.json'))
            self.assertEqual({f.name for f in dest.iterdir()},{f.name for f in files})
            for file in files:
                self.assertEqual((dest/file.name).read_bytes(),file.read_bytes(),file.name)
            draw(read(dest/'bilan.json'),dest/'figure.svg')
            self.assertEqual((dest/'figure.svg').read_bytes(),(ROOT/'figures/bornes.svg').read_bytes())
        report=read(ROOT/'mesures/bilan.json')
        expected={'dice':[(4,5),(5,5),(4,5)],'bloc8':[(4,6),(4,5),(5,5)]}
        for arm,values in expected.items():
            for seed,(actual,bound) in zip((9301,9302,9303),values,strict=True):
                key=f'{arm}-{seed}'
                total=report['etats'][key]['ensemble']
                self.assertEqual((total['entrees'],total['masques'],total['triplet'],total['axe_parfait'],total['masque_seul']),
                                 (26,31,actual,bound,bound))
                photos=read(ROOT/'mesures'/(key+'.json'))['photos']
                self.assertEqual(len(photos),9)
                for ph in photos.values():
                    for name,graph in ph['admissibles'].items():
                        match=ph['appariements'][name]
                        self.assertEqual(len(match),cardinality(graph))
                        self.assertEqual(len(set(match.values())),len(match))
                        self.assertTrue(all(q in graph[n] for n,q in match.items()))
        self.assertEqual(report['cartes_ensemble'],dict(cartes=264,entier=63,au_moins_99=170,au_moins_95=215,identique_masque=103))
        self.assertEqual((report['cas_PCA'],report['cas_PCA_avec_masque']),(8,0))

    def test_seuil_inclusif_et_masque_inclus_dans_axe(self):
        axis=np.zeros((10,10),np.float32)
        mask=np.ones_like(axis)
        axis[0,0]=-1
        mask[0,0]=-1
        record=density(axis,mask)
        self.assertTrue(record['au_moins_99'] and record['identique_masque'])
        self.assertFalse(record['entier'])
        mask[0,0]=0
        self.assertFalse(density(axis,mask)['identique_masque'])

    def test_deux_identites_ne_partagent_pas_une_requete(self):
        self.assertEqual(assignment({'a':[0,1],'b':[0]},{'a':[0,.3],'b':[0,0]}),{'a':1,'b':0})
