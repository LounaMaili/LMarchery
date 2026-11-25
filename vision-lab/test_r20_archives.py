"""Contrôles du registre réalisé et des appariements, à partir des seules archives train."""
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np

from r14.mesurer import maximum
from r19.donnees import fingerprint, read
from r20.champs import CHANNELS
from r20.corpus import ROOT
from r20.rejouer import replay


class ArchivesR20(unittest.TestCase):
    def test_rejouer_les_mesures_et_le_premier_succes(self):
        with TemporaryDirectory() as temp:
            summary=replay(ROOT/'archives',Path(temp))
            self.assertEqual(summary,read(ROOT/'mesures/synthese.json'))
            self.assertEqual(list(summary['A']),['9301','9302','9303'])
            self.assertEqual([v['triplets'] for v in summary['A'].values()],[2,1,1])
            self.assertEqual([(i,b['triplets']) for i,b in summary['B'].items()],[('0',0),('96',2)])
            for state in ('A-9301','A-9302','A-9303','B-0000','B-0096'):
                name=state+'.json'
                old=read(ROOT/'mesures'/name)
                new=read(Path(temp)/'mesures'/name)
                if state.startswith('A'):
                    self.assertEqual(len(old['par_photo']),9)
                    rows=[(v,new['par_photo'][k]) for k,v in old['par_photo'].items()]
                else:
                    rows=[(old,new)]
                for left,right in rows:
                    self.assertEqual(left['identites'],right['identites'])
                    self.assertEqual(left['sorties'],right['sorties'])
                    self.assertEqual(len(right['sorties']),12)
                    for k,cells in left['paires_par_identite'].items():
                        for a,b in zip(cells,right['paires_par_identite'][k],strict=True):
                            self.assertEqual(a['admissible_conjoint'],b['admissible_conjoint'])
                            self.assertEqual(a['intersection'],b['intersection'])
                            self.assertEqual(a['sondes_axe'],b['sondes_axe'])

    def test_cardinalite_independante_depuis_les_valeurs_brutes(self):
        caps=read(ROOT/'plafonds.json')
        config=read(ROOT/'configuration.json')
        files=sorted((ROOT/'mesures').glob('[AB]-*.json'))
        self.assertEqual(len(files),5)
        for path in files:
            result=read(path)
            for photo,r in result.get('par_photo',{config['photo_B']:result}).items():
                outputs=r['sorties']
                self.assertEqual(len(outputs),12)
                for mode,field in [('triplets','conjoint_q'),('triplets_au_seuil','conjoint_au_seuil_q')]:
                    edges=[]
                    owners=[]
                    for name,info in r['identites'].items():
                        if not info['complete']:
                            self.assertIsNone(info[field])
                            continue
                        cap=caps[photo]['instances'][name]
                        good=set()
                        for d in r['paires_par_identite'][name]:
                            pass_mask=(10*d['intersection']*cap['denominateur_dice']>=
                                       9*cap['intersection_optimale']*(cap['positifs_natifs']+d['aire_evaluable_sortie']))
                            if pass_mask and d['sondes_axe']>=11 and d['erreur_entree_px704']<=10:
                                if mode=='triplets' or outputs[d['q']]['score']>=r['seuil']:
                                    good.add(d['q'])
                        edges.append(good)
                        if info[field] is not None:
                            self.assertIn(info[field],good)
                            owners.append(info[field])
                    self.assertEqual(len(owners),len(set(owners)))
                    self.assertEqual(maximum(edges),len(owners))
                    self.assertEqual(len(owners),r['bilan'][mode])

    def test_integrite_champs_complets_et_empreintes(self):
        for step in (0,96):
            folder=ROOT/'archives/B'/f'{step:04d}'
            control=read(folder/'controle.json')
            similar=read(folder/'similarites.json')
            self.assertEqual(len(similar['paires']),66)
            peaks=[]
            for q,info in enumerate(control['champs']):
                path=folder/'champs'/info['fichier']
                self.assertEqual(q,info['q'])
                self.assertEqual(path.stat().st_size,info['octets'])
                self.assertEqual(fingerprint(path),info['sha256'])
                with np.load(path,allow_pickle=False) as raw:
                    self.assertEqual(set(raw.files),set(CHANNELS))
                    planes=np.stack([raw[c] for c in CHANNELS])
                self.assertEqual(planes.shape,(3,256,256))
                self.assertEqual(planes.dtype,np.float32)
                self.assertTrue(np.isfinite(planes).all())
                self.assertEqual(sha256(planes.astype('<f4').tobytes()).hexdigest(),info['sha256_plans_float32_le'])
                peaks.append(int(np.argmax(planes[2])))
            self.assertEqual(len(peaks),12)
            self.assertEqual(len(set(peaks)),similar['coordonnees_distinctes'])
            for pair in similar['paires']:
                self.assertEqual(peaks[pair['q']]==peaks[pair['r']],pair['entree']['meme_maximum'])
