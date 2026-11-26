"""Revue R27 sans Torch. Les tests Mask R-CNN exécutés sont dans r27.barriere."""
import gzip
import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from r27.archiver import archive_logs, compact_log
from r27.bilan import NUM, ROOT, SEEDS, read, replay
from r27.diagnostic import diagnose
from r27.figurer import figures
from r27.mesurer import matching, measure


class RevueR27(unittest.TestCase):
    def test_native_false_unknown_and_two_assignments(self):
        mask=np.full((18,18),255,np.uint8)
        mask[0:6,0:9]=0
        mask[1:3,1:3]=1
        mask[1:3,4:6]=4
        bits=np.zeros(mask.shape,np.uint16)
        for idx,q in [(1,0),(1,4),(4,4),(1,5)]:
            bits[mask==idx]|=1<<q
        bits[12,12]|=(1<<0)|(1<<2)
        bits[4,4]|=1<<1
        cap={n:dict(positifs_natifs=4,intersection_optimale=4,denominateur_dice=8,dice_max=1.)
             for n in ('A','B')}
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            cv2.imwrite(str(path/'m.png'),mask)
            targets={'A':dict(indice=1,boite_xyxy=[1,1,3,3],entree_valide=True,etat_entree='visible'),
                     'B':dict(indice=4,boite_xyxy=[4,1,6,3],entree_valide=False,etat_entree='inconnue')}
            spec=dict(masque=str(path/'m.png'),instances=targets,provenance='exemple',supports=['a'],
                      conditions=[],inventaire_R21_complet=False)
            rpn=np.array([targets[n]['boite_xyxy'] for n in targets],np.float32)
            boxes=np.tile(rpn[0],(6,1))
            np.savez(path/'s.npz',masques=bits,boites=boxes,rpn_boites=rpn,scores=np.linspace(.8,.2,6),
                     propositions=np.zeros(6,int),rpn_logits=np.array([1.,0.]),rpn_ancres=np.arange(2))
            row=measure(path/'s.npz',spec,cap)
            self.assertEqual([q['statut'] for q in row['sorties']],
                             ['apparie','faux_fond_verifie','non_resolu','vide','fusion','dedoublement'])
            counts=row['effectifs']
            self.assertEqual([counts[k] for k in ('rpn_50','boites_50','admis','separes','non_apparies',
                                                  'sorties_fusionnees','identites_dedoublees')],[2,1,1,1,5,1,1])
            self.assertEqual(row['identites']['A']['iou'][0],1.)
        ids,_,a,s=matching(np.zeros((2,0),int),['A','B'],np.array([],int),cap)
        self.assertFalse(a or s)
        self.assertTrue(all(x['dice_attribue']==0 for x in ids.values()))

    def test_replay_archived_measurements_and_figures(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)
            replay(out)
            diagnose(out)
            for p in out.glob('*.json'):
                self.assertEqual(p.read_bytes(),(NUM/p.name).read_bytes(),p.name)
            figures(out,NUM)
            for p in out.glob('*.svg'):
                self.assertEqual(p.read_bytes(),(ROOT/'figures'/p.name).read_bytes(),p.name)

    def test_journals_budget_and_frozen_state(self):
        gate=read(ROOT/'barriere.json')
        self.assertEqual(len(set(gate['sha256_initiaux'])),1)
        self.assertEqual(gate['tests'],7)
        self.assertEqual(gate['tenseurs_source'],312)
        registry=read(ROOT/'sorties/index.json')
        self.assertEqual(registry['lectures_reelles'],99)
        states=read(NUM/'courbes.json')
        self.assertEqual(states['9301']['0'],states['9302']['0'])
        self.assertEqual(states['9302']['0'],states['9303']['0'])
        opt=read(NUM/'optimisation.json')
        draws=read('r21/tirages.json')
        with tempfile.TemporaryDirectory() as tmp:
            for seed in SEEDS:
                with gzip.open(NUM/f'echantillonnage-{seed}.jsonl.gz','rt') as handle:
                    rows=[compact_log(json.loads(line)) for line in handle]
                self.assertEqual(len(rows),3 if seed==9301 else 768)
                self.assertEqual({r['sha256_geles'] for r in rows},{gate['sha256_geles']})
                self.assertTrue(all(all(r[k]==v for k,v in draw.items()) for r,draw in zip(rows,draws[str(seed)])))
                self.assertEqual(json.loads(json.dumps(archive_logs(Path(tmp),seed,rows))),opt[str(seed)])
                name=f'pas-{seed}.jsonl'
                self.assertEqual((Path(tmp)/name).read_bytes(),(NUM/name).read_bytes())
        result=read(NUM/'decision.json')
        self.assertIsNone(result['GO_capacite'])
        self.assertIsNone(result['graines']['9301']['conforme'])
        self.assertEqual(result['graines_completes'],2)


if __name__=='__main__':
    unittest.main()
