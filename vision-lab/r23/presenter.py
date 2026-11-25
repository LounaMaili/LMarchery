"""Tables des 624 paires et figures spatiales comparatives depuis les cartes publiées."""
import argparse
import base64
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.donnees import read, save, work_to
from r19.tracer import label, node, page, save_svg
from r20.tableaux import fmt, slot, table
from r23.commun import ARMS, FOCUS, GAGNON, LYNCH, PHOTOS, ROOT, STEPS, cases, tag

ARM={'temoin':'Témoin R21','dice':'Dice R22'}
SOURCE={GAGNON:'Gagnon',LYNCH:'Lynch'}


def rank(cell):
    a,b=cell['rang_logit']
    return str(a) if a==b else f'{a}–{b}'


def tables(src,out):
    dest=out/'tableaux'
    dest.mkdir(exist_ok=True,parents=True)
    registry=read(src/'references.json')
    index=[]
    overview=[]
    competition=[]
    supports=[]
    for arm,t,k in cases():
        name=tag(arm,t,k)
        rows=read(out/'mesures'/(name+'.json'))['requetes']
        spatial,geometry=[],[]
        for ident in registry[k]['identites']:
            n=ident.split(':')[-1]
            for q,row in enumerate(rows):
                a=row['par_identite'][ident]
                best=a['meilleure_cellule_disque']
                role=' + '.join(s for ok,s in [(a['q_perte']==q,'perte'),(a['q_entree_geometrique']==q,'entrée')] if ok) or '—'
                spatial.append([n,q,role,a['cellules_disque'],fmt(a['masse_disque'],6),fmt(row['maximum_logit']['logit']),
                    fmt(best['logit']),fmt(a['ecart_logit']),rank(best),a['modes_secondaires_stricts'],
                    rank(a['meilleur_mode_secondaire']) if a['meilleur_mode_secondaire'] else '—',
                    fmt(row['pic_decode']['p'],6),fmt(a['distance_pic_px704'],2),fmt(a['perte_entree_recomposee'],5)])
                geometry.append([n,q,fmt(a['cout_hongrois']),fmt(a['BCE_masque']),fmt(a['BCE_axe']),
                    fmt(a['CE_historique']),fmt(a['presence_positive']),fmt(a['dice_doux']),fmt(a['fraction_plafond']),
                    a['sondes_axe'],a['disque_cellules_masque'],a['disque_cellules_axe_dans_masque']])
                if q==a['q_perte']:
                    overview.append([ARM[arm],t,SOURCE[k],n,slot(q),slot(a['q_entree_geometrique']),
                        fmt(a['masse_disque'],5),rank(best),fmt(a['ecart_logit']),fmt(a['distance_pic_px704'],2),
                        a['principale_autre_entree'].split(':')[-1],fmt(a['masse_principale_autre_entree'],5)])
                    if int(n) in FOCUS[k]:
                        for b in a['support']:
                            supports.append([ARM[arm],t,SOURCE[k],n,q,b['xy_grille'],fmt(b['poids'],6),fmt(b['p'],8),
                                             rank(b),fmt(-b['poids']*b['log_p_historique'],6)])
        if k==GAGNON:
            for q,row in enumerate(rows):
                c=row['concurrence_11_9']
                competition.append([ARM[arm],t,q,fmt(c['masse_11'],6),fmt(c['masse_9'],6),fmt(c['rapport_11_sur_9']),
                                    fmt(c['ecart_meilleurs_logits_11_moins_9']),c['cellules_communes'],fmt(row['masse_hors_union'],6)])
        f=dest/(name+'.md')
        f.write_text(f'# {SOURCE[k]} · {ARM[arm]} · pas {t}\n\n'
            'Chaque ligne associe une identité de référence à l’une des douze requêtes. La colonne Rôle indique l’affectation hongroise et/ou l’appariement géométrique de l’entrée. Rang 1 désigne le maximum de la grille entière.\n\n'+
            table(['Nº','q','Rôle','Cellules','Masse du disque','Logit global','Logit disque','Écart','Rang disque',
                   'Maxima secondaires','Meilleur rang secondaire','P du pic global','Distance (px)','Perte recomposée'],spatial)+
            '\n\n## Coût historique et géométrie de la même requête\n\n'+
            table(['Nº','q','Coût','BCE masque','BCE axe','CE entrée','Présence positive','Dice doux','Fraction plafond',
                   'Sondes / 21','Cellules masque dans le disque','Cellules axe dans le masque et le disque'],geometry)+
            f'\n\n[Valeurs complètes, poids bilinéaires et rangs des cellules](../mesures/{name}.json)\n')
        index.append([ARM[arm],t,SOURCE[k],f'[Toutes les paires](tableaux/{name}.md)',f'[Détail JSON](mesures/{name}.json)',
                      f'[Cartes natives, q0 à q11](cartes/{name})'])
    (out/'tableaux.md').write_text('# Résultats des treize entrées connues\n\n'+
        table(['Bras','Pas','Provenance','Tableaux','Mesures','Logits et probabilités'],index)+
        '\n\n## Distribution de la requête affectée pour la perte\n\nLa distance et la masse portent sur la requête de perte. La requête qui couvre géométriquement l’entrée peut être différente. La dernière paire de colonnes donne, parmi les autres entrées connues, le disque de plus grande masse de cette même requête.\n\n'+
        table(['Bras','Pas','Provenance','Nº','q perte','q entrée','Masse du disque','Rang disque','Écart logit',
               'Distance du pic (px)','Autre nº','Masse autre'],overview)+'\n')
    (out/'concurrence.md').write_text('# Gagnon · Concurrence entre les entrées 11 et 9\n\n'+
        table(['Bras','Pas','q','Masse nº 11','Masse nº 9','Rapport 11/9','Écart logits 11 moins 9','Cellules communes','Masse hors des huit disques'],competition)+'\n')
    (out/'supports.md').write_text('# Support bilinéaire des trois entrées détaillées\n\nRequêtes effectivement affectées pour la perte. Les valeurs complètes des autres requêtes sont disponibles dans les tableaux par état. La somme des contributions reproduit la perte d’entrée à l’arrondi du calcul float32 près.\n\n'+
        table(['Bras','Pas','Source','Nº','q','Cellule (x, y)','Poids','Probabilité','Rang logit','Contribution à la perte'],supports)+'\n')


