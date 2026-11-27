"""Contrôles numériques sur archives, sans dépendance envers Torch ou un modèle."""
import tempfile
import unittest
from functools import cache
from pathlib import Path

import cv2
import numpy as np

from r28.commun import ROOT, SEEDS, STEPS, read
from r28.couples import oracle, transition
from r28.mesurer import replay
from r28.partiel import union_counts, union_masks
from r28.planches import color, paint
from r28.publier import publish


class DiagnosticR28(unittest.TestCase):
    def test_borne_inclusive_et_sortie_unique(self):
        caps={n:dict(positifs_natifs=11,intersection_optimale=11,denominateur_dice=22,dice_max=1.)
              for n in ('A','B')}
        for i in range(12):
            out=oracle({'A':[i],'B':[i]},['A','B'],caps)
            self.assertEqual(out['A']['meilleur_dice'],2*i/(11+i))
            self.assertEqual(out['A']['admissibles'],[0] if i>=9 else [])
            self.assertEqual(sum(row['q_attribue'] is not None for row in out.values()),int(i>=9))
        out=oracle({'A':[11,9],'B':[11,8]},['A','B'],caps)
        self.assertEqual((out['A']['q_attribue'],out['B']['q_attribue']),(1,0))

    def test_inconnus_et_union_sur_couvertures_superposees(self):
        labels=np.array([[1,2,0,255,255]],np.uint8)
        bits=np.array([[3,1,2,3,0]],np.uint16)
        ids={key:dict(indice=i,appris=dict(q_separe=q)) for key,i,q in [('A',1,0),('B',2,1)]}
        covered,own=union_masks(labels,bits,ids,'q_separe')
        self.assertEqual(union_counts(labels,covered,own),
                         dict(identite=1,autres_identites=1,fond_certifie=1,ignores=1))
        # Les deux masques activent le même pixel ignoré, qui reste unique en union.
        self.assertEqual(sum(int(np.count_nonzero((bits & (1<<q)!=0) & (labels==255))) for q in [0,1]),2)
        drawn=paint(labels,covered,own)
        for i,tint in enumerate(('propre','autre','fond_actif','inconnu_actif','inconnu')):
            self.assertEqual(tuple(drawn[0,i]),color(tint))

    def test_categorie_sans_suivre_le_rang_q(self):
        def state(iou, admissibles, separes, attribue):
            return dict(meilleur_RPN=dict(iou=.75),meilleure_boite=dict(iou_boite=iou),
                appris=dict(admissibles=admissibles,separes=separes,q_separe=attribue))
        first=state(.7,[2],[2],2)
        self.assertEqual(transition(first,state(.8,[7],[7],7))['categorie'],'stable')
        self.assertEqual(transition(first,state(.5,[],[],None))['categorie'],'seuil_masque_boite_couvrante')
        self.assertEqual(transition(first,state(.49,[],[],None))['categorie'],'bascule_boite')
        self.assertEqual(transition(first,state(.5,[4],[],None))['categorie'],'separation_locale')
        self.assertEqual(transition(first,state(.5,[4],[4],None))['categorie'],'appariement_seul')

    def test_cardinalite_oracle_par_enumeration_et_bornes_par_couple(self):
        # Vérification indépendante de l’algorithme d'affectation par recherche sur les q disponibles.
        matrix=read(ROOT/'mesures/comparaison.json')
        photos=read('r26/train.json')['photos']
        for seed in SEEDS:
            for step in STEPS:
                for photo in photos:
                    state=read(ROOT/'mesures'/'couples'/f'{seed}-{step}-{photo}.json')
                    rows=list(state['identites'].values())
                    edges=[r['oracle']['admissibles'] for r in rows]
                    @cache
                    def maximum(i,used):
                        if i==len(edges):
                            return 0
                        return max([maximum(i+1,used)]+[1+maximum(i+1,used|1<<q) for q in edges[i] if not used & (1<<q)])
                    bound=matrix[f'{seed}-{step}']['par_photo'][photo]['oracle']['cardinalite_un_a_un']
                    self.assertEqual(maximum(0,0),bound)
                    for row in rows:
                        for pair in row['couples']:
                            intersection=pair['pixels_plein']['identite']
                            upper=2*intersection/(row['positifs']+intersection)
                            self.assertEqual(pair['dice_oracle'],upper)
                            for arm in ('appris','plein'):
                                counts=pair['pixels_'+arm]
                                known=counts['identite']+counts['autres_identites']+counts['fond_certifie']
                                score=2*counts['identite']/(row['positifs']+known)
                                self.assertEqual(pair['dice_'+arm],score)
                                self.assertLessEqual(score,upper)

    def test_rejeu_complet_et_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            out=Path(folder)
            replay(out/'mesures')
            publish(out,out/'mesures')
            expected={p.relative_to(ROOT) for p in ROOT.rglob('*') if p.is_file() and
                      (p.suffix in ('.json','.png','.svg','.md') and 'supports' not in p.parts and
                       p.name not in ('protocole.md','reproduire.md'))}
            actual={p.relative_to(out) for p in out.rglob('*') if p.is_file()}
            self.assertEqual(actual,expected)
            for rel in sorted(actual):
                if rel.suffix=='.png':
                    a,b=[cv2.imread(str(p/rel),cv2.IMREAD_UNCHANGED) for p in (out,ROOT)]
                    self.assertTrue(np.array_equal(a,b),str(rel))
                else:
                    self.assertEqual((out/rel).read_bytes(),(ROOT/rel).read_bytes(),str(rel))
            n=read(out/'mesures'/'verification.json')
            self.assertEqual((n['etats_photo'],n['sorties'],n['etats_identite'],n['couples']),(36,432,124,1488))
            figs=read(out/'figures'/'donnees.json')
            self.assertEqual(len(figs['transitions']),n['transitions'])
            self.assertEqual(len(figs['photos']),9)
            print('Rejeu R28 : 36 états, 432 sorties, 1 488 couples, 21 planches ; aucun appel de modèle.')
