"""Inventorier les roues réellement installées et leurs distributions sources."""
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from importlib.metadata import distributions
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

from packaging.utils import canonicalize_name, parse_wheel_filename

ROOT = Path('data/r27')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def describe(entry):
    name, version, wheel = entry
    row = dict(nom=name, version=version, fichier=str(wheel), octets=wheel.stat().st_size, sha256=sha(wheel))
    if name in ('torch', 'torchvision', 'detectron2'):
        base = ('https://dl.fbaipublicfiles.com/detectron2/wheels/cpu/torch1.10/' if name == 'detectron2'
                else 'https://download.pytorch.org/whl/cpu/')
        row['url'] = base + quote(wheel.name)
    else:
        with urlopen('https://pypi.org/pypi/' + name + '/' + version + '/json', timeout=30) as reply:
            files = json.load(reply)['urls']
        original = next((f for f in files if f['filename'] == wheel.name), None)
        if original:
            assert original['digests']['sha256'] == row['sha256']
            row['url'] = original['url']
        else:
            source = next(f for f in files if f['packagetype'] == 'sdist')
            row.update(source=source['url'], sha256_source=source['digests']['sha256'])
    return row


if __name__ == '__main__':
    wheels = {parse_wheel_filename(p.name)[:2]:p for p in (ROOT/'wheels').glob('*.whl')}
    entries = []
    for d in distributions():
        name = canonicalize_name(d.metadata['Name'])
        from packaging.version import Version
        entries.append((name, d.version, wheels[name, Version(d.version)]))
    with ThreadPoolExecutor(6) as pool:
        rows = list(pool.map(describe, sorted(entries)))
    python = ROOT/'cpython.tar.gz'
    doc = dict(python=dict(fichier=str(python), octets=python.stat().st_size,sha256=sha(python),
               url='https://github.com/astral-sh/python-build-standalone/releases/download/20240726/cpython-3.9.19%2B20240726-x86_64-unknown-linux-gnu-install_only.tar.gz'),
               roues=rows)
    Path('r27/environnement.json').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')
    Path('r27/requirements.lock').write_text(''.join(
        f"{r['nom']}=={r['version']} --hash=sha256:{r['sha256']}\n" for r in rows))
