"""Rejouer la filiation, les comptages et le statut des partitions depuis GitHub."""
from collections import Counter
from fractions import Fraction
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from r14.mesurer import read
from r15.corpus import corpus,fingerprint
from r17.figer import freeze
from r17.inventorier import inventory
from r17.mesurer import render

LAB=Path('r17')


class SupervisionR17Tests(unittest.TestCase):
    def test_filiation_geometries_et_instances(self):
        frozen=read(LAB/'cas-geles.json')
        self.assertEqual(freeze(),frozen)
        self.assertEqual((frozen['entrees_initiales'],frozen['geometries'],frozen['liens_cas_instance'],
                          frozen['instances_distinctes']),(28,28,29,27))
        cases=frozen['cas']
        self.assertEqual(len({r['geometrie_sha256'] for r in cases}),28)
        self.assertEqual(sorted(v for v in frozen['par_instance'].values() if len(v)>1),
                         [['R17-22','R17-24'],['R17-26','R17-27']])
        self.assertEqual(len(cases[14]['references']),2)
        train_ids={p['id'] for p in corpus() if p['partition']=='train'}
        self.assertTrue(all((c['affectation_R17']=='train')==(c['photo'] in train_ids) for c in cases))

    def test_bilan_lecture_masses_versions(self):
        cases={r['cas']:r for r in read(LAB/'cas-geles.json')['cas']}
        readings=read(LAB/'lectures.json')['lectures']
        for r in readings:
            c=cases[r['cas']]
            self.assertEqual({v['instance'] for v in r['associations']},{ref['instance'] for ref in c['references']})
            self.assertEqual(r['maintien_exclusion_R16'],r['ensemble']=='exclu_ignore')
            if r['bout']=='entree_inconnue':
                self.assertTrue(all(ref['entree'] is None for ref in c['references']))
        with TemporaryDirectory() as temp:
            path=Path(temp)
            render(path)
            for f in ['bilan.json','tableaux.md']:
                self.assertEqual((path/f).read_bytes(),(LAB/f).read_bytes())
        s=read(LAB/'bilan.json')
        self.assertEqual([s['total'][k] for k in ('confirmee','erreur_demontree','indecidable','exclusions_maintenues')],[22,2,4,4])
        self.assertEqual(sum(Fraction(v['masse_positive_exacte']) for v in s['par_photo'].values()),Fraction(57,4))
        bg=s['par_provenance']['Bernard Gagnon']
        self.assertEqual((bg['fraction_des_positifs_bruts_R16'],bg['fraction_de_la_masse_positive_R16']),('1/2','1/6'))
        fixes=read(LAB/'corrections-v2.json')['corrections']
        self.assertEqual({v['id'] for v in fixes},{r['correction'] for r in readings if r['correction']})
        for v in fixes:
            ref=next(a for a in cases[v['cas']]['references'] if a['instance']==v['instance'])
            self.assertEqual(ref[v['champ']],v['avant'])
            self.assertNotEqual(v['propose'],v['avant'])

    def test_inventaire_sans_generateur_ni_transfert(self):
        with TemporaryDirectory() as tmp:
            p=Path(tmp);inventory(p)
            for n in ('acquisitions','instances','effectifs-instances'):
                self.assertEqual((p/(n+'.json')).read_bytes(),(LAB/(n+'.json')).read_bytes())
        records=read(LAB/'instances.json');photos=read(LAB/'acquisitions.json')
        self.assertEqual(len(photos),195)
        ids=[a['instance'] for a in records]
        self.assertEqual(len(set(ids)),len(ids))
        self.assertEqual(Counter(p['usage_R17'] for p in photos.values()),
                         {'train':162,'dev_explore_R16':11,'dev_explore_anterieur':22})
        masks=read(LAB/'polygones.json')['polygones']
        for a in records:
            ann={v['acquisition'] for v in a['vues_annotees']}
            self.assertFalse(ann & set(a['mentions_seule_dans_la_vue']))
            for v in a['vues_annotees']:
                p=photos[v['acquisition']]
                self.assertEqual((p['usage_R17'],p['provenance']),(a['usage_R17'],a['provenance']))
                for k in v['masques_versions']:
                    m=masks[k]
                    self.assertEqual((m['photo'],m['instance']),(v['acquisition'],a['instance']))
                    self.assertGreaterEqual(len(m['points_travail']),3)
                if v['etat_entree']!='visible':
                    self.assertIsNone(v['entree'])
        t=read(LAB/'effectifs-instances.json')['train']['total']
        self.assertEqual([t[k] for k in ('instances_annotees','occurrences_annotees','avec_axe','avec_masque','entree_visible',
            'axe_et_entree_meme_vue','axe_et_entree_hors_ignore','axe_masque_entree_meme_vue',
            'mentions_sans_geometrie_dans_la_vue','identites_mention_seule')],[168,176,41,129,143,33,30,2,368,7])

    def test_planches_sources_et_masquage(self):
        d=LAB/'figures';idx=read(d/'index.json')
        js=(d/'calques.js').read_text().removeprefix('const planchesR17 = ').strip().removesuffix(';')
        self.assertEqual(json.loads(js),idx)
        self.assertEqual([i['cas'] for i in idx],[r['cas'] for r in read(LAB/'cas-geles.json')['cas']])
        for item in idx+read(d/'details.json'):
            self.assertEqual(fingerprint(d/item['fichier']),item['sha256'])
            self.assertTrue(item['droits']['credit'] and item['droits']['url'] and item['droits']['licence'])
        for item in idx:
            self.assertEqual(set(item),{'cas','photo','fichier','sha256','cadre_detail','contexte','detail',
                                        'segment','references','regions_ignorees','droits'})
            self.assertTrue(all(set(ref)=={'instance','axe','entree','contact_visible','statut_initial'} for ref in item['references']))
        self.assertEqual(len(read(d/'details.json')),6)
