"""Régénérer les tableaux, figures et inventaire à partir des données du diagnostic."""
import argparse
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, indent, tostring

import cv2

from r28.commun import ROOT, REFERENCE, GEL, INPUTS, SEEDS, STEPS, digest, read, save, tag
from r28.planches import boards
from r28.tableaux import comparisons, identities, table, transitions, write


def svg_el(parent, element, **attrs):
    return SubElement(parent, element, {k.replace('_','-'):str(v) for k,v in attrs.items()})


def svg_text(root, x, y, label, size=14, **attrs):
    obj=svg_el(root,'text',x=x,y=y,font_size=size,**attrs)
    obj.text=str(label)
    return obj


def chart(out, rows):
    root=Element('svg',dict(xmlns='http://www.w3.org/2000/svg',width='820',height='405',
            viewBox='0 0 820 405',role='img',**{'aria-labelledby':'title desc'}))
    svg_el(root,'title',id='title').text='Identités séparées et borne globale'
    svg_el(root,'desc',id='desc').text='Quatre contrôles du train R27. Colonnes groupées pour le témoin plein, le masque appris et la borne un à un.'
    svg_el(root,'rect',width=820,height=405,fill='#ffffff')
    g=svg_el(root,'g',font_family='sans-serif',fill='#263540')
    svg_text(g,26,35,'Identités séparées sur 31',25,font_weight='bold')
    svg_text(g,26,62,'Chaque groupe reprend les douze mêmes boîtes finales par photo.',15)
    series=[('separes_plein','Grille pleine','#8c729d'),('separes_appris','Masque appris','#087767'),
            ('borne_globale','Borne un à un','#436aa5')]
    for i,(_,name,color) in enumerate(series):
        x=40+i*220
        svg_el(g,'rect',x=x,y=84,width=15,height=15,fill=color)
        svg_text(g,x+23,97,name)
    for v in [0,5,10,15,20,25,31]:
        y=334-7*v
        svg_el(g,'line',x1=49,y1=y,x2=793,y2=y,stroke='#dce3e8')
        svg_text(g,40,y+4,v,13,text_anchor='end')
    for j,r in enumerate(rows):
        x=79+184*j
        for i,(field,_,color) in enumerate(series):
            v=r[field]
            pos=x+36*i
            svg_el(g,'rect',x=pos,y=334-7*v,width=30,height=7*v,fill=color)
            svg_text(g,pos+15,327-7*v,v,15,text_anchor='middle',font_weight='bold')
        svg_text(g,x+51,361,str(r['graine'])+' / '+str(r['pas']),15,text_anchor='middle')
    svg_text(g,415,388,'Graine / pas',13,text_anchor='middle')
    indent(root)
    (out/'separations.svg').write_bytes(tostring(root,encoding='utf-8',xml_declaration=True)+b'\n')


def measures_index(out, photos):
    rows=[]
    for photo,spec in photos.items():
        cells=[]
        for seed in SEEDS:
            for step in STEPS:
                code=tag(seed,step,photo)
                cells.append(f'[{seed} / {step}](couples/{code}.json)')
        rows.append([photo,spec['provenance'],len(spec['instances']),*cells])
    write(out/'mesures'/'README.md',[
        '# Mesures R28',
        'L’unité est un état d’une photo du train. Chaque JSON par photo contient ses douze sorties et toutes ses identités, avec une ligne par couple identité–sortie. Les coordonnées xyxy sont flottantes en pixels natifs, q commence à zéro et le score final garde sa précision archivée. Le logit RPN reste distinct du score final.',
        '[Reproduction des quatre contrôles R27](verification.json) · [Comparaisons par contrôle, provenance et photo](comparaison.json) · [Toutes les trajectoires](trajectoires.json) · [Pixels appariés, incidences et unions](partiel.json) · [Empreintes et contrôle des collages](../supports/index.json)',
        table(['Photo','Provenance','Identités','Contrôle 1','Contrôle 2','Contrôle 3','Contrôle 4'],rows),
        'Le champ `sorties` donne le q RPN ancêtre et les boîtes, scores, aires et statuts des deux masques. Une fusion est une sortie couvrant au moins 25 % de deux identités. Un dédoublement est une identité couverte à au moins 25 % par deux sorties. Ces deux compteurs peuvent se recouper. Le statut exclusif de chaque sortie non appariée suit la priorité R27.',
        'Chaque entrée de `identites` donne le meilleur RPN, le meilleur q selon la boîte, le meilleur q selon le Dice, chaque liste d’arêtes et chaque attribution. Les douze `couples` donnent les marges, les partitions de pixels des deux masques, I et la borne. Les champs `admissibles` et `separes` désignent des possibilités locales. Les champs `q_admis`, `q_separe` et `q_dice_total` désignent trois attributions globales distinctes.',
        'Les sous-tables `memes_couples` fixent q avant de comparer. Les autres compteurs de rappel utilisent le réappariement indépendant. Dans `partiel.json`, la médiane ordinaire porte sur les associations présentes avec une aire positive. La fraction pondérée est le rapport des sommes. Chaque union conserve un repère distinct par photo et compte une seule fois chaque position, même si plusieurs q, pas ou graines la couvrent dans le groupe.',
        'Le Dice et les statuts conservent l’étiquette 255 hors de l’aire évaluable. Les sorties sans attribution admissible sont décrites dans les compteurs historiques. Les zéros de témoins vides indiquent une absence de tels témoins dans ces neuf vues occupées. Le contact physique, une courbe selon le seuil de score et le rappel hors train ne sont pas mesurés par ce diagnostic.',
        '[Tables par identité](../identites.md) · [Transitions](../transitions.md) · [Comparaison par provenance](../comparaison.md) · [Portée de l’annotation](../annotation.md) · [Figures](../figures/README.md)'])


