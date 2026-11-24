"""Non-régression des appariements, des partitions et du dossier de revue R14."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from r14.mesurer import analyze,maximum,read


class DiscriminationTests(unittest.TestCase):
    def test_unique_tube_capacity_and_reassignment(self):
        self.assertEqual(maximum([{0},{0}]),1)
        self.assertEqual(maximum([{0,1},{0}]),2)
        self.assertEqual(maximum([set()]),0)

    def test_public_archives_reproduce_measures(self):
        with TemporaryDirectory() as tmp:
            analyze(Path(tmp))
            for p in Path(tmp).glob('*.json'):
                self.assertEqual(p.read_bytes(),(Path('r14/mesures')/p.name).read_bytes(),p.name)

    def test_independent_partition_and_bank(self):
        rows=read('r14/partitions.json')['photos']
        by_id={r['id']:r for r in rows}
        self.assertEqual(len(by_id),len(rows))
        sources={}
        for r in rows:
            self.assertEqual(sources.setdefault(r['provenance'],r['partition']),r['partition'])
            for tube in r['tubes']:
                self.assertEqual(tube['contact_visible'],tube['entree'] is not None)
                self.assertEqual(tube['axe'],tube['bouts_trace'])
        for n in read('r14/banque-segments.json'):
            self.assertEqual(n['partition'],by_id[n['photo']]['partition'])
            self.assertEqual(n['provenance'],by_id[n['photo']]['provenance'])
        figures=read('r14/figures/valeurs.json')['geometrie']
        for name,values in figures.items():
            src=read(f'r14/mesures/{name}.json')['strates']['ensemble']['mesures']
            for method,metrics in values.items():
                for key,value in metrics.items():
                    self.assertEqual(value,src[method][key])
