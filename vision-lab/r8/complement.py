"""Bind two-stage blind reader exports to the original, count-only return."""
import argparse
from datetime import date
from pathlib import Path

from lab_data import fingerprint, write_json
from r8.adjudicate import compare
from r8.corpus import read


def receive(root, reception, outputs, destination, day):
    date.fromisoformat(day)
    forms = read(root / "lecture-test/lecture.json")
    pending = {row["photo"] for row in read(reception)["photos"] if row["status"] == "needs_points"}
    readers, seen, hashes = set(), set(), {}
    statuses = {"visible":"visible", "ambiguous":"indeterminable", "occluded":"occulte", "out_of_frame":"hors_champ"}
    for path in outputs:
        r = read(path)
        alias = r["id"].replace("review-", "photo-", 1)
        if alias not in pending or alias in seen or r["schema"] != 1:
            raise ValueError("Unexpected, duplicate or unsupported export")
        row = next(f for f in forms if f["id"] == alias)
        if (r["photo_sha256"] != row["sha256"] or r["original_sha256"] != row["sha256"]
                or r["size"] != row["taille"]):
            raise ValueError("Export differs from sealed photo")
        readers.add(r["annotated_by"].strip())
        seen.add(alias)
        hashes[alias] = fingerprint(path)
        row.update(inventaire_complet=r["complete"], tubes=[
            dict(axe=a["axis"], statut=statuses[a["visibility"]], entree=a["entry"]) for a in r["arrows"]])
    if seen != pending or len(readers) != 1 or "" in readers:
        raise ValueError("Missing photos or inconsistent reader identifier")
    destination.mkdir(parents=True, exist_ok=False)
    combined = destination / "coordonnees.json"
    write_json(combined, forms)
    compare(root, combined, destination / "comparaison.json", readers.pop(), day, reception)
    write_json(destination / "empreintes.json", dict(count_receipt=fingerprint(reception), exports=hashes))
    for path in outputs:
        r = read(path)
        (destination / (r["id"] + "-original.json")).write_bytes(path.read_bytes())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "reception", "destination"):
        parser.add_argument(name, type=Path)
    parser.add_argument("exports", nargs="+", type=Path)
    parser.add_argument("--date", required=True)
    a = parser.parse_args()
    receive(a.root, a.reception, a.exports, a.destination, a.date)
