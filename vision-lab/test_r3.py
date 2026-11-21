import importlib.util
import tempfile
import unittest
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import fingerprint, read_manifest, write_image
from r3.review import audit, blind, compare, read_json
from r3.synth import generate

HAS_TORCH = importlib.util.find_spec("torch") is not None


class SyntheticTests(unittest.TestCase):
    def test_masks_and_partitions_regenerate_and_hidden_labels_have_no_point(self):
        cv.setNumThreads(1)
        with tempfile.TemporaryDirectory() as tmp:
            first, second = Path(tmp) / "first", Path(tmp) / "second"
            layout = (("train", 6, 1), ("dev", 2, 1), ("test", 2, 1))
            generate(first, layout)
            generate(second, layout)
            self.assertEqual(fingerprint(first / "manifest.json"), fingerprint(second / "manifest.json"))
            rows = read_manifest(first)
            self.assertEqual([sum(r["split"] == s for r in rows) for s in ("train", "dev", "test")], [6, 2, 2])
            hidden = next(row for row in rows if row["scenario"] == "masked")
            mark = hidden["arrows"][0]
            self.assertIsNone(mark["entry"])
            self.assertEqual(mark["visibility"], "occluded")
            mask = cv.imread(str(first / hidden["mask"]))
            self.assertGreater(np.count_nonzero(mask[:, :, 2]), 20)
            self.assertFalse(mask[:, :, 1].any())


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.photo = self.root / "target.png"
        write_image(self.photo, np.zeros((130, 160, 3), np.uint8))
        self.row = {"id": "sample", "image": self.photo.name, "size": [160, 130],
                    "sha256": fingerprint(self.photo), "source_group": "source1", "session_id": "session1",
                    "board_id": "board1", "origin_id": "original1", "split": "test",
                    "license": "CC0", "source_url": "https://example.org/image",
                    "license_evidence": "https://example.org/terms", "credit": "Synthetic reference"}

    def test_blind_packet_contains_only_normalized_pixels_and_opaque_identity(self):
        output = self.root / "packet"
        blind(self.root, [self.row | {"arrows": ["DO NOT INCLUDE"], "predictions": ["DO NOT INCLUDE"]}], output)
        self.assertEqual({path.name for path in output.iterdir()}, {"review-001.json", "review-001.png"})
        packet = read_json(output / "review-001.json")
        self.assertEqual(set(packet), {"schema", "id", "size", "original_sha256", "photo_sha256"})
        self.assertEqual(fingerprint(output / "review-001.png"), packet["photo_sha256"])

    def test_same_board_and_unreviewed_rights_refuse_training_overlap(self):
        write_image(self.root / "other.png", np.full((130, 160, 3), 20, np.uint8))
        other = self.row | {"id": "another", "image": "other.png", "sha256": fingerprint(self.root / "other.png"),
                            "source_group": "different-provenance", "session_id": "other-session",
                            "origin_id": "other", "split": "train"}
        with self.assertRaisesRegex(ValueError, "board_id"):
            audit([self.row, other], self.root)
        with self.assertRaisesRegex(ValueError, "weight distribution"):
            audit([self.row, other | {"board_id": "other-board"}], self.root)
        from r3.review import previous_images
        with self.assertRaisesRegex(ValueError, "R1/R2"):
            audit([self.row | {"sha256": sorted(previous_images())[0]}], self.root)

    def test_independent_reviewer_pairs_axes_without_reusing_visible_entry(self):
        header = {"size": [704, 600], "original_sha256": self.row["sha256"], "photo_sha256": self.row["sha256"]}
        left = {"id": "A", "axis": [[30, 40], [130, 140]], "entry": [30, 40], "visibility": "visible"}
        right = {"id": "X", "axis": [[130, 139], [32, 42]], "entry": None, "visibility": "occluded"}
        first = header | {"annotated_by": "reader-1", "arrows": [left]}
        second = header | {"annotated_by": "reader-2", "arrows": [right]}
        result = compare(first, second)
        self.assertEqual(result["pairs"], [{"first": "A", "second": "X", "first_visibility": "visible",
                                           "second_visibility": "occluded", "distance_px": None}])
        self.assertEqual(result["second_unmatched"], [])
        with self.assertRaisesRegex(ValueError, "independent"):
            compare(first, second | {"annotated_by": " READER-1 "})


@unittest.skipUnless(HAS_TORCH, "Optional R3 PyTorch runtime is not installed")
class LearningTests(unittest.TestCase):
    def test_three_heads_train_and_visibility_is_not_invented_for_a_mask_or_crop(self):
        import torch
        from r3.model import EntryNet, loss
        from r3.train import target_maps

        torch.set_num_threads(2)
        mask = np.zeros((160, 192, 3), np.uint8)
        mask[40, 25:90, 2] = 255
        labels = target_maps(mask, [], 0, 0, 192, 160)
        self.assertEqual(float(labels[0].sum()), 65.)
        self.assertEqual(float(labels[3].sum()), 65.)
        self.assertFalse(labels[1:3].any())
        self.assertFalse(target_maps(mask, [[60, 40]], 0, 0, 50, 80)[1:].any())
        model = EntryNet()
        photo = torch.zeros((1, 3, 160, 192))
        predictions = model(photo)
        self.assertEqual(tuple(predictions.shape), (1, 3, 160, 192))
        objective = loss(predictions, torch.as_tensor(labels[None]))
        objective.backward()
        self.assertTrue(torch.isfinite(objective))
        self.assertEqual(tuple(model.output.bias.grad.shape), (3,))
        self.assertTrue(torch.isfinite(model.output.bias.grad).all())

    def test_overlapping_native_tiles_cover_borders_in_exact_coordinates(self):
        import torch
        from r3.inference import offsets, predict
        class Maps(torch.nn.Module):
            def forward(self, photo):
                return photo * 4 - 2
        frame = np.zeros((307, 397, 3), np.uint8)
        frame[300:,390:,2] = 255
        actual = predict(Maps(), frame, True, "cpu")
        self.assertEqual(actual.shape, (3, 307, 397))
        self.assertGreater(actual[0, -1, -1], .85)
        self.assertLess(actual[2, -1, -1], .15)
        self.assertEqual(offsets(397, 192, 48), [0, 144, 205])

    def test_explicit_abstention_rejects_a_hot_but_invisible_contact(self):
        from r3.inference import propose
        from r3.evaluate import select_threshold
        maps = np.zeros((160,192), np.float32)
        shaft = maps.copy()
        shaft[78:82,30:115] = .9
        heat = maps.copy()
        heat[80,30] = .99
        candidates, tubes, reasons = propose([shaft,heat,maps])
        self.assertEqual((len(candidates), len(tubes)), (0, 1))
        self.assertEqual(reasons, ["no_supported_visible_contact"])
        candidates, _, _ = propose([shaft,heat,maps+.8])
        self.assertEqual(candidates[0].point.tolist(), [30,80])
        self.assertEqual(select_threshold([{"threshold": .25, "matched": 8, "unmatched_per_image": .31},
                                           {"threshold": .42, "matched": 4, "unmatched_per_image": .2}]), .42)


if __name__ == "__main__":
    unittest.main()
