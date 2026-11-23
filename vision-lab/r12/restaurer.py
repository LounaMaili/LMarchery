"""Récupérer exactement les douze dérivés publics gelés, sans accéder aux réserves."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from urllib.request import Request, urlopen


def restore(folder):
    rows=json.loads(Path(__file__).with_name('nouvelles.json').read_text())['photos']
    folder.mkdir(exist_ok=True,parents=True)
    for row in rows:
        src=row['source']
        path=folder/(row['id']+'.jpg')
        if path.exists():
            data=path.read_bytes()
        else:
            request=Request(src['pixels_url'],headers={'User-Agent':'LMArchery-vision-research/1.0'})
            with urlopen(request,timeout=45) as response:
                data=response.read()
        if len(data)!=src['size'] or sha256(data).hexdigest()!=src['sha256']:
            raise ValueError('Dérivé différent, consulter la capsule locale : '+row['id'])
        if not path.exists():
            path.write_bytes(data)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path)
    restore(p.parse_args().folder)
