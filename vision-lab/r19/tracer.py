"""Courbes SVG et données de tracé. Les paliers de seuil gardent les ex æquo groupés."""
import argparse
from collections import defaultdict
import json
from math import log1p
from pathlib import Path
from xml.etree import ElementTree as E

from r19.donnees import ROOT, SEEDS, archive, read

COLORS={'B':'#273641','9301':'#087e87','9302':'#aa4a0a','9303':'#7052ae'}


def node(parent, tag, **attrs):
    return E.SubElement(parent,tag,{k.replace('_','-'):str(v) for k,v in attrs.items()})


def label(root,x,y,value,size=13,weight='normal',anchor='start'):
    node(root,'text',x=x,y=y,font_size=size,font_weight=weight,text_anchor=anchor,fill='#20313b').text=value


def page(w,h,title,sub):
    root=E.Element('svg',dict(xmlns='http://www.w3.org/2000/svg',width=str(w),height=str(h),viewBox=f'0 0 {w} {h}',
                                role='img'))
    root.set('font-family','DejaVu Sans, sans-serif')
    node(root,'title').text=title
    node(root,'desc').text=sub
    node(root,'rect',x=0,y=0,width=w,height=h,fill='#fbfaf7')
    label(root,30,37,title,24,'bold');label(root,30,62,sub)
    return root


def legend(root, names, y):
    x=34
    for s in names:
        node(root,'line',x1=x,y1=y,x2=x+22,y2=y,stroke=COLORS[s],stroke_width=3)
        label(root,x+27,y+4,s); x+=95


def save_svg(root,path):
    E.indent(root)
    E.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)


def panel(root,x,y,w,h,title,xmax,ymax,xticks,yticks,log=False):
    label(root,x,y-13,title,15,'bold')
    fx=lambda v:x+w*(log1p(v)/log1p(xmax) if log else v/xmax)
    fy=lambda v:y+h-h*v/ymax
    for v in yticks:
        node(root,'line',x1=x,y1=fy(v),x2=x+w,y2=fy(v),stroke='#d4dce0',stroke_width=1)
        label(root,x-5,fy(v)+4,f'{v:g}',11,anchor='end')
    for v in xticks:
        node(root,'line',x1=fx(v),y1=y+h,x2=fx(v),y2=y+h+4,stroke='#64777d')
        label(root,fx(v),y+h+16,f'{v:g}',10,anchor='middle')
    node(root,'line',x1=x,y1=y,x2=x,y2=y+h,stroke='#64777d')
    return fx,fy


def draw(root,pts,color,width=2,dashed=None):
    attrib=dict(points=' '.join(f'{x:.2f},{y:.2f}' for x,y in pts),fill='none',stroke=color,stroke_width=width)
    if dashed:attrib['stroke_dasharray']=dashed
    node(root,'polyline',**attrib)


