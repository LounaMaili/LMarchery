import importlib.util
import unittest

import numpy as np

from benchmark import maximum_matches
from r3.windows import tiled_maps
from r6.decode import multiple, oracle


class DecoderTests(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("torch"), "R3 CPU environment required")
    def test_one_positive_board_cannot_establish_a_zero_width_recall_interval(self):
        from r6.measure import totals
        row = dict(group="board", present=1, known=1, matched=0, proposals=0, complete=True,
                   errors_px=[], unassigned={}, tubes_known=1, tubes_matched=0, negative=False,
                   close={"3": 0, "5": 0, "10": 0})
        report = totals([row, row])
        self.assertIsNone(report["recall_ci95"])
        self.assertEqual(report["positive_groups"], 1)
        self.assertAlmostEqual(report["any_match_group_upper95_if_zero"], .95)

    def test_two_peaks_on_one_shaft_and_quantized_plateau(self):
        mask = np.zeros((48, 96, 3), np.uint8)
        mask[20:26, 10:82, 1] = 255
        maps = oracle(mask, [{"entry": [15,22]}, {"entry": [22,22]}, {"entry": None}])
        maps[1, 22, 22:24] = 1
        entries = multiple(maps)
        self.assertEqual(len(entries), 2)
        self.assertEqual([e.point.tolist() for e in entries], [[15,22], [22,22]])
        maps[2] = 0
        self.assertEqual(multiple(maps), [])

    def test_dense_candidates_count_once_and_preserve_original_indices(self):
        refs = np.array([[10.,10.], [13.,10.]])
        predicted = np.array([[100.,100.]]*1500 + [[10.1,10.], [12.9,10.]])
        self.assertEqual(set(maximum_matches(refs, predicted, 1)), {(0,1500), (1,1501)})
        predicted[:1500] = [11.,10.]
        self.assertEqual(set(maximum_matches(refs, predicted, 3)), {(0,1500), (1,1501)})

    def test_patch_coordinates_survive_padding_and_overlap(self):
        rng = np.random.default_rng(711)
        for h,w in ((71,85), (163,251)):
            photo = rng.integers(0,256,(h,w,3),np.uint8)
            maps = tiled_maps(photo, lambda batch: batch)
            np.testing.assert_allclose(maps, photo[:, :, ::-1].transpose(2,0,1)/255, atol=2e-7)

    @unittest.skipUnless(importlib.util.find_spec("torch"), "R3 CPU environment required")
    def test_lifting_component_cap_preserves_legacy_abstention_by_default(self):
        from r3.inference import propose
        mask = np.zeros((48,96,3), np.uint8)
        mask[20:26,10:82,1] = 255
        maps = oracle(mask, [{"entry":[15,22]}, {"entry":[40,22]}])
        entries, tubes, reasons = propose(maps)
        self.assertEqual(entries, [])
        self.assertIn("competing_peaks", reasons)
        relaxed, same_tubes, _ = propose(maps, multiple_per_component=True)
        self.assertEqual(len(relaxed), 2)
        np.testing.assert_equal(tubes[0].ends, same_tubes[0].ends)
