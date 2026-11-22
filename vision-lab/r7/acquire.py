"""Fetch an immutable, deterministic subset of the original Crigges v1 files."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote, urlencode
import zipfile

from lab_data import fingerprint, write_json

DATASET = "crigges/arrow-sequences"
PAGE = "https://www.kaggle.com/datasets/" + DATASET
API = "https://www.kaggle.com/api/v1/datasets/"
LICENSE = "Attribution 4.0 International (CC BY 4.0)"


def get(url):
    return subprocess.check_output(["curl", "-fsSL", "--retry", "3", "--retry-all-errors",
                                    "--max-time", "120", url])


def catalog():
    meta = json.loads(get(API + "view/" + DATASET))
    if meta["currentVersionNumber"] != 1 or meta["creatorName"] != "Crigges" or meta["licenseName"] != LICENSE:
        raise ValueError("Dataset version, credit or license changed")
    files = {}
    token = ""
    tokens = set()
    while True:
        args = {"pageSize": 200}
        if token:
            args["pageToken"] = token
        page = json.loads(get(API + "list/" + DATASET + "?" + urlencode(args)))
        for item in page["datasetFiles"]:
            files[item["name"]] = int(item["totalBytes"])
        token = page.get("nextPageToken")
        if not token:
            return files, meta
        if token in tokens:
            raise ValueError("Repeated catalog pagination token")
        tokens.add(token)


def selection(files):
    # Exclude the R6 audit sequence, including all its later frames.
    seqs = {p.split("/")[0] for p in files if re.fullmatch(r"[0-9]+/00\.png", p)} - {"10"}
    names = [f"{i:02}.{ext}" for i in range(8) for ext in (("png", "json") if i else ("png",))]
    eligible = [s for s in seqs if all(s+"/"+n in files for n in names)]
    ranked = sorted(eligible, key=lambda s: hashlib.sha256(("r7-pilot-v1/"+s).encode()).digest())
    selected = [s+"/"+n for s in ranked[:20] for n in names]
    if len(ranked) < 20:
        raise ValueError("Fewer than twenty complete contiguous sequences")
    return selected


def acquire(root):
    root.mkdir(exist_ok=True, parents=True)
    files, meta = catalog()
    paths = selection(files)
    write_json(root/"source.json", {k: meta[k] for k in ("id","creatorName","currentVersionNumber",
                                                      "licenseName","description","totalBytes")} | {"url": PAGE})
    def fetch(name):
        path = root/"originals"/name
        path.parent.mkdir(exist_ok=True, parents=True)
        url = API+"download/"+DATASET+"/"+quote(name, safe="")+"?datasetVersionNumber=1"
        if not path.is_file() or path.stat().st_size != files[name]:
            raw = get(url)
            if raw.startswith(b"PK\x03\x04"):
                with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                    info, = archive.infolist()
                    if Path(info.filename).name != path.name or info.file_size != files[name]:
                        raise ValueError("Unexpected ZIP member for "+name)
                    raw = archive.read(info)
            if len(raw) != files[name]:
                raise ValueError("Source byte count mismatch: "+name)
            tmp = path.with_suffix(path.suffix+".part")
            tmp.write_bytes(raw)
            tmp.replace(path)
        return dict(path=name, bytes=files[name], sha256=fingerprint(path), url=url)
    with ThreadPoolExecutor(max_workers=3) as pool:
        inventory = list(pool.map(fetch, paths))
    write_json(root/"inventory.json", {"dataset": DATASET,"version":1,"credit":"Crigges",
                                       "license":LICENSE,"files":inventory})
    print(len(inventory), "verified source files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    acquire(parser.parse_args().root)
