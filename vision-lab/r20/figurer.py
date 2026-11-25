"""Figures de R20 depuis les archives publiques et les deux panneaux train de R18."""
import argparse
import json
from math import ceil
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

from r17.planches import font
from r19.donnees import R18, fingerprint, read, save, work_to
from r19.planches import INK, PAPER, TEAL, PINK, mark, paint
from r19.tracer import COLORS, draw, label, node, page, panel, save_svg
from r20.corpus import ROOT, references


def counts(folder, summary):
    root=page(940,335,'R19 sur les neuf photos d’apprentissage',
              'Les masques, les axes et les entrées sont appariés séparément. Le triplet exige une seule requête pour la même flèche.')
    data={}
    for j,(key,title,n) in enumerate([('masques_apparies','Masques appariés',31),('axes_apparies','Axes admis retrouvés',31),
                                  ('contacts_couverts','Entrées avant seuil',26),('triplets','Triplets avant seuil',26)]):
        x=33+224*j
        label(root,x,108,title,15,'bold')
        data[key]={}
        for i,(s,total) in enumerate(summary['A'].items()):
            value=total[key]; data[key][s]={'nombre':value,'admis':n}
            y=139+49*i
            label(root,x,y+5,s,12)
            node(root,'rect',x=x+43,y=y-6,width=103,height=12,fill='#e1e5e4',rx=2)
            node(root,'rect',x=x+43,y=y-6,width=103*value/n,height=12,fill=COLORS[s],rx=2)
            label(root,x+151,y+5,f'{value}/{n}',12)
    means='  ·  '.join(f"{v['somme_dice_masques_R19']/v['masques_admis']:.3f}".replace('.',',') for v in summary['A'].values())
    label(root,33,303,'Un masque peut être apparié avec un faible Dice. Dice moyen par graine  '+means+'.')
    save_svg(root,folder/'train.svg')
    return data


