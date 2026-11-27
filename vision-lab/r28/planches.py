"""Planches de cartes indexées du train et de masques natifs, sans photographie."""
import math
import cv2
import numpy as np

from r28.commun import ROOT, SEEDS, STEPS, read, tag
from r28.partiel import union_masks


# Palette en RVB. La classe 255 reste distincte, même dans une prédiction.
PALETTE = {
    'inconnu': '#e0d8ef', 'fond': '#e3eee8', 'positif': '#93bda4',
    'identite_reference': '#187356', 'inconnu_actif': '#8456ad',
    'fond_actif': '#cb4143', 'propre': '#006344', 'autre': '#e29a30',
}
INK = (57, 48, 39)
MUTED = (100, 88, 77)
PAPER = (255, 255, 255)
FONT = cv2.FONT_HERSHEY_SIMPLEX
WIDTH, PANEL_W, PANEL_H = 1150, 356, 192
LEFT, GAP = 24, 17


def color(key):
    value = PALETTE[key][1:]
    return tuple(int(value[i:i+2], 16) for i in (4, 2, 0))


def label(canvas, value, xy, scale=.52, ink=INK, thickness=1):
    cv2.putText(canvas, value, xy, FONT, scale, ink, thickness, cv2.LINE_AA)


def paint(labels, covered=None, own=None, identity=None):
    canvas = np.full((*labels.shape, 3), color('positif'), np.uint8)
    canvas[labels == 0] = color('fond')
    canvas[labels == 255] = color('inconnu')
    if identity is not None:
        canvas[labels == identity] = color('identite_reference')
    if covered is not None:
        canvas[covered & (labels == 0)] = color('fond_actif')
        canvas[covered & (labels == 255)] = color('inconnu_actif')
        canvas[covered & (labels != 0) & (labels != 255) & ~own] = color('autre')
        canvas[own] = color('propre')
    return canvas


def tile(sheet, x, y, img):
    h, w = img.shape[:2]
    scale = min(PANEL_W / w, PANEL_H / h)
    w, h = max(1, round(w*scale)), max(1, round(h*scale))
    x0, y0 = x + (PANEL_W-w)//2, y + (PANEL_H-h)//2
    cv2.rectangle(sheet, (x, y), (x+PANEL_W, y+PANEL_H), (248,247,247), -1)
    sheet[y0:y0+h, x0:x0+w] = cv2.resize(img, (w,h), interpolation=cv2.INTER_NEAREST_EXACT)
    cv2.rectangle(sheet, (x0,y0), (x0+w-1,y0+h-1), (177,172,167), 1)


def sheet(title, subtitle, labels, rows):
    bottom = 126 + rows * 236
    canvas = np.full((bottom+111,WIDTH,3), PAPER, np.uint8)
    label(canvas,title,(LEFT,39),.88,thickness=2)
    label(canvas,subtitle,(LEFT,66),.48,MUTED)
    for col, name in enumerate(labels):
        label(canvas,name,(LEFT+col*(PANEL_W+GAP),100),.58,thickness=1)
    cv2.line(canvas,(LEFT,bottom-7),(WIDTH-LEFT,bottom-7),(220,216,212))
    legend = [
        [('inconnu','255 inconnu'),('fond','Fond certifie'),('positif','Identite connue'),
         ('identite_reference','Identite detaillee')],
        [('inconnu_actif','Inconnu couvert'),('fond_actif','Fond couvert'),
         ('propre','Identite appariee / visee'),('autre','Autre identite couverte')],
    ]
    for row, entries in enumerate(legend):
        y=bottom+16+row*29
        for col,(key, name) in enumerate(entries):
            x=LEFT+col*278
            cv2.rectangle(canvas,(x,y-10),(x+17,y+5),color(key),-1)
            cv2.rectangle(canvas,(x,y-10),(x+17,y+5),MUTED,1)
            label(canvas,name,(x+24,y+3),.46)
    label(canvas,'Cartes indexees R18 et sorties R27. Mesures a la resolution native.',(LEFT,bottom+86),.46,MUTED)
    return canvas


def images(seed, step, photo, catalog):
    rec = catalog[tag(seed,step,photo)]
    with np.load(rec['source'], allow_pickle=False) as raw:
        learned = raw['masques']
    full = cv2.imread(str(ROOT/'supports'/rec['fichier']),cv2.IMREAD_UNCHANGED)
    return learned, full


def export(out, filename, canvas):
    path = out/filename
    assert cv2.imwrite(str(path),canvas,[cv2.IMWRITE_PNG_COMPRESSION,8])
    return filename


