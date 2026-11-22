import importlib.util
import unittest

import numpy as np

from r7.acquire import selection
from r7.prepare import geometry, project

HAS_MODEL = all(importlib.util.find_spec(name) for name in ("torch", "torchvision"))


class CorpusTests(unittest.TestCase):
    def test_deterministic_sequences_never_split_or_reuse_r6(self):
        files=[f"{seq}/{i:02}.{ext}" for seq in range(25) for i in range(8)
               for ext in (("png","json") if i else ("png",))]
        files.remove("9/01.json")
        selected=selection(files)
        self.assertEqual(selected,selection(list(reversed(files))))
        self.assertEqual(len(selected),300)
        self.assertFalse(any(p.startswith(("9/","10/")) for p in selected))

    def test_doubled_point_and_ambiguous_label(self):
        poly=dict(label="arrow",shape_type="polygon",points=[[15,15],[22,16],[45,45]])
        circle=dict(label="impact",shape_type="circle",points=[[15,15],[16,16]])
        src=dict(imagePath="01.png",imageWidth=64,imageHeight=64,shapes=[poly,circle,circle])
        self.assertEqual(geometry(src,"01.png",[64,64])[1:3],([15.,15.],"source_candidate"))
        self.assertEqual(geometry(src,"01.png",[64,64])[-1],1)
        src["shapes"].append(circle|dict(points=[[21,17],[20,17]]))
        self.assertIsNone(geometry(src,"01.png",[64,64])[1])
        with self.assertRaisesRegex(ValueError,"image_label_mismatch"):
            geometry(src,"02.png",[64,64])

    def test_pixel_center_round_trip(self):
        original=[[11.25,492.3],[368.5,12.75]]
        scale=np.array([.37,.369])
        np.testing.assert_allclose(project(project(original,scale),1/scale),original)


@unittest.skipUnless(HAS_MODEL,"R7 CPU learning environment required")
class LearningTests(unittest.TestCase):
    def test_unlabelled_shaft_is_ignored_and_uncertain_single_has_no_heat_loss(self):
        from r7.train import supervision
        poly=np.array([[15,15],[18,15],[92,90],[89,90]])
        entry=np.array([15,15])
        _,partial=supervision("latest_only",entry,poly,0,0)
        self.assertEqual(partial[:,190,180].tolist(),[0,0])
        self.assertEqual(partial[:,15,15].tolist(),[1,1])
        _,single=supervision("single",None,poly,0,0)
        self.assertEqual(single[0].sum(),0)
        self.assertTrue(single[1].all())
        _,empty=supervision("empty",None,None,0,0)
        self.assertTrue(empty.all())

    def test_two_peaks_and_plateau_count_once_each(self):
        from r7.model import decode
        maps=np.zeros((2,36,36),np.float32)
        maps[0,11,10:12]=.91
        maps[0,11,19]=.9
        peaks,_=decode(maps)
        self.assertEqual([p for p,s in peaks],[[10,11],[19,11]])

    def test_threshold_budget_includes_unresolved_and_all_off(self):
        from r7.evaluate import select
        r=dict(id="p",group="g",oriented_size=[704,704],instances=[dict(entry=[15,15],axis=None)],
               flags=[],ignore_regions=[],complete=False)
        p=dict(peaks=[([15,15],.6),([90,60],.5)],axes=[])
        self.assertEqual(select([(r,p)])[0],.6)
        self.assertEqual(select([(r,p|dict(peaks=[([90,60],.9)]))])[0],2.)
