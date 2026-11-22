"""Freeze local annotations, acquisition identities, exclusions and a blind reader packet."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

import cv2 as cv
import numpy as np
from lab_data import fingerprint, write_json
from r5.review import reserved_hashes
from r7.prepare import project, resized

def read(path):
    return json.loads(path.read_text())

def freeze(root,old):
    labels=read(root/"readings.json")
    assets={}
    for name in ("new-public","openverse-verified","targets-verified"):
        assets.update({r["id"]:r for r in read(root/(name+".json"))})
    reference=reserved_hashes() | {r["source_sha256"] for r in read(old/"data/r7/external.json")}
    reference.update(r["sha256"] for r in read(old/"r5/public-candidates.json")["frames"])
    control=read(root/"control/control-proposals.json")
    # Every registered contact was inspected in its current view, with a raw/overlay pair.
    for row in control:
        row["group"]="Crigges"
        row["boards"]=["crigges-4"]
        row["exhaustive"]=True
        row["ignore"]=[]
        for mark in row["marks"]:
            mark["reading"]="assisted_current_view_checked"
            if mark["entry"] is None:
                p=np.array(mark["source_center"])
                row["ignore"].append([(p-22).tolist(),(p+22).tolist()])
        row["pool"]="control"
    rows=control[:]
    folder=root/"work"
    folder.mkdir(exist_ok=True)
    for key,r in labels.items():
        a=assets[key]
        path=root/a["image"]
        if fingerprint(path)!=a["sha256"] or a["sha256"] in reference:
            raise ValueError("Historical, duplicate or changed photo")
        reference.add(a["sha256"])
        im=cv.imread(str(path))
        work=resized(im)
        w,h=work.shape[1::-1]
        scale=np.array([w,h])/r["display"]
        output=folder/(key+".png")
        if not cv.imwrite(str(output),work,[cv.IMWRITE_PNG_COMPRESSION,1]):
            raise OSError(output)
        marks=[m|dict(entry=project(m["entry"],scale).tolist() if m["entry"] is not None else None,
                      axis=project(m["axis"],scale).tolist(),origin="assisted_visual",polygon=None)
               for m in r["marks"]]
        rows.append(dict(id=key,pool="new",group=a["credit"],boards=r["boards"],split=r["split"],
                         flags=r["flags"],exhaustive=r["exhaustive"],marks=marks,
                         ignore=[project(box,scale).tolist() for box in r["ignore"]],
                         image="work/"+output.name,size=[w,h],sha256=fingerprint(output),
                         oriented_size=list(im.shape[1::-1]),source=a,reading=r["reading"]))
    for col in ("group","boards"):
        groups={}
        for r in rows:
            for g in ([r[col]] if col=="group" else r[col]):
                if groups.setdefault(g,r["split"])!=r["split"]:
                    raise ValueError("Shared group across partitions")
    write_json(root/"corpus.json",rows)
    write_json(root/"inventory.json",dict(assets=list(assets.values()),admitted=list(labels),
               other_candidates={k:"screened_not_selected" for k in assets if k not in labels},
               control_sha256=fingerprint(root/"control/control-proposals.json"),
               readings_sha256=fingerprint(root/"readings.json"),corpus_sha256=fingerprint(root/"corpus.json"),
               partitions={s:[r["id"] for r in rows if r["split"]==s] for s in ("train","dev","test")}))
    test=sorted([r for r in rows if r["split"]=="test"],key=lambda r:r["sha256"])
    packet=root/"lecture-test"
    packet.mkdir(exist_ok=True)
    templates=[]
    key=[]
    for i,r in enumerate(test,1):
        alias=f"photo-{i:02}"
        im=cv.imread(str(root/r["source"]["image"]))
        target=packet/(alias+".png")
        if not cv.imwrite(str(target),im):
            raise OSError(target)
        templates.append(dict(id=alias,sha256=fingerprint(target),taille=list(im.shape[1::-1]),
                         tubes=[],zones_illisibles=[],inventaire_complet=None,commentaire=""))
        key.append(dict(alias=alias,id=r["id"]))
    write_json(packet/"lecture.json",templates)
    write_json(root/"cle-test.json",key)
    (packet/"LIRE.txt").write_text("Pour chaque photo orientée, inventorier les tubes plantés visibles. "
        "Indiquer deux points sur l’axe de chaque tube, le statut visible/occulté/indéterminable/hors champ "
        "et le point d’entrée x,y en pixels seulement si observable. Distinguer les flèches en main. "
        "Signaler les zones illisibles, les groupes serrés, l’usure et la complétude. "
        "Renvoyer une copie datée avec un identifiant de lecteur. Après cette lecture indépendante, "
        "comparer les divergences sur les originaux et signer la version arbitrée.\n")
    write_json(packet/"format-tube.json",dict(
        exemple=dict(axe=[[10,10],[30,30]],statut="visible",entree=[30,30]),
        statuts=["visible","occulte","indeterminable","hors_champ"],
        commentaire="Exemple de format uniquement. Utiliser null pour une entrée non visible. "
        "Les zones illisibles sont des rectangles [[xmin,ymin],[xmax,ymax]]."))
    with ZipFile(root/"lecture-test.zip","w",ZIP_DEFLATED) as z:
        for p in sorted(packet.iterdir()):
            z.write(p,p.name)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("r7",type=Path)
    a=p.parse_args()
    freeze(a.root,a.r7)
