"""Replay R9 public discovery or restore the exact 206 frozen publisher derivatives."""
import argparse
import hashlib
from pathlib import Path
from urllib.parse import urlencode

from lab_data import fingerprint, write_json
from r8.acquire import get
from r8.corpus import read
from r8.flickr import collect

SEARCHES = [
    ("candidates","catalog",[("archery target arrows",2),("archery target arrows",3),("archery target arrows",4),
        ("archery bullseye",1),("archery target",2),("archery target",3),("arrows target",2),("archery gold arrows",1)]),
    ("extra","extra",[("archery grouping",1),("archery arrows gold target",1),("archery target bullseye",1),
        ("archery arrows group",1),("tir arc cible flèches",1),("arrow ends archery target",1),("archery target arrows",5)]),
    ("last","last",[("archery bullseye",2),("archery target arrows",6),("archery target arrows",7),
        ("archery target arrows",8),("archery indoor target",1),("archery robin hood arrows",1),("archery target ends",1)]),
]


def discover(root, r8):
    (root/"media").mkdir(parents=True,exist_ok=True)
    seen={r["url"] for r in read(r8/"data/r8/inventory.json")["assets"]}
    for name,prefix,queries in SEARCHES:
        assets={}
        for i,(query,page) in enumerate(queries):
            catalog=root/f"{prefix}-{i}.json"
            if not catalog.exists():
                params=urlencode(dict(q=query,page=page,source="flickr",license="by,by-sa,cc0",page_size=20))
                catalog.write_bytes(get("https://api.openverse.org/v1/images/?"+params))
            for a in read(catalog)["results"]:
                link=a["foreign_landing_url"]
                if link not in seen:
                    assets[link]=a
        seen.update(assets)
        path=root/(name+".json")
        write_json(path,dict(results=list(assets.values())))
        collect(root,path)


def restore(root):
    (root/"media").mkdir(parents=True,exist_ok=True)
    for a in read(root/"sources.json"):
        for path,url,sha in ((root/a["image"],a["pixels_url"],a["sha256"]),
                (root/"media"/(a["id"]+".html"),a["url"],a["notice_sha256"])):
            if not path.exists():
                data=get(url)
                if hashlib.sha256(data).hexdigest()!=sha:
                    raise ValueError("Publisher bytes changed. Use the frozen source capsule.")
                path.write_bytes(data)
            if fingerprint(path)!=sha:
                raise ValueError("Frozen photo or notice differs")


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("--discover",type=Path,metavar="R8")
    a=p.parse_args()
    if a.discover:
        discover(a.root,a.discover)
    else:
        restore(a.root)
