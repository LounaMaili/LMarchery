"""Planches du développement depuis le seul panneau photographique autorisé de R18."""
import argparse
from pathlib import Path

import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

from r17.planches import font
from r19.donnees import R18, SEEDS, fingerprint, read, save, work_to
from r19.mesures import baseline, candidate, dev_refs

INK='#19313a'
PAPER='#f8f7f3'
TEAL=(14,199,174)
PINK=(231,80,131)
GOLD=(255,199,60)


def paint(raw, colors):
    img=raw.convert('RGBA')
    for on,rgb in colors:
        alpha=np.zeros((*on.shape,4),np.uint8)
        alpha[on]=[*rgb,70]
        edge=cv.morphologyEx(on.astype(np.uint8),cv.MORPH_GRADIENT,np.ones((3,3),np.uint8)).astype(bool)
        alpha[edge]=[*rgb,195]
        img.alpha_composite(Image.fromarray(alpha))
    return img.convert('RGB')


def mark(draw,xy,kind,color,label):
    x,y=xy
    if kind=='circle':
        draw.ellipse((x-7,y-7,x+7,y+7),outline='#16272c',width=4)
        draw.ellipse((x-7,y-7,x+7,y+7),outline=color,width=2)
    else:
        for delta in (-1,1):
            points=[(x-6,y-6*delta),(x+6,y+6*delta)]
            draw.line(points,fill='#14262c',width=5);draw.line(points,fill=color,width=2)
    draw.text((x+9,y-8),label,font=font(12),fill=color,stroke_width=2,stroke_fill='#17282e')


