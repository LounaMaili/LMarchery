"""Prélever les cinq rangs documentaires R14 sur chaque nouvelle acquisition."""
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

from r7.prepare import resized
from r12.geometrie import repere
from r13.segments import length
from r14.lire import before_scoring, panel
from r15.recherche import ROOT, MEDIA, read, save


def main():
    out = ROOT / 'prelevements'
    out.mkdir(parents=True, exist_ok=True)
    cv.setNumThreads(1)
    samples = []
    for row in read('r15/lectures.json')['photos']:
        key = row['id']
        image = resized(cv.imread(str(MEDIA / row['source']['fichier_travail'])))
        if list(image.shape[1::-1]) != row['taille']:
            raise ValueError(key)
        scale, board, _, _ = repere(image, np.zeros((1, 1), np.uint8))
        ordered = sorted(before_scoring(board), key=lambda item: -length(item[0]))
        ranks = sorted({(len(ordered) - 1) * j // 4 for j in range(5)}) if ordered else []
        plate = Image.new('RGB', (610, 145 * max(1, len(ranks))), 'white')
        for j, rank in enumerate(ranks):
            axis, pair = ordered[rank]
            plate.paste(panel(board, axis, f'{key}   rang {rank}'), (0, j * 145))
            samples.append(dict(photo=key, rang=rank, provenance=row['provenance'],
                                partition=row['partition'], paire=pair, axe_px_travail=(axis / scale).tolist()))
        plate.save(out / (key + '.jpg'), quality=94)
        check = Image.fromarray(cv.cvtColor(image, cv.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(check)
        for i,tube in enumerate(row['tubes'],1):
            p,q = (tuple(pt) for pt in tube['axe'])
            draw.line((p,q),fill='#09d48e',width=1)
            if tube['entree']:
                x,y = tube['entree']
                draw.ellipse((x-4,y-4,x+4,y+4),outline='#fc009a',width=1)
                draw.text((x+4,y-10),str(i),fill='#f800ae')
        check.thumbnail((720,580))
        check.save(out / (key + '-traits.jpg'), quality=92)
    save(out / 'candidats.json', samples)


if __name__ == '__main__':
    main()
