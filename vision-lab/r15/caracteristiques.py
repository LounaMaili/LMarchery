"""Produire les entrées photographiques du vérificateur, sans ajustement ni décision."""
import csv
from hashlib import sha256
from pathlib import Path
import sys

import cv2 as cv
import numpy as np

from r7.prepare import resized
from r12.geometrie import repere, samples, continuity, direction, surface
from r13.segments import reconstruct, length, frame, profile, sample, intervals
from r15.corpus import LAB, corpus, bank, fingerprint
from r15.recherche import MEDIA, read, save

NAMES = ('longueur_px','deux_contours','largeur_mediane_px','largeur_mad_px',
         'contraste_transversal','continuite_longitudinale','moyenne_masque','probabilite_tube_moyenne',
         'ecart_aspect_bouts')
KEYS = ('photo','partition','rang_longueur','rang_sortie_R13','prelevement_documentaire',
        'x0','y0','x1','y1')


def eligible():
    documentary = {s['photo'] for s in bank()}
    return [p for p in corpus() if p['partition']=='dev_indep' or
            (p['partition']=='train' and (p['id'] in documentary or any(t['axe'] for t in p['tubes'])))]


def obtain(board, support):
    # Les variables locales sont lues à la sortie du générateur inchangé.
    # La largeur a besoin des deux contours constitutifs, avant réduction à l’axe.
    saved = {}
    def observe(call, event, value):
        if event=='return' and call.f_code is reconstruct.__code__:
            saved.update({key:call.f_locals.get(key,[]) for key in ('candidates','fragments','pairs')})
    previous = sys.getprofile()
    try:
        sys.setprofile(observe)
        selected = reconstruct(board, support)
    finally:
        sys.setprofile(previous)
    pairs = {axis.tobytes():(saved['fragments'][i],saved['fragments'][j]) for _,i,j,axis in saved['pairs']}
    ordered = sorted(saved['candidates'],key=lambda item:-length(item[0]))
    return selected, ordered, pairs


def widths(contours):
    if contours is None:
        return None, None
    a,b = contours
    u,n,lo,hi,_ = intervals(a,b)
    positions = np.linspace(max(0,lo),min(length(a),hi),11)
    along = (b-a[0]) @ u
    across = (b-a[0]) @ n
    if along[0]>along[1]:
        along,across = along[::-1],across[::-1]
    w = np.abs(np.interp(positions,along,across))
    median = float(np.median(w))
    return median,float(np.median(abs(w-median)))


def values(axis, pair, contours, board, gray, mask, support, probabilities):
    median,mad = widths(contours)
    u = direction(axis)
    flanks = profile(gray,axis,6)
    center = np.median(sample(gray,np.linspace(*axis,13)))
    ends = [surface(axis[0],-u,board),surface(axis[1],u,board)]
    return (float(length(axis)),int(pair),median,mad,float(min(abs(flanks-center))),
            continuity(axis,support),float(sample(mask,samples(axis)).mean()),
            float(sample(probabilities,samples(axis)).mean()),float(abs(ends[0]-ends[1])))


def write_model(name, model, photos, paths, documentary, out):
    from r7.model import full_maps
    records = []
    csv_path = out/(name+'.csv')
    with csv_path.open('w',newline='') as fh:
        writer = csv.writer(fh,lineterminator='\n')
        writer.writerow((*KEYS,*NAMES))
        for row in photos:
            k = row['id']
            path = paths[row['sha256_pixels']]
            if fingerprint(path)!=row['sha256_pixels']:
                raise ValueError(k)
            im = resized(cv.imread(str(path)))
            if list(im.shape[1::-1])!=row['taille']:
                raise ValueError(f'Repère {k}')
            maps = full_maps(model,im)
            scale,board,mask,support = repere(im,maps[1])
            gray = cv.cvtColor(board,cv.COLOR_BGR2GRAY)
            probs = cv.resize(maps[1],tuple(board.shape[1::-1]),interpolation=cv.INTER_AREA)
            selected,ordered,pairs = obtain(board,support)
            index = {a.tobytes():i for i,a in enumerate(selected)}
            if len(index)!=len(selected) or len({a.tobytes() for a,_ in ordered})!=len(ordered):
                raise ValueError('Rang de segment ambigu')
            documentary_ranks = {s['rang']:s for s in documentary.get(k,[])}
            if not set(documentary_ranks)<=set(range(len(ordered))):
                raise ValueError('Rang documentaire absent')
            written = paired = 0
            for rank,(axis,pair) in enumerate(ordered):
                key = axis.tobytes()
                doc = documentary_ranks.get(rank)
                if doc and (doc['paire']!=pair or not np.array_equal(axis/scale,doc['axe_px_travail'])):
                    raise ValueError(f'Parité pré-tri {k}/{rank}')
                if key not in index and not doc:
                    continue
                cols = values(axis,pair,pairs[key] if pair else None,board,gray,mask,support,probs)
                if not np.isfinite([v for v in cols if v is not None]).all():
                    raise ValueError('Valeur non finie')
                writer.writerow([k,row['partition'],rank,index.get(key),int(doc is not None),
                    *(format(v,'.12g') for v in axis.ravel()),
                    *(format(v,'.10g') if v is not None else '' for v in cols)])
                written += 1
                paired += int(pair)
            records.append(dict(photo=k,partition=row['partition'],provenance=row['provenance'],
                carte_tube_sha256=sha256(maps[1].tobytes()).hexdigest(),candidats_avant_tri=len(ordered),
                sorties_R13=len(selected),preleves=len(documentary_ranks),lignes=written,
                largeur_mesuree=paired,largeur_non_observable=written-paired))
    return dict(fichier=csv_path.name,sha256=fingerprint(csv_path),photos=records)


def main(out):
    import torch
    from r7.model import ContactNet
    cfg = read(LAB/'configuration.json')
    manifest = read(LAB/'manifeste-scelle.json')
    if any(fingerprint(k)!=v['sha256'] for k,v in manifest['pieces'].items()):
        raise ValueError('Le manifeste a changé')
    photos = eligible()
    if [p['id'] for p in photos]!=cfg['photos'] or len(photos)*len(cfg['poids'])!=cfg['budget_passages']:
        raise ValueError('Population différente du gel')
    paths = {r['sha256']:Path(r['emplacement']) for r in read('r14/inventaire-pixels.json')}
    paths.update({p['sha256_pixels']:MEDIA/p['source']['fichier_travail'] for p in read(LAB/'lectures.json')['photos']})
    documentary = {}
    for s in bank():
        documentary.setdefault(s['photo'],[]).append(s)
    out.mkdir(parents=True,exist_ok=True)
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    report = {}
    for weight in cfg['poids']:
        path = Path(weight['emplacement'])
        if fingerprint(path)!=weight['sha256']:
            raise ValueError(path.name)
        torch.manual_seed(int(path.stem.split('-')[1]))
        model = ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(path,weights_only=True,map_location='cpu'))
        model.eval()
        report[path.stem] = write_model(path.stem,model,photos,paths,documentary,out)
        print(path.stem,len(photos),'photos',flush=True)
    save(out/'index.json',dict(manifeste_sha256=manifest['sha256'],colonnes_entree=NAMES,
        nombre_passages=sum(len(v['photos']) for v in report.values()),poids=report))


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    main(parser.parse_args().out)