def plate(folder,base,cand,ref,br,rr,entry,rights,seed):
    skey=ref['spec']['photo']
    a=read(R18/'annotations'/(skey+'.json'))
    x,y,w,h=entry['pixels']
    jpg=R18/'figures'/entry['fichier']
    if fingerprint(jpg)!=entry['sha256']:
        raise ValueError('Panneau source différent')
    with Image.open(jpg) as image:
        raw=image.crop((x,y,x+w,y+h)).convert('RGB')
    m=np.array(entry['travail_vers_panneau'])
    inv=cv.invertAffineTransform(m)
    yy,xx=np.indices((h,w))
    work=np.stack((xx*inv[0,0]+yy*inv[0,1]+inv[0,2],xx*inv[1,0]+yy*inv[1,1]+inv[1,2]),axis=-1)
    size=ref['size']
    clip=a['emprise_diffusee'] or [0,0,*size]
    area=((work>=np.array(clip[:2])-.5)&(work<np.array(clip[2:])-.5)).all(axis=2)
    def sample(plane):
        grid=np.array(plane.shape[1::-1])
        pix=np.floor((work+.5)*grid/size).astype(int).clip(0,grid-1)
        return plane[pix[:,:,1],pix[:,:,0]]
    truth=sample(ref['mask'])
    bits=sample(cand['bits'])
    def where(xy):
        nat=work_to(xy,ref['view'],size)
        pos=m[:,:2]@nat+m[:,2]
        return tuple(pos) if np.all(nat>=np.array(clip[:2])-.5) and np.all(nat<np.array(clip[2:])-.5) else None
    ref_pic=paint(raw,[(area&(truth==i),TEAL) for i in ref['ids'].values()])
    d=ImageDraw.Draw(ref_pic)
    for name,pt in ref['entries'].items():
        pos=where(pt)
        if pos:mark(d,pos,'circle','#ffd56a',name.split(':')[-1])
    points_pic=raw.copy();d=ImageDraw.Draw(points_pic)
    for source,record,kind,color,prefix in ((base,br,'circle','#79d6ff','B'),(cand,rr,'cross','#fff171','q')):
        selected=record['selection']
        matches={m['sortie']:m['instance'].split(':')[-1] for m in selected['paires']}
        emitted=set(matches)|{u['sortie'] for u in selected['non_appariees_detail']}
        for c in source['contacts']:
            if c['id'] not in emitted:continue
            pos=where(c['xy'])
            if pos:mark(d,pos,kind,color,prefix+str(c['id'])+' / '+str(matches.get(c['id'],'?')))
    masks=rr['masques']
    counts=masks['fusions']
    assoc=rr['association_selection']
    if assoc:
        q=assoc[0]['sortie']
    elif counts:
        q=int(min(counts,key=lambda s:(-len(counts[s]),int(s))))
    else:
        q=next((v['sortie'] for v in masks['par_identite'] if v['sortie'] is not None),0)
    group=counts.get(str(q),[])
    chosen=paint(raw,[(area&((bits & (1<<q))!=0),PINK),
                        *[(area&(truth==ref['ids'][n]),TEAL) for n in group]])
    split=masks['dedoublements']
    selected_name=min(split,key=lambda n:(-len(split[n]),n)) if split else None
    involved=split.get(selected_name,[])[:2]
    layers=[(area&((bits & (1<<s))!=0),color) for s,color in zip(involved,(PINK,GOLD))]
    if selected_name:
        layers.append((area&(truth==ref['ids'][selected_name]),TEAL))
    detail=paint(raw,layers)
    panel_h=h+48
    sheet=Image.new('RGB',(2*w+40,2*panel_h+108),PAPER);d=ImageDraw.Draw(sheet)
    d.text((12,9),f"{ref['spec']['provenance']}  ·  graine {seed}",fill=INK,font=font(22))
    legends=[('Références admises', 'Fûts turquoise et entrées numérotées en jaune.',ref_pic),
             ('Entrées retenues au seuil', f"B en bleu ({br['selection']['propositions']}) · candidat en jaune ({rr['selection']['propositions']}).",points_pic),
             (f'Masque de la requête q{q}', 'Rose : prédiction. Turquoise : fûts couverts à 25 % dans une fusion.',chosen),
             ('Dédoublement de '+selected_name.split(':')[-1] if selected_name else 'Aucun dédoublement mesuré',
              ('Deux requêtes : q'+str(involved[0])+' (rose) et q'+str(involved[1])+' (jaune). Fût R18 en turquoise.') if involved else 'Pixels du panneau autorisé R18.',detail)]
    for idx,(headline,caption,im) in enumerate(legends):
        px=12+(idx%2)*(w+16);py=52+(idx//2)*panel_h
        d.text((px,py),headline,fill=INK,font=font(16))
        d.text((px,py+21),caption,fill=INK,font=font(12))
        sheet.paste(im,(px,py+41))
    credit=rights['credit']+' · '+rights['nom_licence']+' · cadrage R18, calques R19 par LMArchery, même licence'
    d.text((12,sheet.height-29),credit,font=font(13),fill=INK)
    path=folder/(skey+'-'+seed+'.jpg')
    sheet.save(path,quality=85,subsampling=1,optimize=True)
    return dict(photo=skey,graine=seed,fichier=path.name,sha256=fingerprint(path),requete_detail=q,
                fusions_du_detail=group,reference_dedoublee=selected_name,requetes_illustrees=involved,
                associations_selection=assoc,paires_B=br['selection']['paires'],paires_directes=rr['selection']['paires'])


def render(out):
    folder=out/'figures';folder.mkdir(exist_ok=True,parents=True)
    refs=dev_refs(read(Path('r19/donnees.json'))['photos'])
    context={r['photo']:r for r in read(R18/'figures/index.json') if r['id']==r['photo']}
    rights={r['photo']:r['droits'] for r in read(R18/'sources.json')}
    br=read(out/'mesures/B.json')['par_photo'];base=baseline(refs)
    index=[];links={}
    for seed in map(str,SEEDS):
        pred=candidate(refs,out/'predictions'/seed)
        rr=read(out/'mesures'/(seed+'.json'))['par_photo']
        for k,r in refs.items():
            if r['empty']:continue
            item=plate(folder,base[k],pred[k],r,br[k],rr[k],context[k],rights[k],seed)
            index.append(item);links.setdefault(k,[]).append(f"[{seed}]({item['fichier']})")
    save(folder/'index-planches.json',index)
    text=['# Figures et contrôle des erreurs R19','',
          'Les courbes de pertes montrent les moyennes des cycles de huit provenances. Les coordonnées de seuil sont publiées pour toutes les photos et chaque support. Le nombre de contacts connus des témoins vides est nul.','',
          '[Pertes par tâche](apprentissage.svg) · [Données des pertes](donnees-apprentissage.json) · [Courbes de rappel](contacts.svg) · [Points numériques par condition](donnees-courbes.json) · [Courbes complètes par photo et système](../mesures/)',
          '','## Planches sur les sept photos occupées','',
          'Chaque planche juxtapose les références admises, les contacts retenus par B et par le candidat, un masque individuel et deux requêtes lorsqu’elles recouvrent la même référence. Dans le panneau des contacts, le symbole est suivi du numéro de sortie puis de l’identité appariée. Les quatre témoins sont quantifiés par acquisition dans les tableaux. Les planches utilisent uniquement le panneau photographique déjà autorisé de R18. Le cadrage de diffusion de Moo.The.Cow est appliqué aussi aux calques.','',
          'Le masque individuel illustré est celui de la première entrée appariée au seuil. À défaut, la planche prend la fusion qui recouvre le plus de références, puis une requête appariée au masque si aucune fusion n’est mesurée. Le quatrième panneau prend la référence la plus dédoublée. Le registre numérique et les tables individuelles donnent aussi les cas hors de ces illustrations. [Registre des planches](index-planches.json) · [Toutes les identités](../identites.md) · [Témoins, groupes et supports usés](../tableaux.md)','',
          '| Provenance et crédit | Trois graines | Source et licence du dérivé |','|---|---|---|']
    for k,v in links.items():
        s=rights[k]
        text.append(f"| {s['credit']} | {' · '.join(v)} | [Notice]({s['notice']}) · [{s['nom_licence']}]({s['licence']}) |")
    (folder/'README.md').write_text('\n'.join(text)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    render(parser.parse_args().out)
