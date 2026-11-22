import importlib.util
import unittest

import numpy as np


def record(name, marks=(), ignore=()):
    return dict(id=name, sha256=name, pool="control", split="train", exhaustive=True,
                group="source", boards=["board"], size=[680,600], marks=list(marks),
                flags=["single"] if marks else ["empty"], ignore=list(ignore))


@unittest.skipUnless(all(importlib.util.find_spec(p) for p in ("torch","torchvision")),
                     "R9 requires the Vision CPU runtime")
class SamplingTests(unittest.TestCase):
    def test_head_labels_and_incomplete_coverage(self):
        from r9.sampling import targets
        from r9.bank import clear_window
        visible = dict(entry=[80,90],polygon=[[50,50],[54,50],[82,90],[78,90]])
        older = dict(entry=[140,100],polygon=[[140,100],[145,100],[190,110],[185,110]])
        row = record("a",[visible,older],[[[205,200],[245,235]]])
        truth,known=targets(row,{},0,0)
        self.assertEqual(truth[0,90,80],1)
        self.assertEqual(truth[0,100,140],1)
        self.assertEqual((truth[0,50,52],truth[1,50,52],known[1,50,52]),(0,1,1))
        self.assertTrue(np.all(known[:,210,230]==0))
        self.assertFalse(clear_window(row,0,0))
        fractional=record("fractional",[],[[[42.9,68.1],[80.7,135.3]]])
        self.assertFalse(clear_window(fractional,81,70))
        self.assertTrue(clear_window(fractional,82,70))
        self.assertFalse(clear_window(row|dict(exhaustive=False),360,280))
        self.assertFalse(clear_window(row|dict(split="dev"),360,280))
        external=record("b",[dict(entry=[90,80],polygon=None)])
        truth,known=targets(external|dict(pool="new"),{"b":[[[200,170],[216,170]]]},0,0)
        self.assertEqual((truth[1,170,206],known[1,170,206],known[1,180,206]),(1,1,0))

    def test_plans_have_identical_positive_slots_and_only_safe_paired_negative_origins(self):
        from r9.bank import clear_window, placements
        from r9.sampling import make_plan,STEPS
        r=record("a",[dict(entry=[20,20])])
        windows=placements(r,[490,400])
        bank=[dict(id="a",group="source",sha256="a",category="tube",index=1,point=[490,400],
                   windows=[list(p) for p in windows])]
        p=make_plan([r],bank,9301)
        self.assertEqual(p,make_plan([r],bank,9301))
        self.assertNotEqual(p,make_plan([r],bank,9302))
        self.assertEqual(len(p),STEPS)
        for batch in p:
            self.assertEqual([v["positive"] for v in batch],[True]*4+[False]*4)
            for entry in batch[4:]:
                self.assertIn(tuple(entry["targeted"]),windows)
                self.assertTrue(clear_window(r,*entry["ordinary"]))
        with self.assertRaises(ValueError):
            make_plan([r|dict(split="test")],bank,9301)

    def test_observed_threshold_counts_ties_and_unresolved_against_whole_photo_budget(self):
        from r9.experiment import threshold
        pos=record("p",[dict(entry=[40,40])])
        unknown=record("u",[],[[[0,0],[90,90]]])
        items=[(pos,[([40,40],.831)]),(unknown,[([20,20],.832)]),(record("a"),[]),(record("b"),[])]
        self.assertEqual(threshold(items),.831)
        items[-1]=(record("b"),[([80,80],.831)])
        self.assertEqual(threshold(items),2.)
