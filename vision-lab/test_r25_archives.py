"""Vérification des preuves R25 depuis le seul dépôt, sans appel réseau neuronal."""
from collections import Counter
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import tempfile
import unittest

import cv2 as cv
import numpy as np

from r19.donnees import fingerprint, read
from r25.analyser import analyze
from r25.commun import ROOT, REUSE, captures, historical, tag
from r25.geometrie import geometry
from r25.planches import render as sheets
from r25.profils import render as profiles
from r25.tableaux import render as tables
from test_r23_archives import svg_pixels_and_vectors


def check_record(test,a,b,path,category):
    """Tolérances au dernier chiffre publié, uniquement pour les champs annoncés."""
    if a==b:
        return
    test.assertIs(type(a),type(b),path)
    if isinstance(a,dict):
        test.assertEqual(a.keys(),b.keys(),path)
        for k in a:
            check_record(test,a[k],b[k],(*path,k),category)
    elif isinstance(a,list):
        test.assertEqual(len(a),len(b),path)
        for j,(x,y) in enumerate(zip(a,b,strict=True)):
            check_record(test,x,y,(*path,j),category)
    else:
        places=None
        if category=='composantes' and any(k in path for k in ('centre','valeurs_propres','direction','t_min','t_max')):
            places=9
        if category in ('geometrie','sondes') and 'sondes' in path:
            col=path[-1]
            if (category=='geometrie' and col in (1,2,4,5,6)) or (category=='sondes' and col not in (0,2)):
                places=6
        if category=='diagnostic-archives.json' and 'cristina_min_sans_angle' in path:
            places=6
        if places is None or not isinstance(a,float):
            test.assertEqual(a,b,path)
        else:
            test.assertLessEqual(abs(Decimal(str(a))-Decimal(str(b))),Decimal(1).scaleb(-places),path)


class ArchivesR25(unittest.TestCase):
    def test_rejouer_toutes_les_geometries_les_traces_et_les_planches(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)
            geometry(out)
            analyze(out,ROOT,out,portable=True)
            tables(ROOT,out)
            sheets(ROOT,out)
            profiles(ROOT,out)
            expected=[ROOT/'diagnostic-archives.json',ROOT/'references-sondes.json',ROOT/'bilan-identites.json',
                      ROOT/'synthese.json',ROOT/'tableaux.md',ROOT/'transitions.md']
            for name in ('geometrie','sondes','composantes','identites','traces','figures'):
                expected.extend(p for p in (ROOT/name).rglob('*') if p.is_file())
            paths={p.relative_to(ROOT) for p in expected}
            self.assertEqual(paths,{p.relative_to(out) for p in out.rglob('*') if p.is_file()})
            self.assertEqual(sum(p.suffix=='.svg' for p in paths),20)
            for rel in sorted(paths):
                pub,new=ROOT/rel,out/rel
                if rel.suffix=='.svg':
                    self.assertEqual(svg_pixels_and_vectors(new.read_bytes()),
                                     svg_pixels_and_vectors(pub.read_bytes()),str(rel))
                elif pub.read_bytes()!=new.read_bytes():
                    self.assertEqual(rel.suffix,'.json',str(rel))
                    check_record(self,read(new),read(pub),(str(rel),),rel.parts[0])

    def test_budget_empreintes_et_plans_des_douze_requetes(self):
        config=read(ROOT/'configuration.json')
        for name,h in config['empreintes_gel'].items():
            self.assertEqual(fingerprint(Path(name)),h,name)
        ledger=read(ROOT/'execution.json')
        self.assertEqual([ledger[k] for k in ('appels','reemplois','ajustements','lectures_developpement','lectures_test')],
                         [21,1,0,0,0])
        self.assertFalse(ledger['gradient'])
        archive=read(ROOT/'parite.json')
        self.assertTrue(archive['parite_complete'])
        self.assertEqual(archive['appels_modele_pour_parite'],0)
        inv=read(ROOT/'cartes/inventaire.json')
        self.assertEqual(set(inv),{tag(*c) for c in captures()})
        local=read(ROOT/'inventaire-local.json')['fichiers']
        self.assertEqual(len(local),35)
        self.assertTrue(all(f['octets']>0 and len(f['sha256'])==64 for f in local))
        for case,r in zip(captures(),ledger['cas'],strict=True):
            arm,s,k=case
            name=tag(*case)
            proof=archive['cas'][name]
            self.assertTrue(proof['parite_exacte'])
            self.assertEqual((r['bras'],r['graine'],r['photo'],r['appel_modele']),(*case,case!=REUSE))
            self.assertEqual(proof['capture'],r['sha256'])
            self.assertIn((r['sha256'],r['octets']),{(i['sha256'],i['octets']) for i in local})
            self.assertEqual(proof['poids'],config['poids'][f'{arm}-{s}']['sha256'])
            if case==REUSE:
                self.assertEqual(proof['capture'],config['reemploi']['sha256'])
            pred=read(historical(arm,s)/'predictions.json')[k]
            self.assertEqual(sha256(json.dumps(pred,sort_keys=True,ensure_ascii=False,allow_nan=False,
                                               separators=(',',':')).encode()).hexdigest(),proof['sorties_json'])
            png=historical(arm,s)/'masques'/(k+'.png')
            bits=cv.imread(str(png),cv.IMREAD_UNCHANGED)
            self.assertEqual(fingerprint(png),proof['sha256_png_archive'])
            self.assertEqual(sha256(bits.astype('<u2').tobytes()).hexdigest(),proof['masques_uint16'])
            shape=tuple(config['photos'][k]['grille'][::-1])
            self.assertEqual(len(inv[name]),12)
            for q,asset in enumerate(inv[name]):
                f=ROOT/asset['fichier']
                self.assertEqual((asset['q'],asset['octets'],asset['sha256']),(q,f.stat().st_size,fingerprint(f)))
                with np.load(f,allow_pickle=False) as raw:
                    for field in ('axe','masque'):
                        plane=raw[field]
                        self.assertEqual(plane.shape,shape)
                        self.assertEqual(plane.dtype,np.dtype('<f4'))
                        self.assertTrue(np.isfinite(plane).all())
                        self.assertEqual(sha256(plane.tobytes()).hexdigest(),asset[field+'_f32'])
                    np.testing.assert_array_equal(raw['masque']>=0,((bits>>q)&1)!=0)

    def test_comptages_et_extremites_de_la_chaine(self):
        rows=read(ROOT/'bilan-identites.json')
        archive=read(ROOT/'diagnostic-archives.json')
        self.assertEqual(sum(v['cellules_verifiees'] for v in archive['etats'].values()),2232)
        self.assertEqual(Counter(r['statut'] for r in rows),
                         dict(pertes=13,gains=5,conserves=27,absents_deux=13))
        self.assertEqual(sum(len(r['concurrence']) for r in rows if 'limite' in r),1)
        pca=[r for r in rows if r.get('limite')=='ligne_PCA']
        self.assertEqual((len(pca),len({r['identite'] for r in pca}),len({r['provenance'] for r in pca})),(8,7,4))
        sample=read(ROOT/'identites/bloc8-9302-4169106197.json')['identites']['4169106197:1']
        for q in sample['requetes']:
            self.assertEqual((q['comptes']['taille'],q['comptes']['elongation'],q['comptes']['oriente']),(21,0,0))
        zig=[r for r in rows if r['identite']=='18105652008:2'][0]
        self.assertEqual(zig['triplets'],dict(dice=None,bloc8=8))
