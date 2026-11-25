"""Vérifier les transformations, les fichiers photographiques et les crédits du périmètre figé."""
import argparse
from hashlib import sha256
from pathlib import Path
import re
from urllib.parse import urlparse

import cv2 as cv
import numpy as np
from PIL import Image

from r14.mesurer import read, save
from r15.corpus import fingerprint
from r7.prepare import resized

ROOT=Path('r18')


def rights(scope):
    source = scope['source']
    licence = source.get('terms') or source['licence_image']
    path = urlparse(licence).path.strip('/').split('/')
    if path[:2] == ['publicdomain','zero']:
        title = 'CC0 '+path[2]
    elif path[0] == 'licenses' and path[1] in ('by','by-sa'):
        title = 'CC '+path[1].upper()+' '+path[2]
    else:
        raise ValueError(licence)
    return dict(credit=source['credit'],notice=source.get('url') or source['source'],
                titre=source.get('title') or source.get('source_file') or scope['photo'],
                licence=licence,nom_licence=title)


def matrices(scope):
    _, h = scope['dimensions_stockees']
    orient = np.eye(3, dtype=float)
    if scope['exif'] == 6:
        orient = np.array([[0,-1,h-1],[1,0,0],[0,0,1]],dtype=float)
    elif scope['exif'] != 1:
        raise ValueError('Orientation hors du périmètre')
    sx,sy=scope['echelle_oriente_travail']
    work=np.array([[sx,0,(sx-1)/2],[0,sy,(sy-1)/2],[0,0,1]])
    return orient,work


def decode(scope, worktrees):
    original=worktrees/scope['source_locale']
    reference=worktrees/scope['fichier_reference']
    if fingerprint(original)!=scope['sha256_source'] or fingerprint(reference)!=scope['sha256_reference']:
        raise ValueError(f"Source photographique modifiée {scope['photo']}")
    with Image.open(original) as im:
        if list(im.size)!=scope['dimensions_stockees'] or im.getexif().get(274,1)!=scope['exif']:
            raise ValueError('Dimensions ou orientation différentes du gel')
    raw=cv.imread(str(original), cv.IMREAD_COLOR | cv.IMREAD_IGNORE_ORIENTATION)
    oriented=np.rot90(raw,-1) if scope['exif']==6 else raw
    if not np.array_equal(oriented,cv.imread(str(original))):
        raise ValueError('Désaccord entre l’orientation explicite et le décodeur')
    work=resized(oriented)
    if list(work.shape[1::-1])!=scope['dimensions_travail'] or not np.array_equal(work,resized(cv.imread(str(reference)))):
        raise ValueError('Désaccord avec les pixels de travail historiques')
    return oriented,work


def audit(out,worktrees):
    rows=[]
    for p in read(ROOT/'perimetre.json')['acquisitions']:
        original,work=decode(p,worktrees)
        o,w=matrices(p)
        cols,rows0=p['dimensions_stockees']
        pts=np.array([[0,0,1],[cols-1,0,1],[0,rows0-1,1],[cols-1,rows0-1,1],[(cols-1)/2,(rows0-1)/2,1]]).T
        error=float(np.abs(np.linalg.inv(w@o)@w@o@pts-pts).max())
        if error>1e-8:
            raise ValueError(p['photo'])
        s=p['source']
        url=s.get('url_octets') or s.get('pixels_url') or s.get('source_url')
        if not url:
            notice=(worktrees/p['source_locale']).with_suffix('.html')
            if fingerprint(notice)!=s['notice_sha256']:
                raise ValueError('Notice Flickr')
            url=re.search(r'<meta property="og:image" content="([^"]+)"',notice.read_text())[1]
        row=dict(photo=p['photo'],droits=rights(p),octets_publics=url,
            dimensions_stockees=p['dimensions_stockees'],dimensions_orientees=p['dimensions_orientees'],dimensions_travail=p['dimensions_travail'],
            exif=p['exif'],stocke_vers_oriente=o.tolist(),oriente_vers_travail=w.tolist(),travail_vers_stocke=np.linalg.inv(w@o).tolist(),
            erreur_max_aller_retour_px=error,sha256_octets_source=p['sha256_source'],sha256_octets_reference=p['sha256_reference'],
            sha256_BGR_oriente=sha256(original.tobytes()).hexdigest(),sha256_BGR_travail=sha256(work.tobytes()).hexdigest(),
            parite_pixels_reference=True,emplacement_source=p['source_locale'],taille_source=p['taille_octets'],
            emplacement_reference=p['fichier_reference'],taille_reference=p['taille_reference_octets'])
        if 'instant_s' in s:
            path=(worktrees/p['source_locale']).with_name('wc82322984.webm')
            if fingerprint(path)!=s['sha256_source'] or path.stat().st_size!=s['octets_source']:
                raise ValueError('Film source')
            row['film']=dict(emplacement=str(path.relative_to(worktrees)),sha256=s['sha256_source'],octets=s['octets_source'],
                image_decodee=s['image_decodee'],instant_s=s['instant_s'],cadence=s['base_temps'])
        rows.append(row)
    save(out/'sources.json',rows)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--worktrees',required=True,type=Path)
    audit(**vars(parser.parse_args()))
