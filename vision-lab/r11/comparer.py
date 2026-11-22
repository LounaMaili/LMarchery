"""One sequential inference per frozen checkpoint and development photograph."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import platform
from zipfile import ZipFile

import cv2 as cv
import numpy as np
import torch
import torchvision

from lab_data import fingerprint, write_json
from r7.model import ContactNet, decode, full_maps
from r10.diagnostic import read
from r10.tableaux import RUNS


LAB = Path(__file__).resolve().parents[1]


def checked_file(path, expected):
    if fingerprint(path) != expected:
        raise ValueError(f"Changed input: {path.name}")
    return path


def inputs(r8, r9):
    frozen = read(LAB / "r10/entrees.json")
    rows = read(checked_file(r9 / "data/r9/dev.json", frozen["manifest_sha256"]))
    baseline = read(LAB / "r9/mesures.json")["runs"]
    public = {r["id"]: r for r in read(LAB / "r9/sources.json")["photos"] if r["split"] == "dev"}
    ids = {r["id"] for r in rows}
    if len(rows) != 12 or ids != public.keys() or len(set(RUNS)) != 6:
        raise ValueError("Unexpected R9 population")
    images = {}
    for row in rows:
        ident = row["id"]
        if row["split"] != "dev" or row["sha256"] != public[ident]["pixels_travail_sha256"]:
            raise ValueError("Development manifest differs from published register")
        root = r8 / "data/r8" if row["origin"] == "r8-development" else r9 / "data/r9"
        path = checked_file(root / row["image"], row["sha256"])
        image = cv.imread(str(path))
        if image is None or list(image.shape[1::-1]) != row["size"]:
            raise ValueError(f"Unreadable image {ident}")
        images[ident] = image

    archive = checked_file(r9 / "artifacts/r9/experiences.zip", frozen["archive_sha256"])
    checkpoints, references = {}, {}
    with ZipFile(archive) as z:
        for name in RUNS:
            checkpoints[name] = checked_file(r9 / f"artifacts/r9/train/{name}.pt", baseline[name]["sha256"])
            member = f"train/{name}-dev-peaks.json"
            data = z.read(member)
            if sha256(data).hexdigest() != frozen["members"][member]["sha256"]:
                raise ValueError(f"Changed archived predictions for {name}")
            refs = json.loads(data)
            if refs.keys() != ids or any(len(p) != 96 for p in refs.values()):
                raise ValueError(f"Unexpected reference lists for {name}")
            references[name] = refs
    return rows, images, checkpoints, references


def r9_bytes(obj):
    return (json.dumps(obj, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()


def save_peaks(path, photos):
    with path.open("x", encoding="utf-8") as out:
        out.write("{\n")
        for index, (ident, peaks) in enumerate(photos.items()):
            if index:
                out.write(",\n")
            out.write(f'  {json.dumps(ident)}: [\n')
            out.write(",\n".join("    " + json.dumps(p, allow_nan=False) for p in peaks))
            out.write("\n  ]")
        out.write("\n}\n")


def compare(r8, r9, out, check_only=False):
    rows, images, checkpoints, references = inputs(r8, r9)
    print(f"Verified {len(rows)} photos, {len(checkpoints)} checkpoints and six archived peak files", flush=True)
    if check_only:
        return

    out.mkdir(parents=True, exist_ok=False)
    (out / "pics").mkdir()
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    cv.setNumThreads(1)
    audit = dict(python=platform.python_version(), torch=str(torch.__version__),
                 torchvision=str(torchvision.__version__), numpy=np.__version__, opencv=cv.__version__,
                 torch_threads=torch.get_num_threads(), cv_threads=cv.getNumThreads(),
                 processus_inference=1, appels_full_maps=0, essais={})
    for name in RUNS:
        torch.manual_seed(int(name.split("-")[1]))
        model = ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(checkpoints[name], map_location="cpu", weights_only=True))
        model.eval()
        photos, checks = {}, {}
        for row in rows:
            ident = row["id"]
            audit["appels_full_maps"] += 1
            maps = full_maps(model, images[ident])
            peaks, _ = decode(maps, max_peaks=192)
            photos[ident] = peaks
            refs = references[name][ident]
            short = peaks[:96]
            checks[ident] = dict(passages=1, mesures=len(peaks),
                                 coordonnees_identiques=len(short)==len(refs) and all(a[0]==b[0] for a,b in zip(short,refs)),
                                 scores_identiques=len(short)==len(refs) and all(a[1].hex()==b[1].hex() for a,b in zip(short,refs)),
                                 sha256_cartes=sha256(maps.tobytes(order="C")).hexdigest())
        save_peaks(out / "pics" / (name + ".json"), photos)
        original = references[name]
        short = {ident: peaks[:96] for ident,peaks in photos.items()}
        expected = sha256(r9_bytes(original)).hexdigest()
        actual = sha256(r9_bytes(short)).hexdigest()
        archived_hash = read(LAB / "r10/entrees.json")["members"][f"train/{name}-dev-peaks.json"]["sha256"]
        audit["essais"][name] = dict(checkpoint_sha256=fingerprint(checkpoints[name]),
                                     sha256_archive=archived_hash,sha256_prefixe_96=actual,
                                     fichier_identique=(actual==expected==archived_hash),photos=checks)
        write_json(out / "parite.json", audit)
        print(name, len(photos), "photos, parité", actual==expected==archived_hash, flush=True)
    if audit["appels_full_maps"] != 72 or not all(e["fichier_identique"] for e in audit["essais"].values()):
        raise ValueError("Prefix verification failed. Review audit before interpreting results.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("r8", "r9", "out"):
        parser.add_argument(key, type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    compare(args.r8, args.r9, args.out, args.check_only)
