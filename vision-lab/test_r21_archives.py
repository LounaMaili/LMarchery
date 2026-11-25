"""Contrôles des preuves sans modèle, accès aux photos ni exécution d’un entraînement."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from r14.mesurer import maximum
from r19.donnees import SEEDS, fingerprint, read
from r21.inventorier import ROOT
from r21.mesures import replay
from r21.plan import ARMS, STEPS
from r21.publier import histories


class ArchivesR21(unittest.TestCase):
    def test_temoin_96_restitue_les_sorties_et_les_poids_historiques(self):
        for s in map(str,SEEDS):
            ref=Path('r20/archives/A')/s
            path=ROOT/'archives/temoin'/s/'96'
            for original in ref.rglob('*'):
                if original.is_file():self.assertEqual(fingerprint(original),fingerprint(path/original.relative_to(ref)))
            parity=read(path/'parite.json')
            self.assertEqual(parity['etat_historique'],parity['etat_repris'])
            self.assertTrue(parity['identique']);self.assertEqual(parity['differents'],[])
            self.assertEqual(parity['parametres_et_buffers'],199)

    def test_rejeu_des_trajectoires_et_de_la_decision(self):
        with TemporaryDirectory() as temp:
            out=Path(temp)
            summary,changes,decision=replay(ROOT/'archives',out)
            self.assertEqual(summary,read(ROOT/'mesures/groupes.json'))
            self.assertEqual(changes,read(ROOT/'mesures/contrastes.json'))
            self.assertEqual(decision,read(ROOT/'decision.json'))
            stats=histories(ROOT/'archives',out)
            self.assertEqual(stats,read(ROOT/'pertes-et-expositions.json'))
            for s in map(str,SEEDS):
                self.assertEqual(stats['candidat'][s]['1-768']['certifiees']['negatifs'],5088)
                self.assertEqual(stats['candidat'][s]['1-768']['partielles']['negatifs'],0)

    def test_cardinalite_independante_par_photo_et_attention_aux_identites(self):
        reg=read(ROOT/'inventaires.json')
        for arm in ARMS:
            for s in SEEDS:
                for step in STEPS:
                    result=read(ROOT/'mesures'/f'{arm}-{s}-{step}.json')
                    for k,r in result['par_photo'].items():
                        m=r['presence_05'];edges=[]
                        for ref in reg[k]['identites']:
                            if not ref['champs']:continue
                            name=ref['instance'];valid=set()
                            for c in r['paires_par_identite'][name]:
                                q=c['q']
                                if r['sorties'][q]['presence']<.5:continue
                                if 'masque' in ref['champs'] and not c['masque_90_pourcent']:continue
                                if 'axe' in ref['champs'] and c['sondes_axe']<11:continue
                                if 'entree' in ref['champs'] and c['erreur_entree_px704']>10:continue
                                valid.add(q)
                            edges.append(valid)
                            self.assertEqual(valid,set(m['requetes_admissibles'][name]))
                            if name in m['appariements']:self.assertIn(m['appariements'][name],valid)
                        represented=len(m['appariements'])
                        self.assertEqual(maximum(edges),represented)
                        self.assertEqual(represented,len(set(m['appariements'].values())))
                        self.assertEqual(represented+len(m['manquees']),reg[k]['actives'])
                        self.assertEqual(represented+len(m['supplementaires']),len(m['sorties_emises']))
        changes=read(ROOT/'mesures/contrastes.json')
        for row in changes.values():
            final=row['effet_regle_final']
            self.assertLess(final['certifiees']['variation']['supplementaires_p05'],0)
            self.assertTrue(final['certifiees']['presence']['perdues'])
            self.assertTrue(final['toutes']['triplets']['perdues'])
            self.assertFalse(row['critere_apparie'])
