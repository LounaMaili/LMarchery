"""Contrôles du corpus public R18, sans photo originale ni poids."""
from hashlib import sha256
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import cv2 as cv
import numpy as np

from r14.mesurer import read
from r18.bilan import generate as review
from r18.figurer import render as chart
from r18.supervision import ROOT, admit, cut_axis, generate, paint


def area2(a,b,c):
    return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])


def intersects(a,b,c,d):
    for i in (0,1):
        if max(a[i],b[i]) < min(c[i],d[i]) or max(c[i],d[i]) < min(a[i],b[i]):
            return False
    return area2(a,b,c)*area2(a,b,d)<=0 and area2(c,d,a)*area2(c,d,b)<=0


class AnnotationR18Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scope=read(ROOT/'perimetre.json')['acquisitions']
        cls.photos={p['photo']:read(ROOT/'annotations'/(p['photo']+'.json')) for p in cls.scope}
        cls.valid={p['photo']:p for p in read(ROOT/'validites.json')['vues']}
        cls.sources={p['photo']:p for p in read(ROOT/'sources.json')}

    def test_perimetre_partitions_provenances_identites_et_references(self):
        old=read('r17/acquisitions.json')
        self.assertEqual((20,28,24,4),(len(self.scope),sum(len(p['prioritaires_R17']) for p in self.scope),
            sum(len(p['instances_R17']) for p in self.scope if p['partition']=='dev_explore_R16'),
            sum(p['role']=='temoin_vide' for p in self.scope)))
        ids=set()
        provenance={}
        for p in self.scope:
            k=p['photo']; doc=self.photos[k]; ancestors=old[k]
            self.assertEqual(ancestors['usage_R17'],doc['partition'])
            self.assertEqual(ancestors['provenance'],doc['provenance'])
            self.assertEqual(ancestors['supports'],doc['supports'])
            self.assertEqual(provenance.setdefault(doc['provenance'],doc['partition']),doc['partition'])
            self.assertEqual(sorted(t['instance_R17'] for t in doc['instances'] if t['instance_R17']),sorted(p['instances_R17']))
            self.assertEqual([t['indice'] for t in doc['instances']],list(range(1,len(doc['instances'])+1)))
            self.assertTrue(set(t['instance'] for t in doc['instances']).isdisjoint(ids))
            ids.update(t['instance'] for t in doc['instances'])
            self.assertEqual(sorted(t['instance'] for t in doc['instances'] if t['prioritaire']),sorted(p['prioritaires_R17']))
            self.assertEqual(doc['dimensions'],p['dimensions_travail'])
            actual=[r['polygone'] for r in doc['regions_ignorees'] if r['id'].startswith('R17-')]
            self.assertEqual(actual,[[[x,y],[u,y],[u,v],[x,v]] for (x,y),(u,v) in ancestors['regions_ignorees']])
        self.assertEqual(len(ids),66)

    def test_transformations_exif_6_et_aller_retour(self):
        for p in self.scope:
            v=self.sources[p['photo']]
            self.assertEqual(v['sha256_octets_source'],p['sha256_source'])
            self.assertTrue(v['parite_pixels_reference'])
            w,h=p['dimensions_stockees']; W,H=p['dimensions_travail']
            raw=np.array([[0.,0.],[w-1.,0.],[0.,h-1.],[w-1.,h-1.],[w*.62,h*.77]])
            oriented=np.array([[h-1-y,x] for x,y in raw]) if p['exif']==6 else raw
            dim=[h,w] if p['exif']==6 else [w,h]
            expected=(oriented+.5)*np.array([W,H])/dim-.5
            grid=np.column_stack((raw,np.ones(len(raw)))).T
            result=np.array(v['oriente_vers_travail'])@np.array(v['stocke_vers_oriente'])@grid
            np.testing.assert_allclose(result[:2].T,expected,atol=1e-9)
            np.testing.assert_allclose(np.array(v['travail_vers_stocke'])@result,grid,atol=1e-9)
        self.assertEqual([p['photo'] for p in self.scope if p['exif']!=1],['rf-key-0ijARYt7fTFFlaDVSrdA'])

    def test_contours_simples_bornes_et_champs(self):
        for doc in self.photos.values():
            w,h=doc['dimensions']
            shapes=[r['polygone'] for r in doc['regions_ignorees']]+doc['fond_explicite']
            for t in doc['instances']:
                shapes.extend(t['polygones_observes'])
                for v in [t['entree']['xy_lu'],*(pt for line in t['axes_observes'] for pt in line)]:
                    if v is not None:
                        self.assertTrue(0<=v[0]<w and 0<=v[1]<h and np.isfinite(v).all())
                for poly,axis in zip(t['polygones_observes'],t['axes_observes'],strict=True):
                    self.assertEqual(len(poly),2*len(axis))
                    np.testing.assert_allclose((np.array(poly[:len(axis)])+poly[len(axis):][::-1])/2,axis,atol=.00051)
            for poly in shapes:
                with self.subTest(photo=doc['photo']):
                    pts=np.asarray(poly);n=len(pts)
                    self.assertEqual(len(set(map(tuple,poly))),n)
                    self.assertTrue(np.isfinite(pts).all() and np.all(pts>=0) and np.all(pts<=np.array([w-1,h-1])))
                    self.assertGreater(cv.contourArea(pts.astype(np.float32)),0)
                    for i in range(n):
                        for j in range(i+2,n):
                            if i==0 and j==n-1:
                                continue
                            self.assertFalse(intersects(poly[i],poly[(i+1)%n],poly[j],poly[(j+1)%n]))

    def test_inconnu_entrees_et_exclusions_par_tache(self):
        for k,p in self.photos.items():
            mask=cv.imread(str(ROOT/'masques'/(k+'.png')),cv.IMREAD_GRAYSCALE)
            row=self.valid[k]
            self.assertEqual(list(mask.shape[::-1]),p['dimensions'])
            self.assertEqual(sha256(mask.tobytes()).hexdigest(),row['carte']['sha256_plan_uint8'])
            if p['role']=='temoin_vide':
                self.assertTrue(np.all(mask==0))
            else:
                self.assertGreater(np.count_nonzero(mask==255),0)
                neg=np.zeros(mask.shape,bool)
                for q in p['fond_explicite']:
                    paint(neg,q,True)
                self.assertFalse(np.any((mask==0)&~neg))
            for t,v in zip(p['instances'],row['instances'],strict=True):
                self.assertEqual(np.count_nonzero(mask==t['indice']),v['pixels_masque_valides'])
                self.assertEqual(v['entree_valide'],v['entree_supervisee'] is not None)
                self.assertEqual(v['axe_valide'],bool(v['axes_supervises']))
                if v['etat_entree']!='visible':
                    self.assertIsNone(v['entree_supervisee'])
            for reg in p['regions_ignorees']:
                if 'masque' in reg['taches']:
                    excluded=np.zeros(mask.shape,bool)
                    paint(excluded,reg['polygone'],True)
                    self.assertTrue(np.all(mask[excluded]==255))
        ref=self.valid['rf-key-0ijARYt7fTFFlaDVSrdA']['instances'][5]
        self.assertTrue(ref['entree_valide'])
        self.assertFalse(ref['masque_valide'])
        self.assertTrue(any(v['masque_valide'] and not v['entree_valide'] for p in self.valid.values() for v in p['instances']))

    def test_decoupage_aux_bords_et_conflit_entre_instances(self):
        excluded=np.zeros((5,9),bool);excluded[2,3:6]=True
        pieces=cut_axis([[0.,2.],[8.,2.]],excluded)
        np.testing.assert_allclose(pieces,[[[0,2],[2.5,2]],[[5.5,2],[8,2]]])
        doc=read(ROOT/'annotations'/'13-01.json')
        other=dict(doc['instances'][0],indice=2,instance='autre',entree={'etat':'occultee','xy_lu':None})
        doc['instances'].append(other)
        mask,row=admit(doc)
        self.assertGreater(row['pixels']['conflits'],0)
        self.assertFalse(np.isin(mask,[1,2]).any())
        self.assertFalse(any(t['axe_valide'] for t in row['instances']))
        self.assertEqual([t['entree_valide'] for t in row['instances']],[True,False])
        # Un trait lu indépendamment reste utilisable en l'absence de masque.
        doc['instances']=[dict(other,polygones_observes=[],type_axe='trait_lu_independamment')]
        _,row=admit(doc)
        self.assertTrue(row['instances'][0]['axe_valide'])
        self.assertFalse(row['instances'][0]['masque_valide'])

    def test_rejeu_public_parite_numerique_et_corrections(self):
        with TemporaryDirectory() as temp:
            out=Path(temp)
            generate(out);review(out);chart(out)
            for name in ('validites.json','effectifs.json','tableaux.md','compatibilite.json','champs-exclus.json','champs-exclus.md','figures/bilan.svg'):
                self.assertEqual((out/name).read_bytes(),(ROOT/name).read_bytes(),name)
        old=read('r17/corrections-v2.json')['corrections']
        for before,now in zip(old,read(ROOT/'corrections.json')['decisions'],strict=True):
            self.assertEqual((before['avant'],before['propose']),(now['historique'],now['proposition_R17']))
            self.assertEqual(now['valeur_R18'],now['proposition_R17'])
        compat=read(ROOT/'compatibilite.json')
        self.assertEqual((2,127),(compat['verifiees'],compat['non_verifiees']))
        for r in compat['exemples']:
            self.assertAlmostEqual(r['iou'],r['intersection_px']/r['union_px'])

    def test_galerie_empreintes_cadrage_et_calques(self):
        views=read(ROOT/'figures/index.json')
        script=(ROOT/'figures/calques.js').read_text().removeprefix('const R18 = ').removesuffix(';\n')
        layers=json.loads(script)
        self.assertEqual(layers['vues'],views)
        self.assertEqual(len(views),20+66+2)
        for v in views:
            path=ROOT/'figures'/v['fichier']
            self.assertEqual(sha256(path.read_bytes()).hexdigest(),v['sha256'])
            h,w=cv.imread(str(path),cv.IMREAD_GRAYSCALE).shape
            x,y,W,H=v['pixels']
            self.assertTrue(0<=x<x+W<=w and 0<=y<y+H<=h)
            self.assertGreater(abs(np.linalg.det(np.array(v['travail_vers_panneau'])[:,:2])),1e-8)
        for k,layer in layers['photos'].items():
            mask=np.full(tuple(self.photos[k]['dimensions'][::-1]),255,np.uint8)
            spans=[(0,layer['fond'])]+[(s['i'],s['admis']) for s in layer['sujets']]
            for val,rects in spans:
                for x,y,w,h in rects:
                    mask[y:y+h,x:x+w]=val
            self.assertEqual(sha256(mask.tobytes()).hexdigest(),self.valid[k]['carte']['sha256_plan_uint8'])
        self.assertEqual(self.photos['fl51204504370']['emprise_diffusee'],[375,340,645,425])


if __name__=='__main__':
    unittest.main()
