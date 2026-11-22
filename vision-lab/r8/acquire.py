"""Cache the publisher's 2000-pixel derivatives and photo-specific public-domain notices."""
import argparse
import html
from pathlib import Path
import re
from urllib.request import Request, urlopen

from lab_data import fingerprint, write_json

PAGES = {
    "8501604":"2024-dod-warrior-games-athletes-nock-aim-and-hit-their-targets-with-adaptive-archery-competition",
    "875955":"2013-marine-corps-trials", "2578501":"invictus-games-2016",
    "4817876":"team-us-invictus-games-training-camp",
    "929865":"army-veteran-overcomes-injuries-compete-gold-warrior-games",
    "8961160":"2025-army-trials",
}


def get(url):
    req=Request(url,headers={"User-Agent":"LMarchery/1.0 (github.com/LounaMaili/LMarchery)"})
    with urlopen(req,timeout=45) as resp:
        return resp.read()


def acquire(root):
    folder=root/"media"
    folder.mkdir(parents=True,exist_ok=True)
    manifest=[]
    for key,slug in PAGES.items():
        url=f"https://www.dvidshub.net/image/{key}/{slug}"
        source=folder/(key+".html")
        if not source.exists():
            source.write_bytes(get(url))
        page=source.read_text()
        if "PUBLIC DOMAIN" not in page:
            raise ValueError("Missing public-domain notice for "+key)
        def extract(pattern):
            m=re.search(pattern,page)
            if m is None:
                raise ValueError("Missing publisher field for "+key+": "+pattern)
            return html.unescape(m[1]).strip()
        jpeg=extract(r'(https://[^"<> ]*/'+key+r'/2000w_q95.jpg)')
        path=folder/(key+".jpg")
        if not path.exists():
            path.write_bytes(get(jpeg))
        manifest.append(dict(id=key,url=url,pixels_url=jpeg,sha256=fingerprint(path),
                  notice_sha256=fingerprint(source),credit=extract(r'<h3>Photo by\s+<a[^>]*>([^<]+)'),
                  virin=extract(r'VIRIN:</td>\s*<td>([^<]+)'),published_dimensions=extract(r'Resolution:</td>\s*<td>([^<]+)'),
                  license="Public Domain (DVIDS)",terms="https://www.dvidshub.net/about/copyright",
                  image="media/"+path.name))
        write_json(root/"new-public.json",manifest)
        print(key,manifest[-1]["credit"],flush=True)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    acquire(p.parse_args().root)
