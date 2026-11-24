"""Loupes supplémentaires des corrections et des associations litigieuses."""
import argparse
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

from r14.mesurer import read,save
from r15.corpus import corpus,fingerprint
from r15.restaurer import historique
from r16.donnees import photo_paths
from r17.planches import font,source,projected,line,COLORS

BOXES={2:(438,778,543,853),5:(249,268,326,329),23:(318,342,605,381),24:(292,374,595,417),
       26:(720,507,778,692),27:(724,508,778,633)}


def render(out,worktrees=None):
    population={p['id']:p for p in corpus()}
    cases=read('r17/cas-geles.json')['cas']
    fixes={r['cas']:r for r in read('r17/corrections-v2.json')['corrections']}
    paths=photo_paths()
    out.mkdir(parents=True,exist_ok=True)
    notes=[]
    for n,box in BOXES.items():
        c=cases[n-1]; k=c['photo']; ref=c['references'][0]; path=historique(paths[k],worktrees)
        if fingerprint(path)!=c['sha256_pixels']:
            raise ValueError(k)
        im=Image.fromarray(cv.cvtColor(cv.imread(str(path)),cv.COLOR_BGR2RGB))
        scale=np.array(im.size)/c['taille_travail']
        native=np.rint(np.array(box)*np.tile(scale,2)).astype(int)
        size=np.array(box[2:])-box[:2]; ratio=min(520/size[0],330/size[1],5)
        dims=np.rint(size*ratio).astype(int)
        base=im.crop(tuple(native)).resize(tuple(dims),Image.Resampling.LANCZOS)
        f=dims/(native[2:]-native[:2])
        m=[*(scale*f),*(-native[:2]*f)]
        raw=base.copy();overlay=base.copy();d=ImageDraw.Draw(overlay)
        fix=fixes.get(c['cas'])
        def mark(q,col):
            (x,y),=projected([q],m);d.ellipse((x-5,y-5,x+5,y+5),outline=col,width=2)
        if fix:
            if fix['champ']=='axe':
                line(d,projected(fix['avant'],m),'#ff9a20');line(d,projected(fix['propose'],m),'#00fff2',True)
                mark(fix['avant'][1],'#ff9a20');mark(fix['propose'][1],'#00fff2')
            else:
                mark(fix['avant'],'#ff9a20');mark(fix['propose'],'#00fff2')
            label='Orange : annotation initiale. Cyan : proposition R17.'
        else:
            line(d,projected(ref['axe'],m),COLORS['axes'][0])
            pts=projected(np.array(c['segment_704'])/c['echelle_704'],m);line(d,pts,COLORS['segment'],True)
            for x,y in pts:d.ellipse((x-2,y-2,x+2,y+2),outline=COLORS['segment'],width=2)
            if ref['entree'] is not None:mark(ref['entree'],COLORS['entree'])
            label='Cyan : axe initial. Magenta : segment. Orange : entrée visible.'
        w,h=base.size
        sheet=Image.new('RGB',(max(2*w+24,500),h+77),'#f7f7f5');sd=ImageDraw.Draw(sheet)
        sd.text((6,4),c['cas']+' · '+label,fill='#192d38',font=font(13))
        sheet.paste(raw,(6,30));sheet.paste(overlay,(w+15,30))
        src=source(population[k])
        sd.text((6,h+38),src['credit']+' · '+src['nom_licence']+' · Détail agrandi et calques R17',fill='#192d38',font=font(13))
        name=c['cas']+'-loupe.jpg';sheet.save(out/name,quality=94,subsampling=0,optimize=True)
        notes.append(dict(cas=c['cas'],cadre_travail=box,cadre_source=native.tolist(),fichier=name,droits=src,sha256=fingerprint(out/name)))
    save(out/'details.json',notes)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path);p.add_argument('--worktrees',type=Path)
    render(**vars(p.parse_args()))
