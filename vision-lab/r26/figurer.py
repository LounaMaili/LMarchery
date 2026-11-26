"""Figure des bornes et des densités publiées, sans ajuster les seuils."""
from pathlib import Path

from r19.donnees import read
from r19.tracer import label,node,page,save_svg
from r25.commun import ARMS,SEEDS


def draw(data,out):
    root=page(1000,486,'Ce que pourrait changer un axe parfait',
              'Mesures rétrospectives sur le train. Les deux panneaux ont des unités et des dénominateurs distincts.')
    label(root,35,110,'Triplets appariés sur 26 entrées connues',18,'bold')
    label(root,35,134,'● Mesuré     ○ Borne avec axe parfait',13)
    label(root,521,110,'Occupation de la grille au seuil historique',18,'bold')
    label(root,521,134,'264 requêtes sur les 22 captures de R25',13)
    x0,dx=155,39
    for j in range(7):
        x=x0+j*dx
        node(root,'line',x1=x,y1=164,x2=x,y2=387,stroke='#dbdfd9')
        label(root,x,407,str(j),12,anchor='middle')
    for i,(arm,seed) in enumerate((a,s) for a in ARMS for s in SEEDS):
        y=182+i*37
        v=data['etats'][f'{arm}-{seed}']['ensemble']
        lo,hi=v['triplet'],v['axe_parfait']
        label(root,35,y+4,f"{'Dice' if arm=='dice' else 'Bloc 8'} · {seed}",13)
        node(root,'line',x1=x0+dx*lo,y1=y,x2=x0+dx*hi,y2=y,stroke='#506283',stroke_width=3)
        node(root,'circle',cx=x0+dx*hi,cy=y,r=7,fill='#fbfaf7',stroke='#506283',stroke_width=2)
        node(root,'circle',cx=x0+dx*lo,cy=y,r=4,fill='#506283')
        label(root,409,y+4,f'{lo} → {hi}',13,'bold')
    counts=data['cartes_ensemble']
    for i,(field,title) in enumerate([('entier','Axe positif sur toute la grille'),('au_moins_99','Axe positif sur ≥ 99 %'),
                                      ('au_moins_95','Axe positif sur ≥ 95 %'),('identique_masque','Intersection identique au masque')]):
        y=166+i*62
        label(root,521,y,title,13)
        node(root,'rect',x=521,y=y+8,width=345,height=14,fill='#e8ece6')
        node(root,'rect',x=521,y=y+8,width=345*counts[field]/counts['cartes'],height=14,fill='#167c76')
        label(root,879,y+20,str(counts[field]),14,'bold')
    label(root,35,454,'La borne masque seul coïncide avec la borne axe parfait dans les six états. Les trois premières barres de densité se recouvrent.',13)
    out.parent.mkdir(parents=True,exist_ok=True)
    save_svg(root,out)


if __name__=='__main__':
    draw(read('r26/mesures/bilan.json'),Path('r26/figures/bornes.svg'))
