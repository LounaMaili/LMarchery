"""Rejeu public de la livraison R19 et contrôle indépendant du point de fonctionnement."""
from collections import Counter
from pathlib import Path
import json
import math
import unittest
from tempfile import TemporaryDirectory

import numpy as np

from r14.mesurer import maximum
from r19.bilan import replay
from r19.donnees import INITIAL, ROOT, SEEDS, fingerprint, read, verify
from r19.mesures import baseline, candidate, dev_refs


class ArchivesR19(unittest.TestCase):
    def test_executions_plans_et_archives(self):
        plans=read(ROOT/'tirages.json')
        inventory=read(ROOT/'inventaire-local.json')['checkpoints']
        self.assertEqual(inventory[0]['sha256'],INITIAL)
        for execution,seed,item in zip(read(ROOT/'executions.json'),SEEDS,inventory[1:],strict=True):
            self.assertEqual((seed,96,11,0),(execution['graine'],execution['pas'],execution['photos_inferees'],execution['anomalies_non_finies']))
            self.assertEqual(item['sha256'],execution['final_sha256'])
            rows=[json.loads(s) for s in (ROOT/'apprentissage'/(str(seed)+'.jsonl')).read_text().splitlines()]
            self.assertEqual(plans[str(seed)],[{k:r[k] for k in ('pas','photo','provenance')} for r in rows])
            self.assertEqual(execution['expositions_photos'],Counter(r['photo'] for r in rows))
            for r in rows:
                self.assertTrue(all(math.isfinite(r[k]) for k in ('total','masque','axe','entree','presence','norme_gradient')))
                self.assertAlmostEqual(r['total'],r['masque']+r['axe']+r['entree']+.25*r['presence'],places=5)
        plates=read(ROOT/'figures/index-planches.json')
        self.assertEqual(len({(p['photo'],p['graine']) for p in plates}),21)
        for item in plates:
            self.assertEqual(fingerprint(ROOT/'figures'/item['fichier']),item['sha256'])

    def test_rejeu_depuis_archives_sans_modele(self):
        with TemporaryDirectory() as folder:
            reports,decision=replay(ROOT/'predictions',Path(folder))
            self.assertEqual(decision,read(ROOT/'decision.json'))
            for system,got in reports.items():
                old=read(ROOT/'mesures'/(system+'.json'))
                # Les distances en double tolèrent les derniers bits de libm, tous les comptes sont entiers.
                for k,p in got['par_photo'].items():
                    for mode in ('selection','couverture'):
                        for a,b in zip(p[mode]['paires'],old['par_photo'][k][mode]['paires'],strict=True):
                            self.assertAlmostEqual(a['distance_px'],b['distance_px'],places=10)
                            a['distance_px']=b['distance_px']
                self.assertEqual(got,old)
                name='courbe-'+system+'.json'
                self.assertEqual(read(Path(folder)/'mesures'/name),read(ROOT/'mesures'/name))

    def test_point_de_fonctionnement_controle_par_cardinalite_independante(self):
        refs=dev_refs(verify())
        for system in ('B',*map(str,SEEDS)):
            out=baseline(refs) if system=='B' else candidate(refs,ROOT/'predictions'/system)
            ranks=sorted(((p['score'],k,p['id'],p['xy']) for k,r in out.items() for p in r['contacts']),reverse=True)
            edges={k:[set() for _ in r['entries']] for k,r in refs.items()}
            best=(0,0,2.)
            seen=0
            from itertools import groupby
            for threshold,block in groupby(ranks,key=lambda x:x[0]):
                for _,k,slot,xy in block:
                    seen+=1
                    for i,truth in enumerate(refs[k]['entries'].values()):
                        if math.dist(xy,truth)<=10:
                            edges[k][i].add(slot)
                matched=sum(maximum(e) for e in edges.values())
                unpaired=seen-matched
                if unpaired>2:
                    break
                best=max(best,(matched,-unpaired,threshold))
            report=read(ROOT/'mesures'/(system+'.json'))
            g=report['par_strate']['ensemble']
            self.assertEqual((g['apparies'],-g['non_appariees'],report['seuil']),best)