def catalog(out, data):
    overviews=[[r['photo'],r['provenance'],f"[Quatre contrôles]({r['fichier']})"] for r in data['photos']]
    changes=[[r['graine'],r['identite'],r['provenance'],r['sens'],f"[Deux pas]({r['fichier']})"] for r in data['transitions']]
    write(out/'figures'/'README.md',[
        '# Figures et données R28',
        '[Comparaison des appariements et de la borne](separations.svg) · [Toutes les données des figures](donnees.json) · [Mesures par photo et par q](../mesures/README.md)',
        '![Appariements séparés des quatre contrôles](separations.svg)',
        '## Calques des neuf photos du train',
        'Chaque ligne de planche représente un contrôle. La première colonne montre les étiquettes R18. Les deux suivantes superposent les masques appris séparés, puis les supports pleins des mêmes q. Les couleurs mauve clair et violet foncé identifient respectivement une zone inconnue hors prédiction et une zone inconnue couverte. Le fond certifié couvert apparaît en rouge, l’identité correspondante en vert foncé et une autre identité connue couverte en orange. En cas de superposition de sorties, une couverture par sa propre sortie est prioritaire.',
        table(['Photo','Provenance','Planche'],overviews),
        '## Les douze transitions',
        'Les deux lignes reprennent 512 et 768 dans la même fenêtre native. Les panneaux comparent la référence de l’identité, le masque de meilleur Dice et le remplissage du même q, y compris lorsque q échoue au seuil. La fenêtre englobe la boîte de référence et les meilleures boîtes selon Dice et IoU aux deux pas, avec une marge de 6 % du plus grand côté (8 pixels minimum), bornée à la photo. Les cartes sont affichées au plus proche voisin. Les mesures et les empreintes de pixels portent sur la résolution native.',
        table(['Graine','Identité','Provenance','Sens','Planche'],changes),
        'Les 21 planches utilisent uniquement les rasters catégoriels du train et les 36 états archivés. Les crédits, licences et modifications pour ces dérivés figurent dans la [notice de reproduction](../reproduire.md).'])


def provenance(out, photos, records):
    files={}
    def include(path,role):
        p=Path(path)
        files[str(p)]=dict(role=role,octets=p.stat().st_size,sha256=digest(p))
    for k in INPUTS:
        include(k,'Référence R27 figée')
    for s in SEEDS:
        for t in STEPS:
            include(f'r27/mesures/{s}-{t}.json','Contrôle historique rejoué')
    for path,role in [('r27/mesures/courbes.json','Agrégats R27 des quatre contrôles relus'),
                      ('r27/environnement.json','Origine des roues et de CPython'),
                      ('r27/inventaire-local.json','Registre historique des capsules locales'),
                      ('r27/requirements.lock','Dépendances de collage R27'),
                      ('r27/pile-elf.json','Adaptation ELF historique'),
                      ('requirements-ci.txt','Dépendances de mesure'),
                      ('r26/inventaire.json','Sources, crédits et licences')]:
        include(path,role)
    for k,spec in photos.items():
        include(spec['masque'],'Carte indexée native du train '+k)
    for code,rec in records.items():
        include(rec['source'],'Probabilités, sorties et RPN archivés '+code)
    save(out/'sources.json',dict(reference_R27=REFERENCE,gel_R28=GEL,fichiers=files,
        cartes_pleines='supports/index.json',nouveaux_poids_ou_capsules_volumineuses=[]))


def publish(out, metrics):
    cv2.setNumThreads(1)
    photos=read('r26/train.json')['photos']
    records=read(ROOT/'supports/index.json')['supports']
    states={code:read(metrics/'couples'/(code+'.json')) for code in records}
    rows=read(metrics/'trajectoires.json')
    matrix=read(metrics/'comparaison.json')
    partial=read(metrics/'partiel.json')
    (out/'figures').mkdir(parents=True,exist_ok=True)
    (out/'mesures').mkdir(parents=True,exist_ok=True)
    figures=boards(out/'figures',photos,states,rows)
    points=[]
    for seed in SEEDS:
        for step in STEPS:
            v=matrix[f'{seed}-{step}']['ensemble']
            points.append(dict(graine=seed,pas=step,identites=v['appris']['identites'],
                separes_plein=v['plein']['separes'],separes_appris=v['appris']['separes'],
                borne_globale=v['oracle']['cardinalite_un_a_un']))
    figures['separations']=points
    chart(out/'figures',points)
    save(out/'figures'/'donnees.json',figures)
    catalog(out,figures)
    identities(out,rows)
    transitions(out,rows,figures)
    comparisons(out,matrix,partial)
    measures_index(out,photos)
    provenance(out,photos,records)


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=ROOT)
    cli.add_argument('--mesures',type=Path,default=ROOT/'mesures')
    args=cli.parse_args()
    publish(args.sortie,args.mesures)