def trajectory(folder,archives,photo,steps):
    rows=[json.loads(s) for s in (archives/'B/progression.jsonl').read_text().splitlines()]
    data=[dict(etat=r['etat_avant'],**{k:r[k] for k in ('masque','axe','entree','presence','total','norme_gradient')}) for r in rows]
    last=archives/'B'/f'{steps[-1]:04d}'
    c=read(last/'pertes.json')[photo]['pertes']
    data.append(dict(etat=steps[-1],**c,total=c['masque']+c['axe']+c['entree']+.25*c['presence'],
                     norme_gradient=read(last/'controle.json')['gradient_avant_ecretage']))
    root=page(1000,602,'Une photo répétée jusqu’au premier contrôle réussi',
              'Une valeur par état de 0 à 96. Chaque panneau a sa propre échelle. Les points pleins marquent les deux contrôles.')
    for i,(k,title) in enumerate([('total','Perte pondérée'),('masque','Perte du masque'),('axe','Perte de l’axe'),
                                  ('entree','Perte de l’entrée'),('presence','Perte de présence'),('norme_gradient','Gradient avant écrêtage')]):
        x=49+(i%3)*329
        y=117+(i//3)*212
        maximum=max(d[k] for d in data)
        top=ceil(maximum*2)/2 if maximum<2 else ceil(maximum)
        fx,fy=panel(root,x,y,265,140,title,steps[-1],top,[0,24,48,72,96],[0,top/2,top])
        draw(root,[(fx(d['etat']),fy(d[k])) for d in data],COLORS['9301'])
        if k=='norme_gradient':
            draw(root,[(fx(0),fy(5)),(fx(steps[-1]),fy(5))],'#ac521b',1.8,'5 3')
        for d in (data[0],data[-1]):
            node(root,'circle',cx=fx(d['etat']),cy=fy(d[k]),r=3,fill='#23444c')
    label(root,30,553,'Abscisse en mises à jour achevées. Sur la norme, la ligne pointillée marque le seuil 5.')
    clipped=sum(r['ecrete'] for r in rows)
    label(root,30,577,f'{clipped} gradients écrêtés sur {len(rows)} mises à jour. Le gradient du contrôle terminal est mesuré sans mise à jour supplémentaire.')
    save_svg(root,folder/'apprentissage.svg')
    return data


def tv_color(v):
    pale=np.array([245,245,225]); dark=np.array([15,93,116])
    return '#%02x%02x%02x'%tuple(np.rint(pale*(1-v)+dark*v).astype(int))


def similarities(folder,archives,steps):
    root=page(930,525,'Champs d’entrée des douze requêtes',
              'La couleur représente la distance de variation totale entre deux softmax. Un cercle marque une même cellule de maximum.')
    data={}
    for i,step in enumerate(steps):
        result=read(archives/'B'/f'{step:04d}'/'similarites.json')
        x=78+i*455;y=132;tile=23
        label(root,x,100,f'État après {step} mises à jour',16,'bold')
        for j in range(12):
            label(root,x+j*tile+11,y-8,str(j),10,anchor='middle')
            label(root,x-8,y+j*tile+16,str(j),10,anchor='end')
            node(root,'rect',x=x+j*tile,y=y+j*tile,width=tile-1,height=tile-1,fill='#c8ced0')
        data[str(step)]=[]
        for row in result['paires']:
            v=row['entree'];dist=v['distance_variation_totale']
            data[str(step)].append([row['q'],row['r'],dist,v['meme_maximum'],v['correlation_centree']])
            for a,b in [(row['q'],row['r']),(row['r'],row['q'])]:
                x0=x+a*tile;y0=y+b*tile
                node(root,'rect',x=x0,y=y0,width=tile-1,height=tile-1,fill=tv_color(dist))
                if v['meme_maximum']:
                    node(root,'circle',cx=x0+11,cy=y0+11,r=4,stroke='#633213',stroke_width=1.8,fill='none')
        label(root,x,434,f"{result['paires_meme_pic']} paires à maximum commun sur {len(result['paires'])}",12)
    for j in range(101):
        node(root,'rect',x=79+j*2,y=463,width=3,height=10,fill=tv_color(j/100))
    label(root,78,495,'0 · identiques',11);label(root,280,495,'1 · disjointes',11,anchor='end')
    label(root,352,473,'Les indices de ligne et de colonne désignent q0 à q11.',12)
    save_svg(root,folder/'champs-entree.svg')
    return data


def overlays(folder,archives,photo,step):
    ref=references()[photo]
    b=read(archives/'B'/f'{step:04d}'/'predictions.json')[photo]
    report=read(folder.parent/'mesures'/f'B-{step:04d}.json')
    bits=cv.imread(str(archives/'B'/f'{step:04d}'/'masques'/(photo+'.png')),cv.IMREAD_UNCHANGED)
    sheet=Image.new('RGB',(1306,594),PAPER)
    pen=ImageDraw.Draw(sheet)
    pen.text((14,10),'Les deux triplets au contrôle 96',fill=INK,font=font(26))
    pen.text((14,51),'Référence R18 · masque, tronçons, entrée',fill=INK,font=font(17))
    pen.text((660,51),'Requête R20 · masque, fragments décodés, maximum d’entrée',fill=INK,font=font(17))
    records=read(R18/'figures/index.json')
    data=[]
    for i,(name,entry) in enumerate(ref['entries'].items()):
        stamp=next(v for v in records if v['photo']==photo and v['instance']==name)
        jpg=R18/'figures'/stamp['fichier']
        if fingerprint(jpg)!=stamp['sha256']:raise ValueError(jpg)
        x,y,w,h=stamp['pixels']
        with Image.open(jpg) as im:
            raw=im.crop((x,y,x+w,y+h)).convert('RGB')
        m=np.array(stamp['travail_vers_panneau']);inv=cv.invertAffineTransform(m)
        yy,xx=np.indices((h,w))
        work=np.stack((xx*inv[0,0]+yy*inv[0,1]+inv[0,2],xx*inv[1,0]+yy*inv[1,1]+inv[1,2]),axis=-1)
        def sample(plane):
            grid=np.array(plane.shape[1::-1])
            pos=np.floor((work+.5)*grid/ref['size']).astype(int).clip(0,grid-1)
            return plane[pos[:,:,1],pos[:,:,0]]
        def where(xy):
            nat=work_to(xy,ref['view'],ref['size'])
            return tuple(m[:,:2]@nat+m[:,2])
        q=report['identites'][name]['conjoint_q']
        scores=report['paires_par_identite'][name][q]
        for j,(layer,color,lines,pos,kind) in enumerate([
                (sample(ref['mask'])==ref['ids'][name],TEAL,ref['axes'][name],entry,'circle'),
                ((sample(bits)&(1<<q))!=0,PINK,b['sorties'][q]['segments'],b['sorties'][q]['xy'],'cross')]):
            img=paint(raw,[(layer,color)])
            brush=ImageDraw.Draw(img)
            for line in lines:
                brush.line([where(pt) for pt in line],fill='#17282e',width=3)
                brush.line([where(pt) for pt in line],fill='#fbdc65',width=1)
            mark(brush,where(pos),kind,'#ffffff','')
            sheet.paste(img,(14+j*646,86+i*220))
        caption=f"Flèche {ref['ids'][name]}     q{q}     Dice {scores['dice']:.3f}     Axe {scores['sondes_axe']}/21     Entrée {scores['erreur_entree_px704']:.2f} px"
        pen.text((14,86+i*220+h+5),caption,fill=INK,font=font(16))
        data.append(dict(instance=name,q=q,mesures=scores,panneau_R18=stamp['fichier'],sha256_source=stamp['sha256']))
    pen.text((14,535),'Chaque paire reprend la même fenêtre de détail R18. Les fragments peuvent se prolonger hors du fût. Les zones ignorées sont exclues du Dice.',fill=INK,font=font(15))
    pen.text((14,563),'Photo de renatomitra · CC BY-SA 2.0 · recadrages R18 et superpositions R20, diffusés sous la même licence.',fill=INK,font=font(14))
    sheet.save(folder/'triplets.png',optimize=True)
    return data


def render(out,archives):
    folder=out/'figures';folder.mkdir(exist_ok=True,parents=True)
    summary=read(out/'mesures/synthese.json')
    steps=list(map(int,summary['B']));photo=summary['decision']['photo']
    data=dict(train=counts(folder,summary),trajectoire=trajectory(folder,archives,photo,steps),
              paires_q_r_TV_meme_maximum_correlation=similarities(folder,archives,steps),
              triplets=overlays(folder,archives,photo,steps[-1]))
    save(folder/'donnees.json',data)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--archives',type=Path,default=ROOT/'archives')
    a=p.parse_args();render(a.out,a.archives)
