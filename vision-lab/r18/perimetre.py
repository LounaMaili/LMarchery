"""Figer les identités R17 et le repère des acquisitions autorisées avant la lecture R18."""
import argparse
from pathlib import Path

from PIL import Image

from r14.mesurer import read, save
from r15.corpus import corpus, fingerprint
from r15.restaurer import historique
from r16.donnees import photo_paths


def selected():
    rows = read('r17/instances.json')
    scans = read('r17/acquisitions.json')
    priority = {}
    crigges = {}
    for a in rows:
        if a['usage_R17'] != 'train':
            continue
        for v in a['vues_annotees']:
            if not (v['axe'] and v['entree'] and not v['axe_touche_ignore'] and not v['entree_en_zone_ignoree']):
                continue
            if a['provenance'] == 'Crigges' and not v['masque_reference']:
                continue
            subset = crigges if a['provenance'] == 'Crigges' else priority
            subset.setdefault(v['acquisition'], []).append(a['instance'])
    return [(k, p, priority.get(k, []), crigges.get(k, [])) for k, p in scans.items()
            if k in priority or k in crigges or p['usage_R17'] == 'dev_explore_R16']


def freeze(out, worktrees):
    paths = photo_paths()
    photos = {p['id']: p for p in corpus()}
    records = []
    for k, p, priorities, control in selected():
        path = historique(paths[k], worktrees)
        original = path
        src = photos[k]['source']
        if control:
            original = worktrees/'vision-lab-r7/vision-lab/data/r7/crigges/originals'/src['source_file']
        elif path.parent.name == 'work' and k in ('7245527662', '5115614018'):
            original = path.parent.parent/'media'/(k+'.jpg')
        sha = fingerprint(original)
        if sha != (src.get('source_sha256') or src.get('sha256') or p['sha256_pixels']) or fingerprint(path) != p['sha256_pixels']:
            raise ValueError(f'Empreinte de la vue {k}')
        with Image.open(original) as im:
            w, h = im.size
            exif = im.getexif().get(274, 1)
        ow, oh = (h, w) if exif in (5, 6, 7, 8) else (w, h)
        tw, th = p['dimensions_travail']
        if [round(ow*min(1, 1408/max(ow, oh))), round(oh*min(1, 1408/max(ow, oh)))] != [tw, th]:
            raise ValueError(f'Dimensions de travail de {k}')
        records.append(dict(photo=k, provenance=p['provenance'], partition=p['usage_R17'], supports=p['supports'],
            role='compatibilite_Crigges' if control else 'silhouettes_train' if priorities else
                 'developpement_occupe' if p['instances_annotees'] else 'temoin_vide',
            instances_R17=p['instances_annotees'], prioritaires_R17=priorities, controle_Crigges=control,
            regions_ignorees_R17=p['regions_ignorees'], inventaire_R17_complet=p['inventaire_complet_dans_la_vue'],
            source_locale=str(original.relative_to(worktrees)), taille_octets=original.stat().st_size, sha256_source=sha,
            fichier_reference=str(path.relative_to(worktrees)), sha256_reference=p['sha256_pixels'],
            taille_reference_octets=path.stat().st_size, dimensions_stockees=[w,h], exif=exif,
            dimensions_orientees=[ow,oh], dimensions_travail=[tw,th], echelle_oriente_travail=[tw/ow,th/oh],
            source=src))
    save(out, dict(version=1, base_R17='08e4ce04300ec33d7152534d6804fc6fb3391623', acquisitions=records))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('--worktrees', type=Path, required=True)
    args = parser.parse_args()
    freeze(args.out, args.worktrees)
