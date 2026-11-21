from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import cv2
import numpy as np

from detector import Tube
from lab_data import fingerprint, read_manifest, write_image
from r3.review import blind, read_json
from r4.contact import filter_candidates, verdict
from r4.corpus import HERE, audit, prepare, typed_nuisances
from r4.wear import SCENES, generate


class ContactGateTests(unittest.TestCase):
    def setUp(self):
        self.point = np.array([30., 50.])
        self.tube = Tube(np.array([[100., 50.], self.point]), np.empty((0, 0)))
        self.shaft = np.zeros((100, 140), np.float32)
        self.shaft[50, 30:101] = 1.

    def test_keeps_exact_candidate_only_at_the_supported_end(self):
        attached = SimpleNamespace(point=self.point)
        detached = SimpleNamespace(point=np.array([55., 50.]))
        accepted, rejected = filter_candidates([attached, detached], [self.tube], self.shaft)
        self.assertIs(accepted[0], attached)
        self.assertIs(accepted[0].point, self.point)
        self.assertEqual([(detached, "no_nearby_shaft_end")], rejected)

    def test_rejects_gaps_and_support_past_the_proposed_entry(self):
        with_gap = self.shaft.copy()
        with_gap[50, 38:67] = 0.
        self.assertEqual("shaft_support_discontinuous", verdict(self.point, [self.tube], with_gap))
        self.shaft[50, 20:30] = 1.
        self.assertEqual("support_continues_past_contact", verdict(self.point, [self.tube], self.shaft))

    def test_abstains_at_frame_edge_or_when_two_ends_are_compatible(self):
        left = self.point - [25, 0]
        edge_tube = Tube(np.array([left, [100., 50.]]), np.empty((0, 0)))
        self.assertEqual("insufficient_field_of_view", verdict(left, [edge_tube], self.shaft))
        self.shaft[51, 30:106] = 1.
        competing = Tube(np.array([[30., 51.], [105., 51.]]), np.empty((0, 0)))
        self.assertEqual("competing_shaft_ends", verdict(self.point, [self.tube, competing], self.shaft))


class CorpusTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def row(self, name, pixel, **updates):
        photo = self.root / f"{name}.png"
        write_image(photo, np.full((14, 20, 3), pixel, dtype=np.uint8))
        return dict(id=name, size=[20, 14], split="test", image=photo.name, sha256=fingerprint(photo),
                    session_id=name, source_group=name, origin_id=name, board_id=name, credit="Photographe",
                    photographer_id="reader", source_url="https://example.test/photo", license="CC0",
                    license_evidence="https://example.test/licence", use="public-lab",
                    family="multicolor", scenario="empty", training_allowed=False,
                    weights_redistribution_reviewed=False) | updates

    def test_excludes_private_and_past_originals(self):
        row = self.row("photo", 100)
        past = read_json(HERE.parent / "r3/real-sources.json")["frames"][0]
        with self.assertRaisesRegex(ValueError, "Prior R3"):
            audit([row | {"sha256": past["sha256"]}], self.root)
        with self.assertRaisesRegex(ValueError, "private"):
            audit([row | {"use": "private"}], self.root)
        with self.assertRaisesRegex(ValueError, "Training use"):
            audit([row | {"split": "train"}], self.root)

    def test_checks_photographer_even_when_other_group_ids_differ(self):
        first = self.row("first", 100)
        second = self.row("second", 110, split="train", training_allowed=True,
                          weights_redistribution_reviewed=True)
        with self.assertRaisesRegex(ValueError, "Photographer"):
            audit([first, second], self.root)
        self.assertEqual(2, len(audit([first, second | {"photographer_id": "independent"}], self.root)))

    def test_binds_reading_to_unchanged_blind_photo_and_exports_noncontacts(self):
        row = self.row("photo", 100)
        packet = self.root / "packet"
        blind(self.root, [row], packet)
        marks = [{"id": "trou-1", "point": [3., 3.]}, {"id": "dechirure-1", "point": [10., 10.]},
                 {"id": "leurre-1", "point": [13., 11.]}, {"id": "other", "point": [2., 2.]}]
        label = read_json(packet / "review-001.json") | {
            "annotated_by": "reader-a", "complete": True, "arrows": [], "landmarks": marks,
        }
        output = self.root / "prepared"
        with self.assertRaisesRegex(ValueError, "another photo"):
            prepare([row], self.root, packet, [label | {"photo_sha256": "wrong"}], output)
        self.assertFalse(output.exists())
        prepare([row], self.root, packet, [label], output)
        manifest = read_json(output / "manifest.json")
        self.assertEqual("first-reading-awaiting-independent-review", manifest["annotation_status"])
        self.assertEqual(["old-hole", "tear", "lookalike"],
                         [mark["kind"] for mark in manifest["frames"][0]["nuisances"]])
        self.assertEqual([3., 3.], typed_nuisances(marks)[0]["point"])
        self.assertEqual([], read_manifest(output)[0]["arrows"])
        write_image(packet / "review-001.png", np.zeros((14, 20, 3), dtype=np.uint8))
        with self.assertRaisesRegex(ValueError, "photo changed"):
            prepare([row], self.root, packet, [label], self.root / "changed")


class WearTests(unittest.TestCase):
    def test_independent_held_out_groups_and_hidden_world_points(self):
        cv2.setNumThreads(1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate(root / "first", 1)
            generate(root / "second", 1)
            self.assertEqual(fingerprint(root / "first/manifest.json"),
                             fingerprint(root / "second/manifest.json"))
            rows = read_manifest(root / "first")
            self.assertEqual(set(SCENES), {row["scenario"] for row in rows})
            self.assertEqual(len(rows), len({row["source_group"] for row in rows}))
            for row in rows:
                self.assertEqual("test", row["split"])
                if row["scenario"].endswith("-only"):
                    self.assertEqual([], row["arrows"])
                    self.assertTrue(row["complete"])
                    self.assertTrue(row["nuisances"])
                if row["scenario"] == "worn-masked":
                    self.assertTrue(row["arrows"])
                    self.assertTrue(all(arrow["entry"] is None for arrow in row["arrows"]))
