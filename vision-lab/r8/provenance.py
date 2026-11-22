"""Verify locked new sources against historical public photos and restore admitted pixels."""
import argparse
from itertools import combinations
from hashlib import sha256
from pathlib import Path

from lab_data import fingerprint, write_json
from r7.inventory import dhash
from r8.acquire import get
from r8.corpus import read


def check(root,old):
    rows=read(root/"corpus.json")
    records=[]
    for r in rows:
        if r["pool"]=="control":
            continue
        p=root/r["source"]["image"]
        records.append((r["id"],r["split"],r["group"],fingerprint(p),dhash(p)))
    public=old/"data/r7"
    historical=[public/r["source_file"] for r in read(public/"external.json")]
    historical.extend(public/"pilot"/r["work_file"] for r in read(public/"pilot/annotations.json"))
    for p in historical:
        records.append((p.name,"historical","archive",fingerprint(p),dhash(p)))
    nearby=[]
    for a,b in combinations(records,2):
        if a[1]==b[1]:
            continue
        if a[3]==b[3]:
            raise ValueError("Exact duplicate between splits")
        distance=(a[4]^b[4]).bit_count()
        if distance<=4:
            nearby.append(dict(left=a[0],right=b[0],distance=distance))
    write_json(root/"duplicates.json",dict(historical_work_or_public_frames=len(historical),
               new_admitted=sum(r["pool"]=="new" for r in rows),threshold=4,candidates=nearby))


def restore(root, all_sources=False):
    manifest=read(root/"inventory.json")
    for a in manifest["assets"]:
        if not all_sources and a["id"] not in manifest["admitted"]:
            continue
        dest=root/"media"/(a["id"]+".jpg")
        dest.parent.mkdir(exist_ok=True)
        if not dest.exists():
            raw=get(a["pixels_url"])
            if sha256(raw).hexdigest()!=a["sha256"]:
                raise ValueError("Publisher derivative changed")
            dest.write_bytes(raw)
        if fingerprint(dest)!=a["sha256"]:
            raise ValueError("Unexpected local photo")
        notice=root/"notices"/(a["id"]+".html")
        if fingerprint(notice)!=a["notice_sha256"]:
            raise ValueError("Changed frozen publisher notice")
        (root/"media"/notice.name).write_bytes(notice.read_bytes())

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("--r7",type=Path)
    p.add_argument("--all",action="store_true",help="Restore every acquired photo, including exclusions")
    a=p.parse_args()
    if a.r7:
        check(a.root,a.r7)
    else:
        restore(a.root,a.all)
