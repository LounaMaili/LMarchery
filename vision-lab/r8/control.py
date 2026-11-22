"""Register sequence annotations to the CURRENT controlled image for visual review."""
import argparse
import json
from pathlib import Path

import cv2 as cv
import numpy as np
from lab_data import fingerprint, write_json
from r7.prepare import project

SEQUENCES=("13","70","90","101")

def transform(previous,current):
    detector=cv.ORB_create(nfeatures=3000)
    a,da=detector.detectAndCompute(cv.cvtColor(previous,cv.COLOR_BGR2GRAY),None)
    b,db=detector.detectAndCompute(cv.cvtColor(current,cv.COLOR_BGR2GRAY),None)
    pairs=cv.BFMatcher(cv.NORM_HAMMING).knnMatch(da,db,k=2)
    keep=[p for p,q in pairs if p.distance < .75*q.distance]
    src=np.float32([a[p.queryIdx].pt for p in keep])
    dst=np.float32([b[p.trainIdx].pt for p in keep])
    matrix,inliers=cv.findHomography(src,dst,cv.RANSAC,2.0)
    if matrix is None or inliers.sum()<40:
        raise ValueError("Insufficient registration support")
    return matrix,int(inliers.sum())

def warp(points,matrix):
    return cv.perspectiveTransform(np.asarray(points,dtype=np.float64)[None],matrix)[0]

def prepare(old,out):
    out.mkdir(parents=True,exist_ok=True)
    cv.setNumThreads(1)
    cv.setRNGSeed(8301)
    root=old/"data/r7/pilot"
    rows={r["id"]:r for r in json.loads((root/"annotations.json").read_text())}
    controlled=[]
    for row in rows.values():
        if row["id"].split("-")[0] in SEQUENCES and row["frame"] in (0,1,2,3):
            if fingerprint(root/row["work_file"])!=row["work_sha256"]:
                raise ValueError("Controlled image differs from R7")
    for seq in SEQUENCES:
        for n in (0,1,3):
            key=f"{seq}-{n:02}"
            row=rows[key]
            im=cv.imread(str(root/row["work_file"]))
            s=np.array(row["work_size"])/row["oriented_size"]
            marks=[]
            display=im.copy()
            for i in range(1,n+1):
                prev=rows[f"{seq}-{i:02}"]
                affine,num=(np.eye(3),None) if i==n else transform(cv.imread(str(root/prev["work_file"])),im)
                poly=warp(project(prev["polygon"],s),affine)
                center=warp(project([prev["source_point"]],s),affine)[0]
                marks.append(dict(instance=prev["id"],polygon=poly.tolist(),source_center=center.tolist(),
                                  entry=center.tolist() if prev["entry"] is not None else None,
                                  origin="source_current" if i==n else "registered_source_proposal",
                                  inliers=num,visibility=prev["entry_status"]))
                color=[(0,165,255),(230,0,160),(0,225,0)][i-1]
                cv.polylines(display,[np.rint(poly).astype(np.int32)],True,color,1)
                cv.circle(display,tuple(np.rint(center).astype(int)),8,color,1)
                if n==3:
                    patch=cv.getRectSubPix(im,(136,110),tuple(center.astype(float)))
                    check=cv.getRectSubPix(display,(136,110),tuple(center.astype(float)))
                    cv.imwrite(str(out/f"{key}-{i}.jpg"),np.hstack([patch,check]))
            if n==3:
                cv.imwrite(str(out/(key+"-overview.jpg")),cv.resize(display,(528,704)))
            controlled.append(dict(id=key,group="Crigges",split="train",frame=n,
                        image=row["work_file"],sha256=row["work_sha256"],size=row["work_size"],
                        marks=marks,flags=(["empty"] if n==0 else ["grouped"] if n>1 else ["single"])
                        +(["worn"] if seq in ("13","70") else [])))
    write_json(out/"control-proposals.json",controlled)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("old",type=Path)
    p.add_argument("out",type=Path)
    a=p.parse_args()
    prepare(a.old,a.out)
