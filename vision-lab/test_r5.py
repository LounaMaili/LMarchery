import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from lab_data import fingerprint, write_image, write_json
from r5.review import IDENTITY, build_packet, collect, compare, packet_identity, starter


class ReviewPacketTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.dataset = self.root / "dataset"
        self.dataset.mkdir()
        self.image = self.dataset / "fixture.png"
        write_image(self.image, np.zeros((30, 50, 3), dtype=np.uint8))
        self.row = {
            "id": "fixture", "source_group": "group", "source": "public-photo", "split": "test",
            "image": self.image.name, "sha256": fingerprint(self.image), "size": [50, 30],
            "before": None, "before_sha256": None, "geometry": None,
            "complete": True, "arrows": [], "landmarks": [{"id": "trou-1", "point": [17, 7]}],
        }
        self.packet = self.root / "packet"

    def create_packet(self):
        write_json(self.dataset / "manifest.json", {"schema": 1, "annotated_by": "A", "frames": [self.row]})
        with patch("r5.review.reserved_hashes", return_value={self.row["sha256"]}):
            self.assertEqual(1, build_packet({"r2": self.dataset}, self.packet))
        self.identity, _, _ = packet_identity(self.packet / "lecture-aveugle", "review-001")

    def test_packet_contains_only_pixels_and_opaque_identity(self):
        self.create_packet()
        blind = self.packet / "lecture-aveugle"
        self.assertEqual({"review-001.json", "review-001.png"}, {p.name for p in blind.iterdir()})
        self.assertEqual(set(IDENTITY), self.identity.keys())
        first = json.loads((self.packet / "reference/review-001.json").read_text())
        self.assertEqual(self.row["landmarks"], first["landmarks"])
        self.assertEqual(self.row["sha256"], self.identity["original_sha256"])

    def test_disallows_unregistered_original_and_missing_pair(self):
        self.row.update(before="before.png", before_sha256="")
        before = self.dataset / "before.png"
        write_image(before, np.ones((30, 50, 3), dtype=np.uint8))
        self.row["before_sha256"] = fingerprint(before)
        with self.assertRaisesRegex(ValueError, "before photo is missing"):
            self.create_packet()
        self.assertFalse(self.packet.exists())
        write_json(self.dataset / "manifest.json", {"schema": 1, "annotated_by": "A", "frames": [self.row]})
        with self.assertRaisesRegex(ValueError, "unregistered"):
            build_packet({"r2": self.dataset}, self.packet)
        self.assertFalse(self.packet.exists())

    def test_starter_preserves_files_and_rejects_extra_fields_or_changed_pixels(self):
        self.create_packet()
        destination = self.root / "starter"
        with patch("r5.review.STARTER", ("review-001",)):
            starter(self.packet, destination)
            blind = self.packet / "lecture-aveugle"
            for path in destination.iterdir():
                self.assertEqual((blind / path.name).read_bytes(), path.read_bytes())
            with self.assertRaises(FileExistsError):
                starter(self.packet, destination)
            (blind / "review-001.png").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "pixels"):
                starter(self.packet, self.root / "withdrawn")
            self.assertFalse((self.root / "withdrawn").exists())
        write_json(blind / "review-001.json", self.identity | {"arrows": []})
        with self.assertRaisesRegex(ValueError, "blind packet"):
            packet_identity(blind, "review-001")

    def test_return_keeps_exact_bytes_and_refuses_duplicate_without_publication(self):
        self.create_packet()
        incoming = self.root / "returned"
        incoming.mkdir()
        record = self.identity | {"annotated_by": "B", "complete": False, "arrows": [], "landmarks": []}
        payload = (json.dumps(record, indent=1) + "\n").encode()
        (incoming / "first.json").write_bytes(payload)
        output = self.root / "result"
        collect(self.packet, incoming, output)
        self.assertEqual(payload, (output / "second-readings/review-001.json").read_bytes())
        report = json.loads((output / "desaccords.json").read_text())
        self.assertEqual([], report["awaiting_reading"])
        self.assertFalse(report["comparisons"][0]["completeness_agrees"])
        self.assertEqual([["trou-1"], []], report["comparisons"][0]["landmark_unmatched"])
        with self.assertRaises(FileExistsError):
            collect(self.packet, incoming, output)
        (incoming / "duplicate.json").write_bytes(payload)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            collect(self.packet, incoming, self.root / "other")
        self.assertFalse((self.root / "other").exists())


class ReviewComparisonTests(unittest.TestCase):
    identity = {"schema": 1, "id": "review-001", "size": [704, 704],
                "original_sha256": "a" * 64, "photo_sha256": "f" * 64}

    def reading(self, reader, *arrows, landmarks=()):
        return self.identity | {"annotated_by": reader, "complete": True, "arrows": arrows,
                                "landmarks": landmarks}

    def arrow(self, name, start, tail, entry, visibility="visible"):
        return {"id": name, "axis": [start, tail], "entry": entry,
                "visibility": visibility, "uncertainty_px": 3 if entry else None}

    def test_preserves_disagreement_on_status_and_kind_when_averaging_would_hide_it(self):
        a = self.reading("A", self.arrow("a", [30, 40], [130, 40], [30, 40]),
                         landmarks=({"id": "trou-1", "point": [135, 13]},))
        b = self.reading("B", self.arrow("b", [130, 41], [30, 41], None, "occluded"),
                         landmarks=({"id": "dechirure-1", "point": [136, 14]},))
        result = compare(a, b, self.identity)
        self.assertEqual([a, b], result["readings"])
        self.assertEqual(["visible", "occluded"], result["tube_pairs"][0]["visibility"])
        self.assertIsNone(result["tube_pairs"][0]["distance_px"])
        self.assertEqual(["old-hole", "tear"], result["landmark_pairs"][0]["categories"])
        self.assertEqual("awaiting-adjudication", result["status"])

    def test_reports_large_coordinate_disagreement_in_original_pixels(self):
        a = self.reading("A", self.arrow("a", [30, 40], [130, 40], [30, 40]))
        b = self.reading("B", self.arrow("b", [40, 40], [130, 40], [40, 40]))
        pair = compare(a, b, self.identity)["tube_pairs"][0]
        self.assertEqual(10, pair["distance_px"])
        self.assertTrue(pair["beyond_combined_uncertainty"])

    def test_does_not_guess_identity_for_parallel_grouped_tubes(self):
        a = self.reading("A", self.arrow("a", [30, 40], [130, 40], [30, 40]))
        b = self.reading("B", self.arrow("b", [30, 41], [130, 41], [30, 41]),
                         self.arrow("c", [30, 42], [130, 42], [30, 42]))
        result = compare(a, b, self.identity)
        self.assertEqual([], result["tube_pairs"])
        self.assertEqual([["a", ["b", "c"]]], result["tube_ambiguities"])
        self.assertEqual([["a"], ["b", "c"]], result["tube_unmatched"])

    def test_refuses_same_reader_wrong_image_and_hidden_contact_with_a_point(self):
        a = self.reading("A")
        with self.assertRaisesRegex(ValueError, "different reader"):
            compare(a, self.reading("  a "), self.identity)
        with self.assertRaisesRegex(ValueError, "different original"):
            compare(a, self.reading("B") | {"photo_sha256": "x" * 64}, self.identity)
        b = self.reading("B", self.arrow("b", [30, 40], [130, 40], [30, 40], "ambiguous"))
        with self.assertRaisesRegex(ValueError, "visible contacts"):
            compare(a, b, self.identity)
