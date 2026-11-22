"""Public-domain development derivatives published by DVIDS, with immutable digests."""
import argparse
from pathlib import Path
from urllib.request import urlopen

from lab_data import fingerprint

ASSETS={
    "570904":("1205","archer-recovers-arrows","Sgt. 1st Class Raymond Piper","120502-A-5261P-975",
              [2848,4288],"6f40265e5b85f8ac15c9f008ebbebb37617185548c7e4dd4c03f3f84fc73ef2c"),
    "4194279":("1803","soldiers-veterans-target-2018-army-trials-archery-competition","Marcy Sanchez",
               "180305-A-EK666-610",[3111,2223],"a515ba4270927a3da7284a121f24b9e29c89302455380fbb5493344f609227e3"),
}


def material(root,key):
    date,slug,author,virin,size,digest=ASSETS[key]
    file="dvids/"+key+".jpg"
    page=f"https://www.dvidshub.net/image/{key}/{slug}"
    url=f"https://d1ldvf68ux039x.cloudfront.net/thumbs/photos/{date}/{key}/2000w_q95.jpg"
    path=root/file
    path.parent.mkdir(exist_ok=True)
    source=path.with_suffix(".html")
    if not source.exists():
        with urlopen(page,timeout=30) as resp:
            source.write_bytes(resp.read())
    h=source.read_text()
    if not all(token in h for token in ("PUBLIC DOMAIN",author,virin,url)):
        raise ValueError("DVIDS provenance changed")
    if not path.exists():
        with urlopen(url,timeout=30) as resp:
            path.write_bytes(resp.read())
    if fingerprint(path)!=digest:
        raise ValueError("DVIDS revision changed")
    return dict(id=key,split="dev",group=author,credit=author,source_file=file,source_sha256=digest,
                license="Public Domain (DVIDS)",license_url="https://www.dvidshub.net/about/copyright",
                source_url=page,version=virin,published_derivative_url=url,publication_size=size,
                provenance_sha256=fingerprint(source))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    a=p.parse_args()
    for key in ASSETS:
        material(a.root,key)
