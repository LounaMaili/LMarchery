import tempfile
import unittest
from pathlib import Path

import cv2 as cv
import numpy as np

from detector import Tube
from lab_data import fingerprint, write_image, write_json
from r2_corpus import agreement, read_corpus
from r2_detector import register, stable_entries
from r2_evaluate import evaluate, summarize


class FixedOrientation:
    def __init__(self, index):
        self.index = index

    def choose(self, tube):
        return self.index, .95


def mark(identity, entry=None, axis=None, visibility="visible", state="standalone"):
    return {"id": identity, "entry": entry, "tail": None, "opposite_kind": "unavailable",
            "axis": axis, "group": None, "visibility": visibility,
            "near_boundary": True if entry else None, "state": state,
            "uncertainty_px": 3 if entry else None}


class RegistrationTests(unittest.TestCase):
    def test_aligns_real_geometric_and_exposure_difference_in_synthetic_scene(self):
        rng = np.random.default_rng(45)
        frame = np.full((240, 320, 3), [109, 151, 176], dtype=np.uint8)
        for x, y in rng.integers((10, 10), (310, 230), (135, 2)):
            color = tuple(map(int, rng.integers(35, 218, 3)))
            cv.circle(frame, (int(x), int(y)), 3, color, -1)
        warp = np.float32([[1, .014, 6], [-.012, 1, 3]])
        after = cv.warpAffine(frame, warp, (320, 240), borderMode=cv.BORDER_REFLECT_101)
        after = cv.convertScaleAbs(after, alpha=.86, beta=14)
        cv.line(after, (71, 123), (144, 169), (12, 28, 65), 3)
        residual, info = register(after, frame)
        self.assertIsNone(info["reason"])
        self.assertGreaterEqual(info["held_out_fraction"], .75)
        self.assertGreater(residual[147, 109], 45)

    def test_rejects_flat_frames_with_no_independent_alignment_support(self):
        flat = np.zeros((220, 280, 3), np.uint8)
        self.assertEqual(register(flat, flat), (None, {"reason": "insufficient_tracks"}))
        self.assertEqual(register(flat, flat[:, :-1]), (None, {"reason": "incompatible_dimensions"}))


class AbstentionTests(unittest.TestCase):
    def test_accepts_only_agreeing_stable_noncompeting_entry(self):
        forms = np.array([[4., 0, 0, 0], [1., 0, 0, 0]])
        tube = Tube(np.array([[75., 24.], [28., 45.]]), forms)
        shifted = Tube(tube.ends + [2, 1], forms)
        coincident = Tube(tube.ends.copy(), forms)
        edge = Tube(tube.ends - [24, 0], forms)
        points, refusals = stable_entries([tube], [shifted], FixedOrientation(1), (100, 100))
        self.assertEqual([point.tolist() for point in points], [[28., 45.]])
        self.assertEqual(refusals, [])
        self.assertEqual(stable_entries([tube], [shifted], FixedOrientation(0), (100, 100)),
                         ([], ["orientation_uncertain"]))
        self.assertEqual(stable_entries([tube], [], FixedOrientation(1), (100, 100)),
                         ([], ["endpoint_unstable"]))
        self.assertEqual(stable_entries([tube, coincident], [shifted], FixedOrientation(1), (100, 100)),
                         ([], ["competing_endpoints", "competing_endpoints"]))
        self.assertEqual(stable_entries([edge], [edge], FixedOrientation(1), (100, 100)),
                         ([], ["frame_edge"]))

    def test_does_not_promote_an_occluded_shaft_or_existing_arrow_to_a_match(self):
        row = {"complete": True, "geometry": None, "arrows": [
            mark("new", [25, 27], state="new"),
            mark("old", [52, 57], state="existing"),
            mark("hidden", axis=[[84, 87], [97, 92]], visibility="occluded", state="new"),
        ]}
        result = evaluate(row, [np.array(p) for p in [[26, 28], [53, 58], [90, 89], [26, 28], [125, 10]]],
                          np.ones(2), pair=True)
        self.assertEqual((result["known"], result["matched"], result["occluded"]), (1, 1, 1))
        self.assertEqual(result["unassigned"],
                         {"false": 1, "existing": 1, "occluded_axis": 1, "other_unresolved_axis": 0,
                          "unknown": 1})
        report = summarize([result])
        self.assertEqual(report["proposals_per_image"], 5)
        self.assertIsNone(report["errors_mm"])
        self.assertEqual(report["boundary_errors_px"]["n"], 1)

    def test_reports_an_abstention_separately_from_a_missed_visible_entry(self):
        row = {"complete": True, "geometry": None, "arrows": [mark("a", [7, 10])]}
        missed = evaluate(row, [], np.ones(2), pair=False)
        empty = evaluate(row | {"arrows": []}, [], np.ones(2), pair=False)
        summary = summarize([missed, empty])
        self.assertEqual((summary["known"], summary["matched"], summary["negatives_without_proposals"]), (1, 0, 1))


class CorpusTests(unittest.TestCase):
    def test_visible_marks_and_blind_second_reader(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_image(root / "example.png", np.zeros((90, 100, 3), np.uint8))
            image_hash = fingerprint(root / "example.png")
            frame = {"id": "example", "source_group": "board", "split": "test", "source": "public-photo",
                     "image": "example.png", "sha256": image_hash, "size": [100, 90], "before": None,
                     "before_sha256": None, "geometry": None, "license": "CC0", "source_url": "https://example.org",
                     "complete": True, "arrows": [mark("a", [10, 10])]}
            write_json(root / "manifest.json", {"schema": 1, "annotated_by": "Reader A", "frames": [frame]})
            primary = read_corpus(root)
            with self.assertRaisesRegex(ValueError, "different annotator"):
                agreement(primary, (" reader a ", primary[1]))
            frame["arrows"] = [mark("a", [13, 14]), mark("b", visibility="occluded")]
            write_json(root / "manifest.json", {"schema": 1, "annotated_by": "Reader B", "frames": [frame]})
            second = read_corpus(root)
            self.assertEqual([row["distance_px"] for row in agreement(primary, second)], [5., None])
            frame["arrows"][1]["entry"] = [14, 15]
            write_json(root / "manifest.json", {"schema": 1, "annotated_by": "Reader B", "frames": [frame]})
            with self.assertRaisesRegex(ValueError, "Only a visible"):
                read_corpus(root)


if __name__ == "__main__":
    unittest.main()
