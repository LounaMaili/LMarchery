"""Render the visually reviewed, ranked R7 audit with a whole-photo locator."""
import argparse
from collections import Counter
from pathlib import Path

import cv2 as cv
import numpy as np
from lab_data import write_json
from r3.windows import offsets
from r7.prepare import project
from r8.corpus import read

# Visual review of the raw crops and full images indexed by audit.selection.json.
SIGN={1,2,5,6,13,17,18,21,22,25,33,37,38,41,42,45,49,53,58,61,62,65,69,70,73,74,77,78}
EDGE={9,10,14,26,29,30,54,57}
HOLE={34,46,50,66}
SHAFT={4,7,8,12,16,20,24,28,32,35,40,43,44,47,48,52,56,59,63,64,68,71,72,76,79}

def run(old,out):
    sel=read(out/"selection.json")
    rows={r["id"]:r for r in read(old/"data/r7/pilot/annotations.json")}
    categories=[(SIGN,"panneau numero 4"),(EDGE,"bord du support"),(HOLE,"perforation / usure"),(SHAFT,"corps du tube")]
    if set.union(*(s for s,_ in categories))!={m["index"] for m in sel if not m["matched"]}:
        raise ValueError("Visual review differs from selected false proposals")
    pages=[]
    reviewed=[]
    for i,m in enumerate(sel):
        r=rows[m["id"]]
        work=cv.imread(str(old/"data/r7/pilot"/r["work_file"]))
        p=project(m["original"],np.array(r["work_size"])/r["oriented_size"])
        x,y=p
        cat=next((c for ids,c in categories if i+1 in ids),"contact apparie")
        h,w=work.shape[:2]
        bx=[a for t in offsets(w,320,64) for a in (t,t+319) if 0<a<w-1]
        by=[a for t in offsets(h,320,64) for a in (t,t+319) if 0<a<h-1]
        dist=min([abs(x-a) for a in bx]+[abs(y-a) for a in by])
        error=(np.linalg.norm(np.subtract(m["original"],r["entry"]))*704/max(r["oriented_size"])
               if r["entry"] is not None else None)
        near=bool(error is not None and error<=10 and not m["matched"])
        reviewed.append(m|dict(visual_category=cat,near_confirmed_contact=near,interior_tile_edge_px=float(dist)))
        if i%16==0:
            pages.append(np.full((4*187+42,4*318,3),246,np.uint8))
            cv.putText(pages[-1],f"Crigges / R7 ImageNet   |   rangs 1 et 2 sur photo entiere   |   {i//16+1}/5",
                       (12,29),0,.72,(35,35,35),1,cv.LINE_AA)
        u,v=(i%4)*318,(i%16//4)*187+42
        locator=cv.resize(work,(93,124),interpolation=cv.INTER_AREA)
        cv.circle(locator,(round(x*93/w),round(y*124/h)),4,(255,0,255),1)
        patch=cv.getRectSubPix(work,(208,124),(float(x),float(y)))
        cv.circle(patch,(104,62),7,(255,0,255),1)
        page=pages[-1]
        page[v:v+124,u:u+93]=locator
        page[v:v+124,u+95:u+303]=patch
        cv.putText(page,f"{i+1:02}  {r['id']}  rang {m['rank']}  score {m['score']:.3f}",(u+2,v+145),0,.52,(25,25,25),1,cv.LINE_AA)
        cv.putText(page,cat+(" / proche" if near else ""),(u+2,v+169),0,.5,(60,40,60),1,cv.LINE_AA)
    for i,page in enumerate(pages,1):
        cv.imwrite(str(out/f"lecture-{i}.png"),page)
    cv.imwrite(str(out/"planche-complete.jpg"),np.vstack(pages),[cv.IMWRITE_JPEG_QUALITY,91])
    write_json(out/"lecture.json",reviewed)
    wrong=[r for r in reviewed if not r["matched"]]
    write_json(out/"categories.json",dict(selected=len(sel),false=len(wrong),
               observed=Counter(r["visual_category"] for r in wrong),
               near=sum(r["near_confirmed_contact"] for r in wrong),
               within_four_px_of_tile_boundary=sum(r["interior_tile_edge_px"]<=4 for r in wrong)))

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("r7",type=Path)
    p.add_argument("out",type=Path)
    a=p.parse_args()
    run(a.r7,a.out)
