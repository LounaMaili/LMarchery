"""Figures SVG entièrement dérivées des mesures publiques."""
import argparse
import math
from pathlib import Path

from r19.tracer import COLORS, draw, label, legend, node, page, panel, save_svg
from r27.bilan import NUM, ROOT, SEEDS, STEPS, read


def series(root, xy, curves, field):
    fx, fy = xy
    for seed in reversed(SEEDS):
        key = str(seed)
        samples = list(curves[key].items())
        points = [(fx(int(x)),fy(state[field])) for x,state in samples]
        if len(points)>1:
            draw(root,points,COLORS[key],2.2,dashed='6 4' if seed==9303 else None)
        for x,y in points:
            node(root,'circle',cx=round(x,2),cy=round(y,2),r=4 if seed==9301 else 2.8,
                 fill='white' if seed==9301 else COLORS[key],stroke=COLORS[key],stroke_width=1.8)


def figures(out, measurements=NUM):
    out.mkdir(exist_ok=True,parents=True)
    curves=read(measurements/'courbes.json')
    root=page(1020,747,'De la proposition au masque séparé',
              'Neuf photos occupées du train, 31 identités. Les trois graines partagent le point initial.')
    legend(root,[str(s) for s in SEEDS],85)
    specs=[('rpn_50','Identités couvertes par le RPN à IoU ≥ 0,5',31,[0,10,20,31]),
           ('boites_50','Identités avec une boîte finale à IoU ≥ 0,5',31,[0,10,20,31]),
           ('admis','Masques admissibles après appariement',31,[0,10,20,31]),
           ('separes','Masques séparés après appariement',31,[0,10,20,31]),
           ('dice_median','Dice brut médian sur les 31 identités',1,[0,.25,.5,.75,1]),
           ('sorties_fusionnees','Sorties couvrant plusieurs identités à ≥ 25 %',108,[0,27,54,81,108])]
    for i,(field,title,maximum,ticks) in enumerate(specs):
        xy=panel(root,62+505*(i%2),135+192*(i//2),410,125,title,768,maximum,STEPS,ticks)
        series(root,xy,curves,field)
    label(root,45,706,'Les abscisses donnent le nombre de mises à jour de la graine. Les lignes relient les contrôles observés.',13)
    label(root,45,728,'9301 possède un seul contrôle autonome au pas 0. Le protocole prévoit une lecture finale au pas 768.',13)
    save_svg(root,out/'capacite.svg')

    groups=read(measurements/'groupes.json')
    origins=[name for name in groups['9302']['0'] if name.startswith('provenance/')]
    root=page(1020,857,'Masques séparés par provenance',
              'Effectifs appariés. Chaque panneau est gradué jusqu’au nombre de masques évaluables de la provenance.')
    legend(root,[str(s) for s in SEEDS],85)
    for i,key in enumerate(origins):
        values={s:{step:points[key] for step,points in rows.items()} for s,rows in groups.items()}
        den=values['9302']['0']['identites']
        ticks=sorted({0,den//2,den})
        title=key.split('/')[1]+f" · {den} masque"+('s' if den>1 else '')
        xy=panel(root,62+505*(i%2),137+174*(i//2),408,103,title,
                 768,den,STEPS,ticks)
        series(root,xy,values,'separes')
    label(root,45,835,'Les abscisses comptent les pas SGD. Les deux photos Crigges appartiennent à une même provenance.',13)
    save_svg(root,out/'provenances.svg')

    specs=read('r26/train.json')['photos']
    protected=read('r26/temoins.json')
    decision=read(measurements/'decision.json')['graines']
    root=page(870,902,'Conservation des identités entre 512 et 768',
              'Un point plein indique un masque séparé. Un contour sombre repère une identité protégée pour la graine.')
    label(root,30,114,'Provenance',14,'bold')
    label(root,257,114,'Identité R18',14,'bold')
    for j,seed in enumerate(('9302','9303')):
        x=480+j*208
        label(root,x+31,94,seed+' · Jaccard '+f"{decision[seed]['jaccard']:.2f}".replace('.',','),14,'bold','middle')
        for i,step in enumerate(('512','768')):
            label(root,x+i*62,114,step,13,anchor='middle')
    row=0
    for spec in specs.values():
        for i,name in enumerate(sorted(spec['instances'],key=lambda n:spec['instances'][n]['indice'])):
            y=142+row*23
            if not i:
                node(root,'line',x1=30,y1=y-17,x2=829,y2=y-17,stroke='#d4dce0')
                label(root,30,y,spec['provenance'],13)
            label(root,257,y,name,13)
            for j,seed in enumerate(('9302','9303')):
                for k,step in enumerate(('512','768')):
                    x=480+j*208+k*62
                    keep=name in curves[seed][step]['identites_separees']
                    node(root,'rect',x=x-14,y=y-13,width=28,height=17,rx=3,fill='#f0f2ee',
                         stroke='#344852' if name in protected[seed]['identites'] else '#e0e4e0')
                    if keep:
                        node(root,'circle',cx=x,cy=y-4.5,r=5,fill=COLORS[seed])
            row+=1
    label(root,30,875,'Les 31 lignes représentent des identités physiques. La graine 9301 ne dispose pas de ces deux contrôles.',13)
    save_svg(root,out/'stabilite.svg')

    opt=read(measurements/'optimisation.json')
    root=page(1020,566,'Évolution des pertes par cycle de présentation',
              'Un cycle complet comporte une photo de chacune des huit provenances. 9301 a un seul cycle partiel de trois pas.')
    legend(root,[str(s) for s in SEEDS],85)
    for i,(keys,title) in enumerate([(('rpn_obj','rpn_L1'),'RPN · classification et régression'),
                                     (('roi_cls','roi_L1'),'RoI · classification et régression'),
                                     (('masque',),'Masque · supervision partielle'),((), 'Norme de gradient avant écrêtage')]):
        ys={s:{cycle:dict(y=(sum(row['pertes'][k] for k in keys) if keys else row['norme_avant']))
               for cycle,row in v['cycles'].items()} for s,v in opt.items()}
        ymax=max(max(row['y'] for row in seq.values()) for seq in ys.values())
        unit=10**math.floor(math.log10(max(ymax,.001)))
        limit=math.ceil(ymax/unit)*unit
        xy=panel(root,62+505*(i%2),140+195*(i//2),405,124,title,96,limit,[1,24,48,72,96],[0,limit/2,limit])
        series(root,xy,ys,'y')
    label(root,45,537,'Les abscisses donnent le numéro du cycle. Chaque point représente la moyenne des pas exécutés.',13)
    save_svg(root,out/'optimisation.svg')


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=ROOT/'figures')
    figures(cli.parse_args().sortie)
