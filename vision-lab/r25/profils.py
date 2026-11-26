"""Compter les limites par transition et décrire les requêtes attribuées aux axes conservés."""
from statistics import median

from r19.donnees import read, save
from r19.tracer import label,node,page,save_svg
from r25.analyser import LIMITS
from r25.commun import ARMS,ROOT
from r25.sondes import STAGES
from r25.tableaux import LIMIT,parts


def render(root=ROOT,out=ROOT):
    rows=read(root/'bilan-identites.json')
    limits=[]
    for name in LIMITS:
        subset=[r for r in rows if r.get('limite')==name]
        limits.append(dict(etape=name,pertes=sum(r['statut']=='pertes' for r in subset),
                           gains=sum(r['statut']=='gains' for r in subset),
                           identites=sorted({r['identite'] for r in subset}),
                           provenances=sorted({r['provenance'] for r in subset})))
    counts={a:[] for a in ARMS}
    for row in rows:
        if row['statut']=='conserves':
            pair=parts(root,row['graine'],row['photo'])
            for arm,q in row['appariements'].items():
                counts[arm].append(pair[arm][row['identite']]['requetes'][q]['comptes'])
    conserved={a:{s:dict(minimum=min(v[s] for v in vv),mediane=median(v[s] for v in vv),
                         maximum=max(v[s] for v in vv)) for s in STAGES} for a,vv in counts.items()}
    out=out/'figures'
    out.mkdir(parents=True,exist_ok=True)
    save(out/'donnees-profils.json',dict(limites=limits,conserves=len(counts['dice']),profils_conserves=conserved))
    svg=page(1000,477,'Du support proche à l’axe attribué',
             'Train · six états au pas 768 · chaque couple identité–graine compte une fois dans son groupe.')
    label(svg,36,110,'18 transitions',18,'bold')
    label(svg,36,132,'Dernière limite observée dans le bras en défaut',13)
    label(svg,260,163,'Pertes',13,'bold')
    label(svg,370,163,'Gains',13,'bold')
    for j,v in enumerate(r for r in limits if r['pertes']+r['gains']):
        y=186+j*35
        label(svg,36,y+9,LIMIT[v['etape']],13)
        for x,field,color in ((260,'pertes','#506283'),(370,'gains','#a95b29')):
            total=v[field]
            node(svg,'rect',x=x,y=y,width=12*total,height=10,fill=color)
            label(svg,x+12*total+5,y+10,str(total),12)
    label(svg,480,110,'27 axes appariés dans les deux bras',18,'bold')
    label(svg,480,132,'Médiane sur 21 sondes, sur la requête attribuée dans chaque bras',13)
    x0,x1,y0,y1=504,937,322,177
    for yval in (0,7,14,21):
        y=y0-(y0-y1)*yval/21
        node(svg,'line',x1=x0,y1=y,x2=x1,y2=y,stroke='#d9dcd8')
        label(svg,x0-8,y+4,str(yval),12,anchor='end')
    step=(x1-x0)/(len(STAGES)-1)
    for j,s in enumerate(['Axe','Inter.','Taille','Élong.','Portée','PCA','Intér.','Seg.','Orient.']):
        label(svg,x0+j*step,y0+21,s,11,anchor='middle')
    for arm,color,symbol in [('dice','#506283','circle'),('bloc8','#a95b29','rect')]:
        pts=[(x0+j*step,y0-(y0-y1)*conserved[arm][s]['mediane']/21) for j,s in enumerate(STAGES)]
        node(svg,'polyline',points=' '.join(f'{x:.2f},{y:.2f}' for x,y in pts),stroke=color,fill='none',stroke_width=2)
        for x,y in pts:
            if symbol=='circle':
                node(svg,'circle',cx=x,cy=y,r=5,fill='#fbfaf7',stroke=color,stroke_width=2)
            else:
                node(svg,'rect',x=x-2.5,y=y-2.5,width=5,height=5,fill=color)
    label(svg,510,379,'○  Dice R22',14,'bold')
    label(svg,659,379,'■  Bloc 8 R24',14,'bold')
    label(svg,36,434,'Les étapes sans transition sont dans les données. Les courbes décrivent des axes conservés, sélectionnés par leur issue.',13)
    save_svg(svg,out/'profils.svg')


if __name__=='__main__':
    render()