def heat(values):
    color=np.rint(255*np.clip((np.log10(np.maximum(values.astype(float),1e-12))+12)/12,0,1)).astype(np.uint8)
    return cv.applyColorMap(color,cv.COLORMAP_VIRIDIS)


def raster(root,pixels,x,y,w,h):
    good,png=cv.imencode('.png',pixels,[cv.IMWRITE_PNG_COMPRESSION,9])
    if not good:
        raise ValueError('Image non enregistrée')
    uri='data:image/png;base64,'+base64.b64encode(png).decode('ascii')
    # xlink:href permet également le rendu des PNG par les lecteurs SVG 1.1.
    elem=node(root,'image',x=x,y=y,width=w,height=h,preserveAspectRatio='none',style='image-rendering:pixelated')
    elem.set('{http://www.w3.org/1999/xlink}href',uri)


def chart(src,out,k,n,fixed):
    ident=f'{k}:{n}'
    spec=read(src/'references.json')[k]
    grid=np.asarray(spec['grille'])
    view=np.asarray(spec['repere_704'])
    target=work_to(spec['identites'][ident]['xy_704'],view,grid)
    start=np.clip(np.floor(target+.5).astype(int)-10,0,grid-21)
    sub='requête affectée à la flèche' if fixed is None else f'requête q{fixed} dans les deux bras'
    root=page(1050,850,f'{SOURCE[k]} nº {n} · {sub}',
              'Probabilité de cellule sur la grille entière. Chaque ligne compare le témoin R21 (à gauche) et Dice R22 (à droite).')
    marks=[]
    for it,t in enumerate(STEPS):
        for ia,arm in enumerate(ARMS):
            name=tag(arm,t,k)
            records=read(out/'mesures'/(name+'.json'))['requetes']
            q=records[0]['par_identite'][ident]['q_perte'] if fixed is None else fixed
            rec=records[q]
            pair=rec['par_identite'][ident]
            with np.load(src/'cartes'/name/f'q{q:02}.npz') as bundle:
                p=bundle['p']
            origin=30+ia*510
            top=98+it*333
            label(root,origin,top,f'{ARM[arm]} · pas {t} · q{q}',17,'bold')
            label(root,origin,top+22,'Grille entière',12)
            label(root,origin+253,top+22,'Zoom de 21 × 21 cellules',12)
            other=f'{k}:{9 if n==11 else 11}' if k==GAGNON else pair['principale_autre_entree']
            second=work_to(spec['identites'][other]['xy_704'],view,grid)
            offsets=[np.zeros(2),start]
            sizes=[grid,np.array([21,21])]
            for j,(offset,size) in enumerate(zip(offsets,sizes,strict=True)):
                x=origin+(253 if j else 0)
                w=193 if j else 218
                h=w*size[1]/size[0]
                y=top+32+(218-h)/2
                x0,y0=offset.astype(int)
                image=p[y0:y0+size[1],x0:x0+size[0]]
                raster(root,heat(image),x,y,w,h)
                factor=np.array([w,h])/size
                def position(g):
                    return np.array([x,y])+(np.asarray(g)-offset+.5)*factor
                at=position(target)
                node(root,'ellipse',cx=at[0],cy=at[1],rx=10*grid[0]/view[0]*factor[0],
                     ry=10*grid[1]/view[1]*factor[1],fill='none',stroke='#ff8848',stroke_width=2)
                if np.all((second>=offset)&(second<offset+size)):
                    sx,sy=position(second)
                    node(root,'ellipse',cx=sx,cy=sy,rx=10*grid[0]/view[0]*factor[0],
                         ry=10*grid[1]/view[1]*factor[1],fill='none',stroke='#8efff0',stroke_width=1.8,stroke_dasharray='3 2')
                peak=np.asarray(rec['pic_decode']['xy_grille'])
                if np.all((peak>=offset)&(peak<offset+size)):
                    px,py=position(peak)
                    for width,color in [(3.5,'#1c2330'),(1.6,'#ffffff')]:
                        node(root,'path',d=f'M {px-5} {py} h 10 M {px} {py-5} v 10',fill='none',stroke=color,stroke_width=width)
            label(root,origin,top+273,f"Masse {100*pair['masse_disque']:.2f} % · Rang {rank(pair['meilleure_cellule_disque'])} · Pic à {pair['distance_pic_px704']:.2f} px".replace('.',','),13,'bold')
            label(root,origin,top+291,f"Disque concurrent nº {other.split(':')[-1]} · Masse {100*rec['par_identite'][other]['masse_disque']:.2f} %".replace('.',','),12)
            marks.append(dict(concurrent=other,masse_concurrente=rec['par_identite'][other]['masse_disque'],bras=arm,pas=t,q=q,fichier=f'cartes/{name}/q{q:02}.npz',
                         origine_zoom=start.tolist(),xy_cible_grille=target.tolist(),pic=rec['pic_decode']['xy_grille'],
                         masse=pair['masse_disque'],rang=pair['meilleure_cellule_disque']['rang_logit']))
    label(root,30,755,'Le cercle orange délimite la cible à 10 px, le cercle pointillé la concurrente et la croix blanche le maximum décodé.',13)
    label(root,30,788,'Probabilité de cellule',13,'bold')
    for i in range(120):
        color=heat(np.array([[10**(-12+12*i/119)]],dtype=float))[0,0][::-1]
        node(root,'rect',x=214+4*i,y=774,width=4.1,height=13,fill='#%02x%02x%02x'%tuple(color))
    for i,s in enumerate(['≤ 10⁻¹²','10⁻⁹','10⁻⁶','10⁻³','1']):
        label(root,214+119*i,809,s,12,anchor='middle')
    key=f'{SOURCE[k].lower()}-{n}-'+('affectee' if fixed is None else f'q{fixed}')
    save_svg(root,out/'figures'/(key+'.svg'))
    return key,marks


def render(src,out):
    tables(src,out)
    (out/'figures').mkdir(exist_ok=True)
    data={}
    for k in PHOTOS:
        for n in FOCUS[k]:
            key,marks=chart(src,out,k,n,None)
            data[key]=marks
    for k,n,qs in [(GAGNON,11,[7,11]),(LYNCH,4,[2,10])]:
        for q in qs:
            key,marks=chart(src,out,k,n,q)
            data[key]=marks
    save(out/'figures/donnees.json',dict(zoom_cellules=21,echelle_log10=[-12,0],planches=data))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--source',type=Path,default=ROOT)
    a=p.parse_args()
    render(a.source,a.out)
