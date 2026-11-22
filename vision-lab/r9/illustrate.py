"""Attach publisher information and render the verified training-only bank."""
import argparse
from pathlib import Path
import unicodedata

import cv2 as cv
import numpy as np

from lab_data import write_json
from r8.corpus import read
from r9.bank import train_data


def ascii_label(value):
    return unicodedata.normalize("NFKD",value).encode("ascii","ignore").decode()


def render(root,r7,r8):
    train,images=train_data(r7,r8)
    folder=root/"bank"
    raw={r["id"]:r for r in read(r7/"data/r7/pilot/annotations.json")}
    manifest={}
    for r in train:
        if r["pool"]=="control":
            a=raw[r["id"]]
            source={k:a[k] for k in ("source","credit","version","license","source_url","source_file",
                                     "source_sha256","annotation_url","annotation_sha256")}
        else:
            source=r["source"]
        manifest[r["id"]]=dict(source=source,work_sha256=r["sha256"],provenance=r["group"],
            supports=r["boards"],split="train",annotations="lecture R8 assistée, régions ambiguës ignorées")
    write_json(folder/"provenance.json",manifest)
    write_json(folder/"annotations-train.json",train)
    bank=sorted(read(folder/"verified.json"),key=lambda v:v["index"])
    pages=[]
    for i,b in enumerate(bank):
        if i%16==0:
            page=np.full((4*148+32,4*258,3),249,np.uint8)
            pages.append(page)
            cv.putText(page,"R9  /  Photos du train  /  Faux confirmes du checkpoint R7",(8,23),0,.62,(30,35,40),1,cv.LINE_AA)
        x,y=i%4*258,i%16//4*148+32
        im=images[b["id"]]
        w,h=im.shape[1::-1]
        locator=cv.resize(im,(68,86),interpolation=cv.INTER_AREA)
        px,py=b["point"]
        cv.circle(locator,(round(px*68/w),round(py*86/h)),3,(225,10,205),1)
        patch=cv.getRectSubPix(im,(176,86),(float(px),float(py)))
        cv.circle(patch,(88,43),6,(225,10,205),1)
        page[y:y+86,x:x+68]=locator
        page[y:y+86,x+72:x+248]=patch
        cv.putText(page,f"{b['index']}  {b['id']}  {b['score']:.3f}",(x+2,y+105),0,.45,(35,35,35),1,cv.LINE_AA)
        cv.putText(page,ascii_label(b["category"]),(x+2,y+125),0,.47,(30,45,55),1,cv.LINE_AA)
    cv.imwrite(str(folder/"planche-verifiee.jpg"),np.vstack(pages),[cv.IMWRITE_JPEG_QUALITY,91])


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","r7","r8"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    render(a.root,a.r7,a.r8)
