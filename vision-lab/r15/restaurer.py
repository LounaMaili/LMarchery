"""Restaurer les dix fichiers admis depuis leurs URL publiques et révisions vérifiées."""
import argparse
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from r15.recherche import MEDIA, fetch, read
from r15.corpus import fingerprint


def historique(original, worktrees=None):
    absolute = Path(original)
    if worktrees is None:
        return absolute
    for i,part in enumerate(absolute.parts[:-1]):
        if part.startswith('vision-lab-r') and part[12:].isdigit() and absolute.parts[i+1]=='vision-lab':
            return Path(worktrees).joinpath(*absolute.parts[i:])
    raise ValueError(f'Chemin historique inconnu: {original}')


def restore(dest):
    dest.mkdir(parents=True,exist_ok=True)
    for row in read('r15/lectures.json')['photos']:
        source = row['source']
        video = 'instant_s' in source
        target = dest/source['fichier_travail']
        media = dest/('wc82322984.webm' if video else target.name)
        with TemporaryDirectory(dir=dest) as tmp:
            candidate = media if media.exists() else Path(tmp)/media.name
            if not candidate.exists():
                fetch(source['url_octets'],candidate,limit=source['octets_source'])
            if candidate.stat().st_size!=source['octets_source'] or fingerprint(candidate)!=source['sha256_source']:
                raise ValueError(f'Fichier source différent: {media.name}')
            if candidate!=media:
                candidate.rename(media)
            if video and not target.exists():
                frame = Path(tmp)/target.name
                subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-ss',str(source['instant_s']),
                    '-i',str(media),'-map_metadata','-1','-frames:v','1',str(frame)],check=True)
                if fingerprint(frame)!=row['sha256_pixels']:
                    raise ValueError('Vérifier la version de FFmpeg et du décodeur')
                frame.rename(target)
        if fingerprint(target)!=row['sha256_pixels']:
            raise ValueError(target.name)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dest',type=Path,nargs='?',default=MEDIA)
    restore(parser.parse_args().dest)
