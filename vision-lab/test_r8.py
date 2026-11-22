import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import numpy as np

from r8.audit import classify, full_peaks
from r8.corpus import read
from r8.retour import register
from r8.complement import receive
from lab_data import fingerprint, write_json


class BlindReturnTests(unittest.TestCase):
    def test_partial_counts_keep_original_and_use_separate_coordinate_reading(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            packet=root/"lecture-test"
            packet.mkdir()
            forms=[]
            for n in range(1,4):
                alias=f"photo-{n:02}"
                png=packet/(alias+".png")
                png.write_bytes(alias.encode())
                forms.append(dict(id=alias,sha256=fingerprint(png),taille=[9,8],tubes=[],
                                  zones_illisibles=[],inventaire_complet=None))
            write_json(packet/"lecture.json",forms)
            counts=[f|dict(tubes=[n],zones_illisibles=[0]) for f,n in zip(forms,(1,0,2))]
            original=root/"source.json"
            write_json(original,counts)
            write_json(root/"cle-test.json",[dict(alias=f["id"],id=f["id"]) for f in forms])
            write_json(root/"corpus.json",[dict(id=f["id"],size=f["taille"],marks=[],ignore=[]) for f in forms])
            receipt=root/"receipt"
            register(root,original,receipt,["photo-02"],["photo-03"])
            self.assertEqual((receipt/"source.json").read_bytes(),original.read_bytes())
            data=forms[0]
            export=root/"review.json"
            marked=dict(schema=1,id="review-01",size=data["taille"],original_sha256=data["sha256"],
                        photo_sha256=data["sha256"],annotated_by="fixture",complete=True,
                        arrows=[dict(axis=[[1,1],[4,4]],entry=None,visibility="ambiguous")])
            write_json(export,marked|dict(photo_sha256="changed"))
            with self.assertRaisesRegex(ValueError,"differs"):
                receive(root,receipt/"reception.json",[export],root/"wrong","2026-11-22")
            write_json(export,marked)
            output=root/"out"
            receive(root,receipt/"reception.json",[export],output,"2026-11-22")
            result=read(output/"comparaison.json")
            self.assertEqual(result["status"],"awaiting_human_adjudication")
            self.assertIsNone(result["photos"][0]["independent"]["tubes"][0]["entree"])
            self.assertEqual([r["status"] for r in result["photos"][1:]],["reported_empty","invalid_by_reader"])


class PeakAuditTests(unittest.TestCase):
    def test_uncapped_plateaus_and_score_order(self):
        heat=np.zeros((100,100),np.float32)
        heat[5::9,5::9]=.65
        heat[5,6]=.65
        heat[5,32]=.8
        peaks=full_peaks(heat)
        self.assertEqual(len(peaks),121)
        self.assertEqual(peaks[0][0],[32,5])

    def test_only_nearest_contact_is_matched_at_each_threshold(self):
        r=dict(oriented_size=[704,704],entry=[10,10])
        peaks=[([15,10],.91),([10,10],.8)]
        self.assertEqual(classify(r,peaks,.5),(1,1))
        self.assertEqual(classify(r,peaks,.9),(0,0))


@unittest.skipUnless(all(importlib.util.find_spec(p) for p in ("torch","torchvision")),"R8 CPU environment required")
class SupervisionTests(unittest.TestCase):
    def test_uncertainty_preserves_no_loss_at_hidden_point(self):
        from r8.experiment import complete_heat
        t,k=complete_heat([dict(entry=[90,90]),dict(entry=None)],[[[95,95],[106,106]]],0,0)
        self.assertEqual(t[90,90],1)
        self.assertEqual(k[100,100],0)
        self.assertEqual(k[0,0],1)
        self.assertEqual(t[0,0],0)

    def test_unknown_region_wholly_outside_crop_cannot_clear_background(self):
        from r8.experiment import complete_heat
        t,k=complete_heat([],[[[1,1],[8,8]],[[900,910],[930,940]]],100,100)
        self.assertTrue(k.all())
        self.assertFalse(t.any())
