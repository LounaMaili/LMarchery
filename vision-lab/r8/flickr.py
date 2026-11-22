"""Use the publisher's photo-specific structured data to verify public Flickr derivatives."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlparse

from lab_data import fingerprint, write_json
from r8.acquire import get

def collect(root, catalog):
    results=[]
    for ref in json.loads(catalog.read_text())["results"]:
        url=ref["foreign_landing_url"]
        key=url.rstrip("/").split("/")[-1]
        if not key.isdigit() or urlparse(url).hostname!="www.flickr.com":
            continue
        folder=root/"media"
        page=folder/(key+".html")
        try:
            if not page.exists():
                page.write_bytes(get(url))
            raw=page.read_text()
            license=re.search(r'"license":\s*"(https://creativecommons.org/[^"]+)"',raw)
            jpg=re.search(r'<meta property="og:image" content="([^"]+)"',raw)
            if not license or not jpg or not license[1].startswith(("https://creativecommons.org/licenses/by/","https://creativecommons.org/licenses/by-sa/","https://creativecommons.org/publicdomain/zero/")):
                raise ValueError("Unconfirmed publisher license")
            if license[1].rstrip("/")!=ref["license_url"].rstrip("/") or urlparse(jpg[1]).hostname!="live.staticflickr.com" or key+"_" not in jpg[1]:
                raise ValueError("Publisher identity, derivative or license mismatch")
            path=folder/(key+".jpg")
            if not path.exists():
                path.write_bytes(get(jpg[1]))
            results.append(dict(id=key,url=url,pixels_url=jpg[1],sha256=fingerprint(path),
                           notice_sha256=fingerprint(page),credit=ref["creator"],author_url=ref["creator_url"],
                           title=ref["title"],terms=license[1],license=ref["license"].upper()+" "+ref["license_version"],
                           image="media/"+path.name))
        except (OSError,ValueError) as exc:
            print(key,str(exc),flush=True)
    write_json(root/(catalog.stem+"-verified.json"),results)
    print(len(results),"verified photos",flush=True)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("root",type=Path)
    p.add_argument("catalog",type=Path)
    a=p.parse_args()
    collect(a.root,a.catalog)
