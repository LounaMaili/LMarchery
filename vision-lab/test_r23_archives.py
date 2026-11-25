"""Audit depuis GitHub sans poids, photos sources ni nouvel appel du réseau."""
import math
from pathlib import Path
import tempfile
import unittest

import numpy as np

from r19.donnees import fingerprint, read
from r23.commun import GAGNON, LYNCH, ROOT, cases, measures, tag
from r23.mesures import replay
from r23.presenter import render


class ArchivesR23(unittest.TestCase):
    def test_rejouer_toutes_les_tables_les_figures_et_les_paires(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)
            replay(ROOT,out)
            render(ROOT,out)
            distances=0
            for f in sorted(p for p in out.rglob('*') if p.is_file()):
                published=ROOT/f.relative_to(out)
                if f.parent.name!='mesures' or f.name=='effectifs.json':
                    self.assertEqual(f.read_bytes(),published.read_bytes(),f.name)
                    continue
                actual,ref=read(f),read(published)
                for a,b in zip(actual['requetes'],ref['requetes'],strict=True):
                    for n,i in a['par_identite'].items():
                        x,y=i['distance_pic_px704'],b['par_identite'][n]['distance_pic_px704']
                        if x!=y:
                            self.assertLessEqual(abs(x-y),2*math.ulp(y))
                            self.assertEqual(x<=10,y<=10)
                            i['distance_pic_px704']=y
                            distances+=1
                self.assertEqual(actual,ref,f.name)
            print(f'Rejeu R23 : {distances} distances géométriques dans la tolérance. Autres valeurs exactes.')

    def test_budget_parite_et_calcul_independant_depuis_les_cellules(self):
        config=read(ROOT/'configuration.json')
        for f,digest in config['empreintes'].items():
            self.assertEqual(fingerprint(Path(f)),digest,f)
        ledger=read(ROOT/'execution.json')['passages']
        self.assertEqual([(r['bras'],r['pas'],r['photo']) for r in ledger],list(cases()))
        self.assertEqual([r['numero'] for r in ledger],list(range(1,9)))
        parity=read(ROOT/'parite.json')
        self.assertTrue(parity['parite_complete'])
        specs=read(ROOT/'references.json')
        count=0
        for arm,t,k in cases():
            name=tag(arm,t,k)
            self.assertTrue(parity['etats'][name]['parite'])
            grid=np.array(specs[k]['grille'])
            view=np.array(specs[k]['repere_704'])
            coords=np.indices(tuple(grid[::-1]))[::-1].reshape(2,-1).T
            xy=(coords+.5)*view/grid-.5
            raw=measures(arm,t)[k]
            actual=read(ROOT/'mesures'/(name+'.json'))['requetes']
            for q,row in enumerate(actual):
                with np.load(ROOT/'cartes'/name/f'q{q:02}.npz') as values:
                    z=values['logits'].ravel()
                    p=values['p'].ravel()
                self.assertEqual(int(p.argmax()),row['pic_decode']['index'])
                self.assertEqual(p.max().item(),raw['sorties'][q]['pic_spatial'])
                for n,i in row['par_identite'].items():
                    count+=1
                    disk=((xy-specs[k]['identites'][n]['xy_704'])**2).sum(axis=1)<=100
                    best=i['meilleure_cellule_disque']
                    self.assertEqual(i['cellules_disque'],int(disk.sum()))
                    self.assertEqual(z[disk].max().item(),best['logit'])
                    self.assertEqual(i['masse_disque'],p[disk].sum(dtype=np.float64))
                    self.assertEqual(best['rang_logit'],[1+int((z>best['logit']).sum()),int((z>=best['logit']).sum())])
                    self.assertEqual(i['q_perte'],raw['identites'][n]['affectation_perte_q'])
                    self.assertEqual(i['q_entree_geometrique'],raw['identites'][n]['entree_q'])
                    self.assertEqual(i['sondes_axe'],raw['paires_par_identite'][n][q]['sondes_axe'])
        self.assertEqual(count,624)

    def test_pic_local_de_gagnon_et_couverture_precoce_de_lynch(self):
        rows=read(ROOT/f'mesures/dice-768-{GAGNON}.json')['requetes']
        self.assertEqual(rows[7]['pic_decode']['index'],rows[11]['pic_decode']['index'])
        self.assertGreater(rows[7]['concurrence_11_9']['masse_11'],rows[7]['concurrence_11_9']['masse_9'])
        c=rows[7]['par_identite'][GAGNON+':11']['meilleure_cellule_disque']
        self.assertEqual(c['rang_logit'],[2,2])
        self.assertTrue(c['maximum_local_strict'])
        self.assertIsNone(rows[7]['par_identite'][GAGNON+':11']['q_entree_geometrique'])
        rows=read(ROOT/f'mesures/dice-96-{LYNCH}.json')['requetes']
        c=rows[2]['par_identite'][LYNCH+':4']
        self.assertEqual((c['q_perte'],c['q_entree_geometrique']),(2,10))
        self.assertFalse(c['pic_decode_dans_disque'])
        self.assertTrue(rows[10]['par_identite'][LYNCH+':4']['pic_decode_dans_disque'])
