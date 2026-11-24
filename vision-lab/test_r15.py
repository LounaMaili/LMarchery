"""Audit R15 en CI depuis les seuls fichiers publics du laboratoire."""
import csv
from collections import Counter
from hashlib import sha256
from pathlib import Path
import unittest

import numpy as np
from r15.caracteristiques import KEYS, NAMES, INPUTS, eligible, widths
from r15.corpus import LAB, SEALED, bank, corpus, counts, fingerprint, gates
from r15.recherche import read


class CorpusWebTests(unittest.TestCase):
    def test_scellement_et_partitions(self):
        seal=read(LAB/'manifeste-scelle.json')
        self.assertEqual(set(seal['pieces']),set(SEALED))
        lines=[]
        for name,info in sorted(seal['pieces'].items()):
            self.assertEqual(info,dict(sha256=fingerprint(name),octets=Path(name).stat().st_size))
            lines.append(f'{info["sha256"]}  {name}\n')
        self.assertEqual(sha256(''.join(lines).encode()).hexdigest(),seal['sha256'])
        partitions=read(LAB/'partitions.json')['partitions']
        rows=corpus()
        self.assertEqual(len({p['sha256_pixels'] for p in rows}),len(rows))
        self.assertEqual(len({p['id'] for p in rows}),195)
        for name,group in partitions.items():
            self.assertEqual([r['photo'] for r in group],[p['id'] for p in rows if p['partition']==name])
        old=read('r14/partitions.json')['photos']
        self.assertEqual([p for p in rows if p['partition']=='dev_explore'],[p for p in old if p['partition']=='dev_explore'])
        fresh=read(LAB/'lectures.json')['photos']
        owners={p['provenance'].casefold() for p in fresh}
        self.assertEqual(len(owners),len(fresh))
        self.assertFalse(owners & {p['provenance'].casefold() for p in old})
        for stratum in {p['strate_affectation'] for p in fresh}:
            subset=[p for p in fresh if p['strate_affectation']==stratum]
            ordered=sorted(subset,key=lambda p:({'rf-key':0,'rf-empty':5,'video-commons':12}.get(p['source']['catalogue'],13),p['source']['url']))
            for i,p in enumerate(ordered):
                self.assertEqual(p['rang_affectation'],i+1)
                self.assertEqual(p['partition'],'dev_indep' if i<2 or i%2 else 'train')

    def test_minima_et_registre_numerique(self):
        totals=read(LAB/'effectifs.json')['partitions']
        for name,result in totals.items():
            rows=[p for p in corpus() if p['partition']==name]
            self.assertEqual(counts(rows,bank()),result['total'])
            for owner,group in result['par_provenance'].items():
                self.assertEqual(counts([p for p in rows if p['provenance']==owner],bank()),group)
        decision=gates(totals,read('r14/portes.json'))
        self.assertEqual(decision,read(LAB/'portes.json'))
        self.assertTrue(decision['deux_portes_franchies'])
        self.assertFalse(decision['entrainement_execute'])
        for r in read(LAB/'figures/valeurs.json'):
            p,k=r['partition'],r['critere']
            self.assertEqual((r['R15'],r['minimum']),(totals[p]['total'][k],decision['partitions'][p]['criteres'][k]['minimum']))
            self.assertEqual(r['R14'],read('r14/portes.json')['partitions'][p]['criteres'][k]['observe'])
        fresh=read(LAB/'lectures.json')['photos']
        for p in fresh:
            w,h=p['taille']
            for t in p['tubes']:
                self.assertEqual(t['contact_visible'],t['entree'] is not None)
                for x,y in t['axe']+([t['entree']] if t['contact_visible'] else []):
                    self.assertTrue(0<=x<w and 0<=y<h)
            if 'empty' in p['conditions']:
                self.assertTrue(p['complet'])
                self.assertEqual((p['regions_ignorees'],p['tubes']),([],[]))
        sources=read(LAB/'sources.json')
        self.assertEqual(sum(s['telecharges'] for s in sources['sources']),sources['uniques']['photos_telechargees']+sources['uniques']['films_telecharges'])
        self.assertEqual(sum(s['retenues'] for s in sources['sources']),len(fresh))
        self.assertEqual(sum(len(p['tubes']) for p in fresh),22)
        self.assertEqual(sum(t['contact_visible'] for p in fresh for t in p['tubes']),19)

    def test_largeur_et_lignee(self):
        line=np.array([[1.,2.],[31.,2.]])
        parallel=line+[0.,4.]
        for second in (parallel,parallel[::-1]):
            self.assertEqual(widths((line,second)),(4.,0.))
        self.assertEqual(widths(None),(None,None))

    def test_sorties_preparees_et_etiquettes_separees(self):
        cfg=read(LAB/'configuration.json')
        result=read(LAB/'caracteristiques/index.json')
        self.assertEqual(result['colonnes_entree'],list(INPUTS))
        self.assertEqual(len(INPUTS),8)
        self.assertEqual(set(NAMES)-set(INPUTS),{result['colonne_documentaire']})
        self.assertEqual(result['nombre_passages'],cfg['budget_passages'])
        self.assertEqual(result['manifeste_sha256'],read(LAB/'manifeste-scelle.json')['sha256'])
        self.assertEqual(cfg['photos'],[p['id'] for p in eligible()])
        self.assertEqual(len(cfg['photos']),25)
        self.assertEqual(set(result['poids']),{Path(w['nom']).stem for w in cfg['poids']})
        sample={(s['photo'],s['rang']) for s in bank()}
        for model in result['poids'].values():
            file=LAB/'caracteristiques'/model['fichier']
            self.assertEqual(fingerprint(file),model['sha256'])
            with file.open() as fh:
                reader=csv.DictReader(fh)
                self.assertEqual(tuple(reader.fieldnames),KEYS+NAMES)
                rows=list(reader)
            self.assertEqual({(r['photo'],int(r['rang_longueur'])) for r in rows if r['prelevement_documentaire']=='1'},sample)
            self.assertEqual(len(rows),len({(r['photo'],r['rang_longueur']) for r in rows}))
            cnt=Counter(r['photo'] for r in rows)
            for p in model['photos']:
                rs=[r for r in rows if r['photo']==p['photo']]
                self.assertEqual(cnt[p['photo']],p['lignes'])
                self.assertEqual(sorted(int(r['rang_sortie_R13']) for r in rs if r['rang_sortie_R13']!=''),list(range(p['sorties_R13'])))
                self.assertIn(p['partition'],('train','dev_indep'))
                self.assertEqual(p['largeur_non_observable'],sum(r['largeur_mediane_px']=='' for r in rs))
            for r in rows:
                self.assertEqual(r['largeur_mediane_px']=='',r['deux_contours']=='0')
                self.assertEqual(r['largeur_mad_px']=='',r['deux_contours']=='0')
                self.assertGreater(float(r['longueur_px']),0)
                for k in ('continuite_longitudinale','moyenne_masque','probabilite_tube_moyenne','ecart_aspect_bouts'):
                    self.assertTrue(0<=float(r[k])<=1)

if __name__=='__main__':
    unittest.main()
