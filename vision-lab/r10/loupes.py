"""Export the pixel evidence for every reviewed location, with privacy masks."""
import argparse
from hashlib import sha256
import math
from pathlib import Path

from PIL import Image, ImageDraw

from r10.planches import DVIDS_NOTICE, font, load


def publishable(photo, x, y, w, h):
    # Keep the entire crop in a visually checked area in scenes containing people.
    if photo=="929865":
        return x+20<=.43*w and y+20<=.56*h
    if photo=="14347254046":
        return x+20<=.53*w
    if photo=="8468859144":
        return x+20<=.57*w and y-19>=.48*h
    return True


def render(r9,r8,dest):
    lab=Path(__file__).resolve().parents[1]
    rights={r["id"]:r for r in load(lab/"r10/droits.json")}
    dest.mkdir(parents=True,exist_ok=True)
    guide=["# Loupes de la lecture visuelle","","Le numéro de case correspond à la colonne "
           "`position_lue` du relevé de la photo. Les quatre traits entourent le centre exact. "
           "Chaque case part d’une fenêtre de 39 × 39 pixels de travail. Le code d’aspect est celui "
           "de la lecture assistée. Les cas hors de la zone publiable d’une scène avec des personnes "
           "gardent leur numéro et leurs mesures. Le fond noir au bord de l’image est le remplissage "
           "du recadrage.","","F = fond ou support, M = motif ou marquage, H = trou ou déchirure, "
           "O = ombre, T = corps de tube, E = extrémité opposée, I = aspect indéterminé.","",
           "Pour chaque planche, le titre original, le crédit, la licence applicable et le lien de la source "
           "figurent dans le [registre des planches](../README.md). Les modifications sont le recadrage, "
           "l’agrandissement, les repères et les légendes.","",
           "| Provenance | Relevé | Cases illustrées / positions lues | Pages par numéro de case |",
           "|---|---|---:|---|"]
    for row in load(r9/"data/r9/dev.json"):
        photo=row["id"]
        assert row["split"]=="dev"
        path=((r8/"data/r8") if row["origin"]=="r8-development" else r9/"data/r9")/row["image"]
        assert sha256(path.read_bytes()).hexdigest()==row["sha256"]
        im=Image.open(path).convert("RGB")
        table=load(lab/f"r10/releves/{photo}.json")["essais"]
        spots={p[6]:(p[2],p[3],p[7]) for trial in table.values() for p in trial["propositions"] if p[5]}
        assert sorted(spots)==list(range(1,len(spots)+1))
        links=[]
        visible=0
        for page in range(math.ceil(len(spots)/100)):
            first=1+page*100
            last=min(first+99,len(spots))
            height=math.ceil((last+1-first)/10)*82+78
            if photo=="929865":
                height+=22
            board=Image.new("RGB",(760,height),"#172730")
            d=ImageDraw.Draw(board)
            d.text((7,7),f'{row["group"]}    ·    Positions {first} à {last}',font=font(17,True),fill="white")
            for j,i in enumerate(range(first,last+1)):
                x=j%10*76+7
                y=j//10*82+37
                px,py,code=spots[i]
                if publishable(photo,px,py,*im.size):
                    crop=im.crop((px-19,py-19,px+20,py+20)).resize((59,59),Image.Resampling.NEAREST)
                    board.paste(crop,(x,y))
                    for dx,dy in ((-1,0),(1,0),(0,-1),(0,1)):
                        d.line((x+29+6*dx,y+29+6*dy,x+29+10*dx,y+29+10*dy),fill="#00ffe8")
                    visible+=1
                else:
                    d.text((x,y+15),"privé",font=font(12),fill="#b2b8ba")
                d.text((x,y+60),f"{i}  {code}",font=font(12),fill="white")
            d.text((7,height-29),f'{row["group"]} · {rights[photo]["licence"]} · Recadrages annotés · Source et licence dans le registre des planches.',font=font(12),fill="white")
            if photo=="929865":
                d.text((7,height-48),DVIDS_NOTICE,font=font(11),fill="white")
            filename=f"{photo}-{page+1}.jpg"
            board.save(dest/filename,quality=90)
            links.append(f"[{first}–{last}]({filename})")
        guide.append(f'| {row["group"]} | [Scores et coordonnées](../../releves/{photo}.json) | {visible}/{len(spots)} | '+ " · ".join(links)+" |")
    (dest/"README.md").write_text("\n".join(guide)+"\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ("r9","r8","dest"):
        parser.add_argument(key,type=Path)
    args=parser.parse_args()
    render(args.r9,args.r8,args.dest)
