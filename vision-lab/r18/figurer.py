"""Tracer les effectifs documentés R17 et admis R18 dans les deux partitions relues."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET

from r14.mesurer import read


def render(out):
    data=read(out/'effectifs.json')['par_partition']
    svg=ET.Element('svg',dict(xmlns='http://www.w3.org/2000/svg',width='980',height='393',viewBox='0 0 980 393',role='img'))
    ET.SubElement(svg,'title').text='Supervision des fûts et des entrées dans les vues R18'
    ET.SubElement(svg,'desc').text='Les nombres de la table de R18 sont représentés en barres, séparément pour le train et le développement exploré.'
    def rect(x,y,w,h,color):
        ET.SubElement(svg,'rect',dict(x=str(x),y=str(y),width=str(w),height=str(h),fill=color))
    def label(x,y,s,size=15,bold=False,color='#20323b'):
        node=ET.SubElement(svg,'text',{'x':str(x),'y':str(y),'fill':color,'font-family':'DejaVu Sans, sans-serif',
                                      'font-size':str(size),'font-weight':'bold' if bold else 'normal'})
        node.text=str(s)
    rect(0,0,980,393,'#fafaf7')
    label(20,32,'Supervision disponible dans les vues relues',22,True)
    for x,color,caption in [(20,'#a5b5bf','R17 · annotation présente'),(335,'#14776f','R18 · supervision admise')]:
        rect(x,49,18,11,color);label(x+26,60,caption,14)
    names=[('Fût','masque_R17','masque_valide'),('Axe','axe_R17','axe_valide'),('Entrée','entree_R17','entree_valide'),('Triplet','triplets_R17','triplets_valides')]
    for j,(part,title) in enumerate([('train','Apprentissage'),('dev_explore_R16','Développement exploré')]):
        x=20+j*480
        v=data[part]
        label(x,96,f"{title} · {v['acquisitions']} photos",17,True)
        label(x,119,f"{v['instances_R17']} identités R17, {v['instances_R18']} documentées en R18",13)
        for i,(name,before,after) in enumerate(names):
            y=142+i*47
            label(x,y+17,name)
            for k,key,col in [(0,before,'#a5b5bf'),(1,after,'#14776f')]:
                n=v[key]
                rect(x+68,y+k*16,n*8,11,col)
                label(x+72+n*8,y+k*16+11,n,12)
        label(x+68,343,'Flèches physiques dans la partition',13)
    label(20,375,'Une flèche peut contribuer à plusieurs tâches. Le triplet exige les trois champs admis dans la même vue.',14)
    target=out/'figures'/'bilan.svg'
    target.parent.mkdir(parents=True,exist_ok=True)
    ET.indent(svg)
    ET.ElementTree(svg).write(target,encoding='utf-8',xml_declaration=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    render(parser.parse_args().out)
