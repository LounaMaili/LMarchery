from pathlib import Path
import tempfile
import unittest

import cv2 as cv
import numpy as np

from benchmark import maximum_matches, tube_matches
from detector import Evidence, LogisticOrientation, Tube, extract, fit_geometry, foreground_pair, proposals, rectify_from_controls
from lab_data import background, fingerprint, homography, paint_shafts, project, read_manifest, render, safe_file, write_json


class VisionTrialTest(unittest.TestCase):
    def test_fletching_orients_to_opposite_end_and_equal_shape_abstains(self):
        ends = np.array([[100., 80.], [400., 240.]])
        confidence = [[1., 1., .8, 1.], [3.8, 1.2, 1.2, 2.]]
        key = Tube(ends, np.array(confidence))
        tie = Tube(ends, np.ones((2, 4)))
        selected, ambiguous = proposals(Evidence([key, tie], None, "pair"))
        np.testing.assert_array_equal(selected[0][0], ends[0])
        self.assertEqual(1, len(selected))
        np.testing.assert_array_equal(ambiguous[0], ends)

    def test_learned_ranker_requires_evidence_and_is_direction_symmetric(self):
        features = np.array([[2., .8, .35, 1.2], [-2., -.8, -.35, -1.2]] * 10)
        model = LogisticOrientation.fit(features, [1, 0] * 10)
        a = np.float64([[1., 1., .7, 1.], [3., 1.8, 1.05, 2.2]])
        ends = np.array([[100., 100.], [450., 300.]])
        self.assertEqual(0, model.choose(Tube(ends, a))[0])
        self.assertEqual(1, model.choose(Tube(ends[::-1], a[::-1]))[0])
        self.assertIsNone(model.choose(Tube(ends, np.ones((2, 4)))))
        with self.assertRaises(ValueError):
            LogisticOrientation.fit(features[:2], [1, 0])

    def test_endpoint_matching_maximizes_cardinality_before_distance(self):
        labels = np.float64([[0, 0], [8, 0]])
        predictions = np.float64([[3, 0], [-4, 0]])
        self.assertEqual({(1, 0), (0, 1)}, set(maximum_matches(labels, predictions, np.array([5.,5.]))))
        self.assertEqual(1, len(maximum_matches(labels, predictions[:1], np.array([5.,5.]))))

    def test_shaft_matching_is_independent_of_entry_orientation(self):
        reference = np.float64([[[20, 30], [90, 30]]])
        tube = Tube(np.float64([[82, 32], [25, 32]]), np.ones((2, 4)))
        vertical = Tube(np.float64([[60, 29], [60, 85]]), np.ones((2, 4)))
        self.assertEqual(((0, 0),), tube_matches(reference, [tube, vertical]))
        self.assertEqual((), tube_matches(reference, [vertical]))

    def test_homography_recovers_held_out_point_and_refuses_bad_controls(self):
        plane = np.float32([[20, 20], [180, 20], [180, 180], [20, 180]])
        image = np.float32([[70, 40], [219, 53], [243, 211], [32, 207]])
        forward = cv.getPerspectiveTransform(plane, image)
        recovered = rectify_from_controls(image, plane, (270, 300))
        targets = np.float64([[84.2, 62.8], [122.5, 158.3]])
        np.testing.assert_allclose(project(recovered, project(forward, targets)), targets, atol=2e-5)
        for bad in (image[:-1], image[[0,2,1,3]], image + [-100,-90]):
            with self.assertRaises(ValueError):
                rectify_from_controls(bad, plane, (270, 300))

    def test_pair_recovers_entry_instead_of_tail_and_negative_stays_empty(self):
        rng = np.random.default_rng(61219)
        before = background(rng, "multicolor", worn=False)
        drawn = before.copy()
        labels = paint_shafts(drawn, rng, "single", "carbon")
        matrix, size = homography("front")
        img = render(drawn, matrix, size, "front")
        bare = render(before, matrix, size, "front")
        selected, _ = proposals(extract(img, "multicolor", bare))
        self.assertEqual(1, len(selected))
        face, nock = project(matrix, labels[0])
        np.testing.assert_allclose(selected[0][0], face, atol=2.5)
        self.assertGreater(np.linalg.norm(selected[0][0]-nock), 35)
        self.assertEqual(([], []), proposals(extract(bare, "multicolor", bare)))
        self.assertIsNone(foreground_pair(img, bare[:-1]))
        # A two-tone target is never calibrated by fitting the yellow disk alone.
        self.assertIsNone(fit_geometry(img, "field-yellow-teal"))

    def test_manifest_detects_hash_and_group_leak_and_ambiguity_coordinates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shape = root/"synthetic.bin"
            shape.write_bytes(b"test data")
            row = {"id":"one","source_group":"board","split":"train","source":"synthetic",
                   "image":"synthetic.bin","sha256":fingerprint(shape),"before":None,"before_sha256":None,
                   "size":[100, 100],"geometry":None,
                   "arrows":[{"entry":[40, 50],"tail":[60, 60],"uncertainty_px":0,"visibility":"visible"}]}
            manifest = root/"manifest.json"
            document = {"schema":1,"annotated_by":"test","frames":[row]}
            write_json(manifest, document)
            self.assertEqual(1, len(read_manifest(root)))
            for invalid in (dict(row, sha256="different"), dict(row, arrows=[dict(row["arrows"][0], visibility="ambiguous")])):
                write_json(manifest, dict(document, frames=[invalid]))
                with self.assertRaises(ValueError):
                    read_manifest(root)
            write_json(manifest, dict(document, frames=[row, dict(row, id="two", split="test")]))
            with self.assertRaisesRegex(ValueError,"crosses splits"):
                read_manifest(root)

    def test_data_paths_cannot_read_a_parent_or_external_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)/"inside"
            root.mkdir()
            outside = root.parent/"outside"
            outside.touch()
            (root/"escape").symlink_to(outside)
            for bad in ("../outside", str(outside), "escape"):
                with self.assertRaises(ValueError):
                    safe_file(root, bad)


if __name__ == "__main__":
    cv.setNumThreads(1)
    unittest.main()
