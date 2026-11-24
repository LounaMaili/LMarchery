from pathlib import Path
import tempfile
import unittest

import cv2 as cv
import numpy as np

from arc_geometry import PROFILE, RADIUS, audit_arcs, samples
from lab_data import read_manifest
from stress_data import generate_stress


class ArcGeometryTest(unittest.TestCase):
    def setUp(self):
        rng = np.random.default_rng(12)
        angles = np.linspace(-137, 138, 12)
        self.calibration = np.vstack((samples(rng, .4, .4, angles), samples(rng, .6, .6, angles+5)))
        self.control = samples(rng, .8, .8, [65, 118, 171, 223, 275])

    def test_independent_controls_allow_well_spread_rings(self):
        result = audit_arcs(self.calibration, self.control, PROFILE, known_front=True)
        self.assertEqual((), result.reasons)
        self.assertIsNotNone(result.fit)
        self.assertAlmostEqual(RADIUS, result.fit.radius_px, delta=2)

    def test_regular_but_wrong_ring_ids_fail_despite_low_residual(self):
        observations = self.calibration.copy()
        observations[:, 2] /= 2
        result = audit_arcs(observations, self.control, PROFILE, known_front=True)
        self.assertIn("independent-rings-disagree", result.reasons)
        self.assertIsNotNone(result.fit)
        self.assertLess(result.fit.rms_residual_px, 2)
        self.assertGreater(result.fit.radius_px, 1.9*RADIUS)

    def test_short_overlapping_sectors_do_not_qualify(self):
        rng = np.random.default_rng(2111)
        angles = np.linspace(-10, 10, 12)
        observations = np.vstack((samples(rng, .4, .4, angles), samples(rng, .6, .6, angles+5)))
        result = audit_arcs(observations, self.control, PROFILE, known_front=True)
        self.assertIn("insufficient-angular-spread", result.reasons)
        self.assertLess(result.fit.rms_residual_px, 2)

    def test_reject_unverified_plane_unknown_ring_and_reused_points(self):
        wrong_id = self.calibration.copy()
        wrong_id[0, 2] = .42
        repeated = np.vstack((self.calibration[0], self.control))
        self.assertEqual(("unverified-front-plane",),
                         audit_arcs(self.calibration, self.control, PROFILE, known_front=False).reasons)
        self.assertEqual(("unknown-ring",), audit_arcs(wrong_id, self.control, PROFILE, known_front=True).reasons)
        self.assertEqual(("need-independent-check-points",),
                         audit_arcs(self.calibration, repeated, PROFILE, known_front=True).reasons)


class HeldOutDatasetTest(unittest.TestCase):
    def test_white_variants_share_test_groups_and_have_no_invented_masked_entry(self):
        cv.setNumThreads(1)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/"holdout"
            generate_stress(root)
            rows = read_manifest(root)
            self.assertEqual(12, len(rows))
            by_group = {}
            for row in rows:
                self.assertEqual("test", row["split"])
                self.assertEqual("white", row["material"])
                by_group.setdefault(row["source_group"], []).append(row)
            self.assertEqual(6, len(by_group))
            for visible, masked in by_group.values():
                self.assertEqual(visible["before_sha256"], masked["before_sha256"])
                self.assertNotEqual(visible["sha256"], masked["sha256"])
                self.assertIsNotNone(visible["arrows"][0]["entry"])
                self.assertEqual("occluded", masked["arrows"][0]["visibility"])
                self.assertIsNone(masked["arrows"][0]["entry"])
