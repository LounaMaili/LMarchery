"""Render six documented crops per development photo, using saved coordinates."""
import argparse
from hashlib import sha256
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from r10.tableaux import LABELS, RUNS


# Curated illustrations, separate from the exhaustive counts. Numbers are visual IDs.
# A is an assigned candidate at a late first-contact rank, D a redundant candidate,
# N an annotated reference without a candidate anywhere across the six lists.
PANELS = {
    "3996564090": [38,72,29,"A","D","N"],
    "929865": [156,21,13,135,24,"A"],
    "6791193480": [341,61,41,163,194,286],
    "49398629148": [176,26,40,14,71,"N"],
    "955488046": [32,273,120,70,129,"N"],
    "3771007694": [104,144,178,166,182,257],
    "14347254046": [83,69,94,61,65,"A"],
    "8549619981": [185,46,163,141,222,211],
    "36377144413": [114,15,284,"A","D","N"],
    "2712546351": [15,2,5,17,"A","D"],
    "8468859144": [28,67,14,24,"A","D"],
    "67300452": [141,161,201,83,275,121],
}
DVIDS_NOTICE = "The appearance of U.S. Department of War (DoW) visual information does not imply or constitute DoW endorsement."
FONT_DIR = Path("/usr/share/fonts/dejavu-sans-fonts")
BG = "#f6f7f4"
INK = "#182833"


def font(size, bold=False):
    return ImageFont.truetype(str(FONT_DIR/("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")),size)


def load(path):
    return json.loads(path.read_text())


def select(example, trials):
    points = [(name,p) for name in RUNS for p in trials[name]["propositions"]]
    if example == "N":
        for e in trials[RUNS[0]]["entrees"]:
            if e["entry"] is not None and all(not any(e["id"] in p[8] for p in trials[n]["propositions"]) for n in RUNS):
                return None,None,e
        raise ValueError("No missing reference")
    if example == "A":
        first = [next(((name,p) for p in trials[name]["propositions"] if p[9] is not None),None) for name in RUNS]
        name,p = max((v for v in first if v is not None),key=lambda v:v[1][0])
    elif example == "D":
        name,p = max(((n,p) for n,p in points if p[8] and p[9] is None),key=lambda v:v[1][1])
    else:
        name,p = max(((n,p) for n,p in points if p[6]==example),key=lambda v:v[1][1])
    return name,p,None


def make(lab, r9, r8, dest):
    dest.mkdir(parents=True,exist_ok=True)
    rights={r["id"]:r for r in load(lab/"r10/droits.json")}
    log = {}
    for row in load(r9/"data/r9/dev.json"):
        photo=row["id"]
        source=rights[photo]
        assert row["split"]=="dev" and source["notice_directe_verifiee"]
        path=((r8/"data/r8") if row["origin"]=="r8-development" else r9/"data/r9")/row["image"]
        assert sha256(path.read_bytes()).hexdigest()==row["sha256"]
        im=Image.open(path).convert("RGB")
        trials=load(lab/f"r10/releves/{photo}.json")["essais"]
        board=Image.new("RGB",(960,650),BG)
        d=ImageDraw.Draw(board)
        d.text((20,12),row["group"],font=font(25,True),fill=INK)
        d.text((20,51),"R10   ·   Développement R9   ·   Le repère ouvert indique la position examinée",font=font(17),fill=INK)
        logs=[]
        for j,example in enumerate(PANELS[photo]):
            name,p,e=select(example,trials)
            if e:
                xy=e["entry"]
                title=f'Entrée de référence nº {e["id"]}'
                line="Aucun candidat dans les 6 essais"
                bottom="Score et rang indisponibles"
            else:
                rank,score,x,y=p[:4]
                xy=(x,y)
                label=LABELS.get(p[7],"candidat")
                if example=="A":
                    title=f'Contact apparié nº {p[9]}'
                elif example=="D":
                    title=f'Candidat redondant · entrée {p[8][0]}'
                else:
                    title=label.capitalize()
                line=f'{"Ordinaire" if name.startswith("ordinary") else "Ciblé"} {name[-4:]}  ·  rang {rank}  ·  {score:.4f}'
                bottom="Réf. "+p[10]
            cx,cy=map(round,xy)
            left,top=cx-57,cy-32
            crop=im.crop((left,top,left+115,top+65)).resize((276,156),Image.Resampling.LANCZOS)
            x=20+j%3*312;y=89+j//3*251
            board.paste(crop,(x,y))
            ax=x+138;ay=y+78
            for vx,vy in [(-1,0),(1,0),(0,-1),(0,1)]:
                d.line((ax+5*vx,ay+5*vy,ax+15*vx,ay+15*vy),fill="#0b2334",width=4)
                d.line((ax+5*vx,ay+5*vy,ax+15*vx,ay+15*vy),fill="#41fff1",width=2)
            d.text((x,y+161),title,font=font(16,True),fill=INK)
            d.text((x,y+185),line,font=font(15),fill=INK)
            d.text((x,y+208),bottom,font=font(14),fill=INK)
            logs.append(dict(exemple=example,essai=name,rang=p[0] if p else None,score=p[1] if p else None,
                             xy=xy,entree=e["id"] if e else None,legende=title,statut=bottom))
        d.text((20,601),f'Photo · {source["attribution"]}    |    {source["licence"]}    |    Recadrages agrandis, repères et légendes ajoutés.',font=font(14),fill=INK)
        footer = DVIDS_NOTICE if photo=="929865" else "Titre, lien de la photographie et conditions de réutilisation dans planches/README.md."
        d.text((20,626),footer,font=font(13),fill=INK)
        board.save(dest/f"{photo}.png",optimize=True)
        log[photo]=logs
    (dest/"points.json").write_text(json.dumps(log,ensure_ascii=False,indent=2)+"\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ("r9","r8","dest"):
        parser.add_argument(key,type=Path)
    args=parser.parse_args()
    make(Path(__file__).resolve().parents[1],args.r9,args.r8,args.dest)