def overview(out, photo, spec, states, catalog):
    labels = cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
    ref = paint(labels)
    canvas=sheet(f"{photo}  |  {spec['provenance']}",'Chaque ligne montre la meme vue entiere. Les deux colonnes de droite partagent les q apparies par le masque appris.',
                 ['Reference R18','Masques appris separes','Supports pleins des memes q'],4)
    data=[]
    for row,(seed,step) in enumerate((s,t) for s in SEEDS for t in STEPS):
        ids=states[tag(seed,step,photo)]['identites']
        qs={n:v['appris']['q_separe'] for n,v in ids.items() if v['appris']['q_separe'] is not None}
        bits=images(seed,step,photo,catalog)
        y=126+row*236
        label(canvas,f"{seed} / {step}     {len(qs)} sur {len(ids)} identites separees", (LEFT,y),.47)
        tile(canvas,LEFT,y+9,ref)
        for col,b in enumerate(bits,1):
            area,own=union_masks(labels,b,ids,'q_separe')
            tile(canvas,LEFT+col*(PANEL_W+GAP),y+9,paint(labels,area,own))
        data.append(dict(graine=seed,pas=step,q_par_identite=qs))
    return dict(fichier=export(out,f'photo-{photo}.png',canvas),photo=photo,provenance=spec['provenance'],
                dimensions=spec['dimensions'],etats=data)


def bounds(labels, controls):
    boxes=[]
    for state in controls.values():
        boxes.extend([state['boite_reference'],state['meilleur_dice']['boite'],state['meilleure_boite']['boite']])
    x1,y1=np.min(np.array(boxes)[:,:2],axis=0)
    x2,y2=np.max(np.array(boxes)[:,2:],axis=0)
    pad=max(8,math.ceil(.06*max(x2-x1,y2-y1)))
    return [max(0,math.floor(x1)-pad),max(0,math.floor(y1)-pad),
            min(labels.shape[1],math.ceil(x2)+pad),min(labels.shape[0],math.ceil(y2)+pad)]


def transition_board(out, row, spec, catalog):
    photo, name, seed = row['photo'],row['identite'],row['graine']
    controls=row['controles']
    native=cv2.imread(spec['masque'],cv2.IMREAD_UNCHANGED)
    x1,y1,x2,y2=bounds(native,controls)
    labels=native[y1:y2,x1:x2]
    ident=spec['instances'][name]['indice']
    canvas=sheet(f"{name}  |  graine {seed}  |  {row['sens']}",
                 f"{spec['provenance']}   -   meme fenetre native aux deux pas   ({x1}, {y1}) a ({x2}, {y2})",
                 ['Reference de cette identite','Sortie au meilleur Dice','Support plein du meme q'],2)
    data=[]
    for row_id,step in enumerate(STEPS):
        state=controls[str(step)]
        best=state['meilleur_dice']
        q=best['q']
        bits=images(seed,step,photo,catalog)
        y=126+row_id*236
        full_dice=state['couples'][q]['dice_plein']
        label(canvas,f"Pas {step}   |   seuil {state['seuil_dice']:.4f}",(LEFT,y),.46)
        label(canvas,f"q{q}   score {best['score']:.4f}   Dice {best['dice']:.4f}",(LEFT+PANEL_W+GAP,y),.46)
        label(canvas,f"q{q}   Dice {full_dice:.4f}",(LEFT+2*(PANEL_W+GAP),y),.46)
        tile(canvas,LEFT,y+9,paint(labels,identity=ident))
        for col,b in enumerate(bits,1):
            m=(b[y1:y2,x1:x2] & (1<<q))!=0
            tile(canvas,LEFT+col*(PANEL_W+GAP),y+9,paint(labels,m,m & (labels==ident),ident))
        data.append(dict(pas=step,q=q,score=best['score'],boite=best['boite'],dice=best['dice'],
                         dice_plein=full_dice,seuil=state['seuil_dice'],meilleure_boite=state['meilleure_boite']))
    basename=f"transition-{seed}-{name.replace(':','-')}.png"
    return dict(fichier=export(out,basename,canvas),photo=photo,provenance=spec['provenance'],
                graine=seed,identite=name,fenetre_native_xyxy=[x1,y1,x2,y2],sens=row['sens'],etats=data)


def boards(out, photos, states, transitions):
    catalog=read(ROOT/'supports/index.json')['supports']
    overviews=[overview(out,k,v,states,catalog) for k,v in photos.items()]
    details=[]
    for row in transitions:
        if row['sens']=='stable':
            continue
        seed,photo,n=row['graine'],row['photo'],row['identite']
        controls={str(step):states[tag(seed,step,photo)]['identites'][n] for step in STEPS}
        details.append(transition_board(out,dict(row,controles=controls),photos[photo],catalog))
    return dict(palette_RVB=PALETTE,photos=overviews,transitions=details)
