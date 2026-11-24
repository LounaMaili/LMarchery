"""Registre des octets publics R12 utilisés pour la comparaison."""
from pathlib import Path
from lab_data import fingerprint,write_json
from r10.diagnostic import read
from r10.tableaux import RUNS


def inventory():
    paths=['r12/historique.json','r12/nouvelles.json','r13/collecte.json']
    paths.extend(f'r12/predictions/{name}.json' for name in RUNS)
    return {p:dict(octets=Path(p).stat().st_size,sha256=fingerprint(Path(p))) for p in paths}


if __name__=='__main__':
    write_json(Path('r13/entrees.json'),inventory())
