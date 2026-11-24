"""Contrôler la décision R16 depuis GitHub, sans pixels, poids ni nouvel ajustement."""
from collections import defaultdict
import csv
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import numpy as np

from r16.donnees import (INPUTS, LAB, RUNS, corpus, documentary, fingerprint, matrices,
                         operational, read)
from r16.mesurer import CRITERIA, criteria, decision, segment_photo, segment_totals
from r16.preparer import prepare
from r16.scores import Verificateur
from r16.tableaux import render


class VerificateurTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.summary = read(LAB/'mesures/synthese.json')
        cls.model = read(LAB/'verificateur.json')
        cls.photos = {p['id']:p for p in corpus() if p['partition']=='dev_indep'}

    def test_selection_positive_unique_et_masse_du_train(self):
        # Les photos ne sont nécessaires qu’au contrôle d’empreintes de la machine de collecte.
        with TemporaryDirectory() as tmp, patch('r16.preparer.verify',return_value=read(LAB/'verification.json')):
            prepare(Path(tmp))
            for name in ('exemples-fit.json','effectifs-fit.json'):
                self.assertEqual(read(Path(tmp)/name),read(LAB/name))
        e = read(LAB/'exemples-fit.json')['exemples']
        pos = [r for r in e if r['y']]
        self.assertEqual(len(pos),len({r['instance'] for r in pos}))
        self.assertTrue(all(r['rang_sortie'] is not None for r in pos))
        self.assertEqual(sum(r['rang_sortie'] is None for r in e),28)
        train = {p['id'] for p in corpus() if p['partition']=='train'}
        self.assertTrue({r['photo'] for r in e} <= train)
        weights = defaultdict(lambda:defaultdict(float))
        for r in e:
            weights[r['bloc']][r['y']] += r['poids']
        for classes in weights.values():
            for weight in classes.values():
                self.assertAlmostEqual(weight,57/(12*len(classes)))

    def test_pretraitement_et_solution_ponderee_sans_fit(self):
        v = Verificateur(self.model)
        self.assertEqual(v.colonnes,INPUTS)
        self.assertEqual(self.model['exemples_sha256'],fingerprint(LAB/'exemples-fit.json'))
        e = read(LAB/'exemples-fit.json')['exemples']
        x = np.array([r['x'] for r in e],dtype=np.float64)
        w = np.array([r['poids'] for r in e])
        y = np.array([r['y'] for r in e])
        for i in np.flatnonzero(np.isnan(x).any(axis=0)):
            self.assertEqual(v.medianes[i],np.nanmedian(x[:,i]))
        filled = np.where(np.isnan(x),v.medianes,x)
        np.testing.assert_allclose(v.moyennes,np.average(filled,axis=0,weights=w),rtol=1e-12)
        np.testing.assert_allclose(v.echelles**2,np.average((filled-v.moyennes)**2,axis=0,weights=w),rtol=1e-12)
        z = (filled-v.moyennes)/v.echelles
        p,kept = v.appliquer(x)
        residual = w*(p-y)
        np.testing.assert_allclose(z.T@residual+v.beta,0,atol=2e-6)
        self.assertAlmostEqual(residual.sum(),0,places=6)
        self.assertEqual((int(kept[y==1].sum()),int(kept[y==0].sum())),(7,2))
        self.assertEqual(self.model['nombre_ajustements'],1)

    def test_parite_cartes_sorties_et_mesures_geometriques(self):
        expected = read('r15/caracteristiques/index.json')['poids']
        maps = read(LAB/'inventaire-cartes.json')
        self.assertEqual(maps['passages_reserves'],66)
        self.assertEqual(len({(m['poids'],m['photo']) for m in maps['couples']}),66)
        labels = documentary()
        v = Verificateur(self.model)
        self.assertEqual(len(self.photos),11)
        for name in RUNS:
            features = matrices(name)
            raw = read(LAB/'predictions'/(name+'.json'))
            detail = read(LAB/'mesures'/(name+'.json'))
            self.assertEqual(set(self.photos),set(raw))
            tube_sha = {r['photo']:r['carte_tube_sha256'] for r in expected[name]['photos']}
            for m in [r for r in maps['couples'] if r['poids']==name]:
                p = self.photos[m['photo']]
                k = p['id']
                d = raw[k]
                self.assertEqual((p['sha256_pixels'],tube_sha[k],d['sha256_tube'],d['sha256_contact']),
                                 (m['source_photo_sha256'],m['sha256_tube'],m['sha256_tube'],m['sha256_contact']))
                rows = operational(features[k])
                probs,kept = v.appliquer(v.matrice(rows))
                for line,(rank,axis,prob,keep),q,ok in zip(rows,d['segments'],probs,kept,strict=True):
                    self.assertEqual(rank,line['rang_longueur'])
                    self.assertEqual([format(a,'.12g') for xy in axis for a in xy],
                                     [line[key] for key in ('x0','y0','x1','y1')])
                    self.assertAlmostEqual(prob,q,places=13)
                    self.assertEqual(keep,ok)
                self.assertEqual(len(d['candidats']),192)
                self.assertTrue(all(0<=h<=vscore<=b<=1 for _,h,b,vscore in d['candidats']))
                self.assertEqual(segment_photo(p,features[k],d,labels),detail['segments_par_photo'][k])
            self.assertEqual(segment_totals(list(detail['segments_par_photo'].values())),detail['segments_par_strate'])
            self.assertEqual(detail['segments_par_strate']['ensemble'],self.summary['poids'][name]['segments'])

    def test_decision_et_denominateurs_nuls(self):
        b={k:10 for k,_,_ in CRITERIA}
        lim=dict(zip(b,[4,4,9,9],strict=True))
        self.assertTrue(decision(criteria(b,lim)))
        self.assertFalse(decision(criteria(b,{**lim,'strict_1a1':8})))
        z={k:0 for k in b}
        metrics=criteria(z,z)
        self.assertTrue(all(c['atteint'] is None for c in metrics.values()))
        self.assertFalse(decision(metrics))
        qualified=[n for n,r in self.summary['poids'].items() if decision(r['segments']['criteres'])]
        self.assertEqual(len(qualified),self.summary['poids_qualifies'])
        self.assertEqual(len(qualified)>=self.summary['minimum'],self.summary['succes'])
        self.assertFalse(self.summary['succes'])

    def test_seuils_contacts_courbes_et_tables(self):
        nums=('known','matched','proposals','unmatched','false','unresolved','silent','empty_with_points')
        with (LAB/'mesures/courbes.csv').open() as f:
            curves={(r['poids'],r['bras'],r['strate'],float(r['seuil'])):r for r in csv.DictReader(f)}
        for name,r in self.summary['poids'].items():
            item=read(LAB/'mesures'/(name+'.json'))['contacts']
            inherited=read(f'r13/mesures/{name}.json')['contacts']['B']['historique']['seuil']
            for arm, points in r['contacts'].items():
                self.assertEqual(points['historique']['seuil'],inherited)
                self.assertLessEqual(points['local_R9']['unmatched'],2)
                for op,point in points.items():
                    full=item[arm][op]
                    self.assertEqual(point['unmatched'],point['false']+point['unresolved'])
                    self.assertEqual(point['proposals'],point['matched']+point['unmatched'])
                    self.assertEqual(point['known'],18)
                    self.assertEqual(full['all']['photos'],11)
                    strata={'ensemble':full['all'],'empty':full['empty'],'worn':full['worn'],'grouped':full['grouped'],
                            **{'source/'+s:g for s,g in full['per_group'].items()}}
                    for group,data in strata.items():
                        row=curves[name,arm,group,point['seuil']]
                        self.assertEqual([int(row[k]) for k in nums],[data[k] for k in nums])
            self.assertEqual(item['B']['couverture'],item['V']['couverture'])
        with TemporaryDirectory() as tmp:
            path=Path(tmp)/'tableaux.md'
            render(LAB/'mesures',path)
            self.assertEqual(path.read_bytes(),(LAB/'tableaux.md').read_bytes())
