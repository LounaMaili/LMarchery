"""Figures de la collecte, sans prédictions ni métrique de performance."""
import argparse
from pathlib import Path
from r15.recherche import MEDIA, read, save

NAMES = {'provenances_occupees':'Provenances occupées','entrees_distinctes':'Entrées visibles',
         'axes_traces_distincts':'Axes tracés','groupes':'Supports avec groupe',
         'supports_vides_uses':'Supports vides usés','segments_negatifs':'Segments négatifs',
         'acquisitions_negatives':'Photos avec négatifs','occupees_avec_negatifs':'Occupées avec négatifs'}
DETAILS = {'rf-empty-0pHGx6yITFtGgR4Uen1L':(862,439,1082,677),
           'wc107063652':(260,208,540,432),'wc156353437':(1402,1040,1742,1320)}


def gates(out):
    import matplotlib as mpl
    import matplotlib.pyplot as plt
    mpl.rcParams['svg.hashsalt']='vision-r15'
    old = read('r14/portes.json')['partitions']
    new = read('r15/portes.json')['partitions']
    table = []
    fig,axes = plt.subplots(1,2,figsize=(11.7,4.9),sharey=True)
    fig.subplots_adjust(left=.224,right=.96,bottom=.16,top=.82,wspace=.21)
    for ax,part,title in zip(axes,('train','dev_indep'),('Apprentissage','Développement indépendant'),strict=True):
        for i,(k,label) in enumerate(NAMES.items()):
            before = old[part]['criteres'][k]['observe']
            actual = new[part]['criteres'][k]['observe']
            minimum = new[part]['criteres'][k]['minimum']
            y = len(NAMES)-1-i
            table.append(dict(partition=part,critere=k,R14=before,R15=actual,minimum=minimum))
            ax.plot([before/minimum,actual/minimum],[y,y],color='#92a7b1',lw=1.8,zorder=1)
            ax.scatter(before/minimum,y,s=39,edgecolors='#475967',facecolors='white',zorder=3)
            ax.scatter(actual/minimum,y,s=20,color='#006a69',zorder=4)
            ax.annotate(f'{actual}/{minimum}',(max(before,actual)/minimum,y),xytext=(7,0),
                        textcoords='offset points',va='center',fontsize=8,color='#1a3741')
        ax.axvline(1,ls='--',lw=1,color='#9b452a',zorder=0)
        ax.set_title(title,fontsize=12,loc='left',weight='bold')
        ax.set_xlabel('Effectif / minimum R14',fontsize=9)
        ax.set_xlim(-.18,5.8)
        ax.set_ylim(-.7,7.7)
        ax.set_yticks(list(reversed(range(len(NAMES)))),list(NAMES.values()),fontsize=9)
        ax.spines[['top','right','left']].set_visible(False)
        ax.tick_params(length=0)
        ax.grid(axis='y',alpha=.13)
    fig.suptitle('Les deux partitions franchissent les minima R14',fontsize=16,fontweight='bold',x=.04,ha='left')
    fig.text(.04,.885,'Cercle ouvert  R14     •  R15     |  Minimum requis     Étiquettes  effectif R15 / minimum',fontsize=9)
    fig.savefig(out/'portes.png',dpi=130,facecolor='white')
    plt.close(fig)
    save(out/'valeurs.json',table)


def evidence(out):
    from PIL import Image, ImageOps, ImageDraw
    rows={p['id']:p for p in read('r15/lectures.json')['photos']}
    meta=[]
    for k,box in DETAILS.items():
        row=rows[k]
        im=ImageOps.exif_transpose(Image.open(MEDIA/row['source']['fichier_travail'])).convert('RGB')
        thumb=im.copy()
        thumb.thumbnail((358,250),Image.Resampling.LANCZOS)
        view=Image.new('RGB',(690,260),'#f1f1ed')
        x,y = (365-thumb.width)//2,(260-thumb.height)//2
        view.paste(thumb,(x,y))
        draw=ImageDraw.Draw(view)
        sx,sy=thumb.width/im.width,thumb.height/im.height
        draw.rectangle((x+box[0]*sx,y+box[1]*sy,x+box[2]*sx,y+box[3]*sy),outline='#00dda4',width=2)
        detail=im.crop(box)
        detail.thumbnail((305,248),Image.Resampling.LANCZOS)
        view.paste(detail,(365+(325-detail.width)//2,(260-detail.height)//2))
        view.save(out/(k+'.png'),optimize=True)
        meta.append(dict(photo=k,credit=row['provenance'],licence=row['source']['licence_image'],
            source=row['source']['url'],dimensions_source=list(im.size),cadre_detail=list(box),
            transformation='À gauche, image entière réduite avec rectangle; à droite, extrait sans marquage.'))
    save(out/'cadres.json',meta)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    p.add_argument('--photos',action='store_true')
    args=p.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    gates(args.out)
    if args.photos:
        evidence(args.out)
