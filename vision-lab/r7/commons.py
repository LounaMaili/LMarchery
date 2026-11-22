"""Retrieve Commons source records, verify original revisions and freeze local readings."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import cv2 as cv
import numpy as np

from lab_data import fingerprint, write_json
from r5.review import reserved_hashes
from r7 import dvids
from r7.freeze import blind
from r7.prepare import project

DEV = (14935550,14935504,150946804,23785698,20132050,20132051,20132052,20132053,
       11706923,686502,35090546,130303446)
TEST = (174575673,27481421,16127104,57660867,25747387,185584183,4408771)


def request(url):
    req=Request(url,headers={"User-Agent":"LMarchery-vision-research/1.0 (github.com/LounaMaili/LMarchery)"})
    with urlopen(req,timeout=60) as response:
        return response.read()


def info(root):
    record=root/"commons-source.json"
    if not record.exists():
        query=urlencode(dict(action="query",format="json",prop="imageinfo",iiprop="url|size|sha1|extmetadata",
                             pageids="|".join(str(i) for i in DEV+TEST)))
        record.write_bytes(request("https://commons.wikimedia.org/w/api.php?"+query))
    return json.loads(record.read_text())["query"]["pages"]


def acquire(root, manifest=None):
    pages=info(root)
    folder=root/"commons"
    folder.mkdir(exist_ok=True)
    inventory=[]
    limited=False
    expected=({Path(item["path"]).stem:item["sha256"] for item in
               json.loads(manifest.read_text())["commons_files"]} if manifest else {})
    ids=[i for i in DEV+TEST if not manifest or str(i) in expected]
    if manifest and len(ids)!=len(expected):
        raise ValueError("Unknown Commons page in the frozen inventory")
    for pageid in ids:
        pg=pages[str(pageid)]
        source=pg["imageinfo"][0]
        path=folder/f"{pageid}.jpg"
        url=source["url"].split("?")[0]
        if not path.exists():
            if limited:
                continue
            try:
                raw=request(url)
            except HTTPError as exc:
                print("HTTP",exc.code,pageid,"retry-after",exc.headers.get("Retry-After"),flush=True)
                if exc.code==429:
                    limited=True
                continue
            if hashlib.sha1(raw).hexdigest()!=source["sha1"]:
                raise ValueError("Original revision changed")
            path.write_bytes(raw)
            time.sleep(1)
        if hashlib.sha1(path.read_bytes()).hexdigest()!=source["sha1"]:
            raise ValueError("Original revision changed")
        record=material(pg,path)
        if manifest and record["source_sha256"]!=expected[str(pageid)]:
            raise ValueError("Different original from frozen pilot")
        inventory.append(record)
    write_json(root/"commons-download.json",inventory)
    if manifest and len(inventory)!=len(expected):
        raise RuntimeError("Incomplete Commons retrieval; resume after server retry interval")


def material(pg, path):
    source=pg["imageinfo"][0]
    meta=source["extmetadata"]
    clean=lambda key: html.unescape(re.sub("<[^>]+>","",meta.get(key,{}).get("value",""))).strip()
    return dict(id=str(pg["pageid"]), title=pg["title"], source_file="commons/"+path.name,
                original_url=source["url"], source_url=source["descriptionurl"],
                revision_sha1=source["sha1"], source_sha256=fingerprint(path),
                credit=clean("Artist"),license=clean("LicenseShortName"),license_url=clean("LicenseUrl"),
                held_by=clean("Credit"), split="dev" if pg["pageid"] in DEV else "test")


def seal(root,reading):
    pages=info(root)
    read=json.loads(reading.read_text())
    records,excluded=[],[]
    historical=reserved_hashes()
    train=json.loads((root/"pilot"/"annotations.json").read_text())
    historical.update(r["source_sha256"] for r in train)
    for key in [str(i) for i in DEV+TEST]+list(dvids.ASSETS):
        if key not in read["include"]:
            page=("https://www.dvidshub.net/image/"+key if key in dvids.ASSETS
                  else pages[key]["imageinfo"][0]["descriptionurl"])
            excluded.append(dict(id=key,reason=read["exclude"].get(key,"original_unavailable"),source_url=page))
            continue
        if key in dvids.ASSETS:
            row=dvids.material(root,key)
        else:
            row=material(pages[key],root/"commons"/(key+".jpg"))
        path=root/row["source_file"]
        if ("revision_sha1" in row and hashlib.sha1(path.read_bytes()).hexdigest()!=row["revision_sha1"]
                or row["source_sha256"] in historical):
            raise ValueError("Duplicate, historical or changed original")
        historical.add(row["source_sha256"])
        im=cv.imread(str(path))
        h,w=im.shape[:2]
        data=read["include"][key].copy()
        scale=np.array([w,h])/data.pop("display_size")
        for inst in data["instances"]:
            inst["axis"]=project(inst["axis"],scale).tolist()
            if inst["entry"] is not None:
                inst["entry"]=project(inst["entry"],scale).tolist()
        data["ignore_regions"]=[project(box,scale).tolist() for box in data["ignore_regions"]]
        records.append(row | data | dict(oriented_size=[w,h],group=row["credit"],
                       reading_status="provisional_assistant_visual_reading_no_independent_human"))
    splits={s:{r["group"] for r in records if r["split"]==s} for s in ("dev","test")}
    if splits["dev"] & splits["test"]:
        raise ValueError("Shared photographer between dev and test")
    write_json(root/"external.json",records)
    write_json(root/"external-exclusions.json",excluded)
    blind(root,records,root/"blind-external")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("--reading",type=Path)
    p.add_argument("--manifest",type=Path)
    a=p.parse_args()
    if a.reading:
        seal(a.root,a.reading)
    else:
        acquire(a.root,a.manifest)
