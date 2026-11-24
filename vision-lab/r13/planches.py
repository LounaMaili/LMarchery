"""Rendre les masques, les références et les deux ensembles d’axes sans pixels photographiques."""
import argparse
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
from r10.diagnostic import read

FONT=ImageFont.load_default(size=16)


def render(root,out):
    out.mkdir(exist_ok=True,parents=True)
    pred=read(root/'sorties/ordinary-9301.json')
    new=read(Path('r12/nouvelles.json'))['photos']
    rows=read(Path('r12/historique.json'))+[r for r in new if r['split']=='nouveau']
    index=['# Planches de diagnostic','',
           'Ordinaire 9301, toutes les photos des deux partitions. Fond sombre = sous le seuil tube 0,5, gris clair = masque. Turquoise = tracés humains, cercle jaune = entrée visible, rose = axes extraits. Un trait humain sans cercle a une entrée de position inconnue. Les extrémités sont celles des segments affichés. Les trois vues ont le même repère.','']
    for r in rows:
        k=r['id'];p=pred[k];sx,sy=p['echelle']
        mask=Image.open(root/'sorties/masques'/(k+'.png')).convert('L')
        base=mask.point(lambda x:38 if x==0 else 181).convert('RGB')
        w,h=base.size
        canvas=Image.new('RGB',(3*w+16,h+72),'#f6f8fa')
        draw=ImageDraw.Draw(canvas)
        draw.text((8,5),r['group']+'   ·   '+k+'   ·   ordinary-9301',fill='#142330',font=FONT)
        for i,title in enumerate(('Référence et masque','A · composantes et PCA','B · segments LSD')):
            panel=base.copy()
            d=ImageDraw.Draw(panel)
            for m in r['marks']:
                d.line([(x*sx,y*sy) for x,y in m['axis']],fill='#41e9cf',width=3)
            if i:
                for l in p['axes_'+('A' if i==1 else 'B')]:
                    d.line([tuple(x) for x in l],fill='#ff69a8',width=2)
            for m in r['marks']:
                if m['entry'] is not None:
                    x,y=m['entry'];x*=sx;y*=sy
                    d.ellipse((x-5,y-5,x+5,y+5),outline='#ffdc42',width=2)
            x=i*(w+8)
            draw.text((x+3,35),title,font=FONT,fill='#142330')
            canvas.paste(panel,(x,64))
        canvas.save(out/(k+'.png'),optimize=True)
        index.extend([f'## {r["group"]}','','![Masque, référence et reconstructions de '+r['group']+']('+k+'.png)',''])
    (out/'README.md').write_text('\n'.join(index))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    render(a.root,a.out)
