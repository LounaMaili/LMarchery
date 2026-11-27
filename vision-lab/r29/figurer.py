"""Figures statiques des capacités et de la perte de places au dernier étage."""
from r19.tracer import COLORS, draw, label, node, page, save_svg
from r29.commun import SEEDS, STAGES, save


def figures(dest, controls):
    dest.mkdir(exist_ok=True,parents=True)
    series={s:{k:controls[s]['ensemble']['etages'][k] for k in STAGES} for s in map(str,SEEDS)}
    sources={s:{o:{k:v['etages'][k] for k in ('nms','final')} for o,v in controls[s]['provenances'].items()} for s in series}
    save(dest/'donnees.json',dict(ordre=list(STAGES),capacites=series,provenances=sources))
    root=page(995,440,'Capacité géométrique sur les 31 identités',
              'Chaque boîte peut servir une seule fois par photo. Un cercle ouvert indique la possibilité individuelle.')
    labels=['RPN','Régr.','Bornage','Admission','NMS','12 sorties']
    for i,(s,values) in enumerate(series.items()):
        x=72+479*i
        label(root,x,104,'Graine '+s,17,'bold')
        fy=lambda v:337-6.5*v
        for t in [0,5,10,15,20,25,31]:
            node(root,'line',x1=x,y1=fy(t),x2=x+384,y2=fy(t),stroke='#dce1df')
            label(root,x-8,fy(t)+4,str(t),11,anchor='end')
        for field,color,style in [('individuelles','#697c89','4 4'),('un_a_un',COLORS[s],None)]:
            pts=[(x+j*74,fy(values[k][field])) for j,k in enumerate(STAGES)]
            draw(root,pts,color,2.5,style)
            for xx,yy in pts:
                node(root,'circle',cx=xx,cy=yy,r=5.4 if style else 2.8,fill='#fbfaf7' if style else color,stroke=color,stroke_width=1.8)
        # Les libellés finaux ont des hauteurs distinctes même quand les valeurs sont voisines.
        for j,k in enumerate(STAGES):
            label(root,x+j*74,360,labels[j],11,anchor='middle')
        label(root,x+375,fy(values['final']['individuelles'])-10,str(values['final']['individuelles']),13,'bold')
        label(root,x+375,fy(values['final']['un_a_un'])+17,str(values['final']['un_a_un']),13,'bold')
    label(root,32,402,'Les points pleins et le trait de couleur donnent le maximum un à un. Les courbes coïncident avant le préfixe final.',13)
    save_svg(root,dest/'capacites.svg')

    root=page(1050,513,'Où les douze sorties perdent-elles des places ?',
              'Par provenance et graine, décomposition des identités disponibles après NMS. Une unité représente un fût.')
    palette=['#347c70','#c86131','#8366aa']
    for j,title in enumerate(['Appariables à des boîtes distinctes','Sans boîte suffisante','Perte par partage de boîtes']):
        x=[31,390,690][j]
        node(root,'rect',x=x,y=82,width=15,height=15,fill=palette[j])
        label(root,x+21,95,title,13)
    for j,s in enumerate(series):
        x=246+j*403
        label(root,x,135,'Graine '+s,16,'bold')
    for i,name in enumerate(next(iter(sources.values()))):
        y=163+i*40
        label(root,31,y+12,name,14)
        for col,s in enumerate(series):
            rec=sources[s][name]['final']
            vals=[rec['un_a_un'],rec['identites']-rec['individuelles'],rec['deficit_partage']]
            x=246+col*403
            for v,color in zip(vals,palette):
                if v:
                    node(root,'rect',x=x,y=y,width=31*v,height=16,fill=color)
                    x+=31*v
            label(root,x+7,y+13,' / '.join(map(str,vals)),12)
    label(root,31,493,'Chiffres au bout de chaque barre dans l’ordre de la légende. Les deux vues Crigges sont réunies.',13)
    save_svg(root,dest/'provenances.svg')
