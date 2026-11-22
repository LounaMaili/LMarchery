import unittest

from r10.diagnostic import analyze


class SavedPeakTests(unittest.TestCase):
    def test_ties_and_late_nearer_peak_keep_distinct_reference_status(self):
        row = dict(size=[100, 100], marks=[dict(entry=[40, 40]), dict(entry=None)],
                   exhaustive=False, ignore=[])
        peaks = [([5, 5], .8), ([34, 40], .6), ([10, 10], .6), ([40, 40], .4)]
        result = analyze(row, peaks)
        points = result["points"]
        self.assertEqual([p["rank"] for p in points], [1, 2, 2, 4])
        self.assertEqual([p["before"] for p in points], [True, False, False, False])
        self.assertEqual((points[1]["match050"], points[1]["match"]), (1, None))
        self.assertEqual(points[1]["reference"], "non résolu")
        self.assertEqual(result["entries"][0]["matched_rank"], 4)
        self.assertIsNone(result["entries"][1]["entry"])
        self.assertEqual(result["prefix"]["3"], 1)

    def test_shared_candidate_and_absent_reference(self):
        row = dict(size=[100, 100], marks=[dict(entry=[20, 20]), dict(entry=[26, 20]),
                                          dict(entry=[80, 80])], exhaustive=True, ignore=[])
        result = analyze(row, [([23, 20], .4)])
        self.assertEqual(result["points"][0]["eligible"], [1, 2])
        self.assertEqual(result["prefix"]["96"], 1)
        self.assertIsNone(result["entries"][2]["best_rank"])

    def test_zero_visible_references_and_ignored_region(self):
        row = dict(size=[100, 100], marks=[], exhaustive=True, ignore=[[[2,2],[8,8]]])
        result = analyze(row, [([2, 2], .9), ([10, 10], .31)])
        self.assertIsNone(result["first_rank"])
        self.assertTrue(all(p["examined"] and p["before"] for p in result["points"]))
        self.assertEqual([p["reference"] for p in result["points"]], ["non résolu", "faux établi"])
        self.assertEqual(analyze(row, [])["prefix"]["96"], 0)
