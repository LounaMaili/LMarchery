"""Périmètre immuable des archives. Compatible avec les deux environnements R27."""
import hashlib
import json
from pathlib import Path

ROOT = Path('r28')
SEEDS = (9302, 9303)
STEPS = (512, 768)
REFERENCE = '3eff2b26f857808f60920db7c78157e9162bd71b'
GEL = '78a9c6cec6268ff9e92d6fbeec7ae6eb3838b4f6'
INPUTS = {
    'r27/sorties/index.json': 'b520484961056c9dee0c88527cc4eca7f17750c3f7bf03ab2de428008077ecf1',
    'r26/train.json': '40d8665fdcfea189e59908c1cf4f6b7cc1c4778c51a7672f08caeb488d68ecc7',
    'r20/plafonds.json': 'f159c8474448a26b43a87e059cbfeeffeb13a109a2698961eb945b9dcd12ed1f',
    'r27/mesurer.py': '494dd30b179c9570d9bbef88632eb9d8325a1306b805152c866c4a6533b1b76b',
    'r26/archives.py': '659faaf6cc08dea1e8edaa83b14608708a2d43ed71c257cff0a75f73e707d0ec',
    'r19/affecter.py': '374fe212a4ed963a0e49a49afd699f700a7d9e5088982f7209e5612eebf8117f',
    'r27/bilan.py': '0fc1bbfdd391ca80930cc3f3e19c0ce32f88743da57b5746fce5d90968e340ec',
}


def read(path):
    return json.loads(Path(path).read_text())


def save(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def scope():
    for file, expected in INPUTS.items():
        if digest(file) != expected:
            raise ValueError(f'Source modifiée depuis R27 : {file}')
    photos = read('r26/train.json')['photos']
    index = read('r27/sorties/index.json')['lectures']
    assert len(photos) == 9 and sum(len(s['instances']) for s in photos.values()) == 31
    for seed in SEEDS:
        for step in STEPS:
            records = index[str(seed)][str(step)]
            assert records.keys() == photos.keys()
            for key, spec in photos.items():
                archive = Path('r27') / records[key]['fichier']
                assert archive.stat().st_size == records[key]['octets'] and digest(archive) == records[key]['sha256']
                assert digest(spec['masque']) == spec['sha256_masque']
                yield seed, step, key, spec, archive


def tag(seed, step, photo):
    return f'{seed}-{step}-{photo}'