def render(out):
    folder=out/'figures';folder.mkdir(exist_ok=True,parents=True)
    sources={str(seed):[json.loads(l) for l in (out/'apprentissage'/(str(seed)+'.jsonl')).read_text().splitlines()] for seed in SEEDS}
    series={}
    for s,rows in sources.items():
        series[s]=[dict(fin_bloc=i+8,**{k:sum(r[k] for r in rows[i:i+8])/8 for k in ('total','masque','axe','entree','presence')})
                   for i in range(0,96,8)]
    archive(folder/'donnees-apprentissage.json',series)
    root=page(1020,658,'Pertes d’apprentissage sur 96 mises à jour',
              'Chaque point est la moyenne d’un cycle de huit provenances. Les trois graines ont le même budget.')
    legend(root,sources,84)
    for i,(key,name,top) in enumerate([('total','Perte totale',14),('entree','Position de l’entrée',12),('masque','Masque du fût',1),
                                          ('axe','Tronçons d’axe',1),('presence','Présence',.5)]):
        x=47+(i%3)*334;y=133+(i//3)*247
        limit=top
        while max(v[key] for rows in series.values() for v in rows)>limit:limit*=2
        fx,fy=panel(root,x,y,279,168,name,96,limit,[0,24,48,72,96],[0,limit/2,limit])
        for s,rr in series.items():
            draw(root,[(fx(d['fin_bloc']),fy(d[key])) for d in rr],COLORS[s])
    label(root,30,632,'Abscisse : mises à jour achevées. Ordonnée : perte moyenne de la tâche. Le score de visibilité est désactivé.')
    save_svg(root,folder/'apprentissage.svg')

    curves={s:read(out/'mesures'/('courbe-'+s+'.json')) for s in COLORS}
    fit={s:read(out/'mesures'/(s+'.json')) for s in COLORS}
    specs=read(ROOT/'donnees.json')['photos']
    strata={'ensemble':[p['photo'] for p in specs if p['partition']!='train']}
    for flag in ('grouped','worn','empty'):
        strata[flag]=[p['photo'] for p in specs if p['partition']!='train' and flag in p['conditions']]
    data={}
    for g,keys in strata.items():
        data[g]={}
        for s,c in curves.items():
            events=defaultdict(dict)
            for k in keys:
                for row in c['par_photo'][k]:
                    events[row['seuil']][k]=row
            current={};points=[];last=None
            for t,updates in sorted(events.items(),reverse=True):
                current.update(updates)
                a=sum(q['apparies'] for q in current.values());u=sum(q['non_appariees'] for q in current.values())
                if (a,u)!=last:
                    points.append([t,a,u]);last=(a,u)
            data[g][s]=dict(photos=len(keys),connus=sum(fit[s]['par_photo'][k]['selection']['connus'] for k in keys),
                            points=points)
    archive(folder/'donnees-courbes.json',data)
    # Une colonne par point de changement de pas, avec les données numériques complètes conservées.
    from r19.publier import readable
    readable(folder/'donnees-courbes.json')
    root=page(1050,721,'Contacts retrouvés et propositions non appariées',
              'Chaque palier correspond à un score observé. Le seuil est sélectionné sur les onze photos réunies.')
    legend(root,COLORS,85)
    layouts=[('ensemble','Onze photos · près du budget',False),('ensemble','Onze photos · courbe entière',True),
             ('grouped','Flèches groupées',True),('worn','Supports usés',True)]
    for idx,(group,title,log) in enumerate(layouts):
        group_data=data[group];den=group_data['B']['connus']
        x=55+(idx%2)*515;y=139+(idx//2)*259
        xmax=200 if log else 1.
        fx,fy=panel(root,x,y,444,167,title+f' · {den} entrées',xmax,100,
                    [0,1,5,20,80,190] if log else [0,.25,.5,.75,1],[0,25,50,75,100],log=log)
        if group=='ensemble':
            node(root,'line',x1=fx(.25),y1=y,x2=fx(.25),y2=y+167,stroke='#898158',stroke_dasharray='4 4')
        for s,v in group_data.items():
            pts=[];prev=None
            for _,a,u in v['points']:
                xv=u/v['photos'];yv=100*a/den if den else 0.
                if xv>xmax:
                    if prev is not None:pts.append((fx(xmax),fy(prev)))
                    break
                if prev is not None:pts.append((fx(xv),fy(prev)))
                pts.append((fx(xv),fy(yv)));prev=yv
            draw(root,pts,COLORS[s],2,{'9301':'7 3','9302':'2 2','9303':'9 3 2 3'}.get(s))
            selected=fit[s]['par_strate'][group];vx=selected['non_appariees']/v['photos']
            node(root,'circle',cx=fx(vx),cy=fy(100*selected['apparies']/den if den else 0),r=3.2,
                 fill='none',stroke=COLORS[s],stroke_width=1.4)
    label(root,30,651,'Abscisse : propositions non appariées par photo. Ordonnée : rappel des entrées admises (%).')
    label(root,30,676,'Les trois vues de courbe entière utilisent une échelle log(1 + x). Cercles : seuil choisi sur l’ensemble.')
    label(root,30,699,'Les témoins vides ont zéro entrée de référence. Leurs propositions figurent dans les données par seuil.')
    save_svg(root,folder/'contacts.svg')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    render(parser.parse_args().out)
