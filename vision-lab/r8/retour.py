"""Register a count-only human return. Keep its bytes, comments and blind follow-up separate."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from lab_data import fingerprint, write_json
from r8.corpus import read


def count(value):
    if not isinstance(value, list) or len(value) != 1 or type(value[0]) is not int or value[0] < 0:
        raise ValueError("Expected a nonnegative count in a one-element list")
    return value[0]


def register(root, submission, dest, empty, invalid):
    forms = read(root / "lecture-test/lecture.json")
    by_id = {r["id"]: r for r in forms}
    rows = read(submission)
    aliases = [r["id"] for r in rows]
    if sorted(aliases) != sorted(by_id) or not set(empty + invalid) <= set(by_id) or set(empty) & set(invalid):
        raise ValueError("Photo inventory differs from blind packet")
    summaries = []
    for row in rows:
        alias = row["id"]
        png = root / "lecture-test" / (alias + ".png")
        if (any(row[k] != by_id[alias][k] for k in ("taille", "sha256"))
                or fingerprint(png) != row["sha256"]):
            raise ValueError("Photo identity differs from blind packet")
        tubes, regions = count(row["tubes"]), count(row["zones_illisibles"])
        if alias in empty and (tubes or regions):
            raise ValueError("Conflicting empty declaration")
        state = "invalid_by_reader" if alias in invalid else "reported_empty" if alias in empty else "needs_points"
        summaries.append(dict(photo=alias, tubes=tubes, regions=regions, status=state,
                              completeness=row.get("inventaire_complet"), comment=row.get("commentaire")))
    dest.mkdir(parents=True, exist_ok=False)
    (dest / submission.name).write_bytes(submission.read_bytes())
    write_json(dest / "reception.json", dict(source=submission.name, sha256=fingerprint(submission),
               evidence="human_counts_no_geometry", photos=summaries))
    follow = dest / "complement.zip"
    with ZipFile(follow, "w", ZIP_DEFLATED) as packet:
        for name in ("annotate.html", "annotate.js"):
            packet.write(Path(__file__).resolve().parents[1] / "r3" / name, name)
        for item in summaries:
            if item["status"] != "needs_points":
                continue
            alias = item["photo"]
            record = by_id[alias]
            key = alias.replace("photo-", "review-")
            packet.writestr(key + ".json", json.dumps(dict(schema=1, id=key,
                original_sha256=record["sha256"], photo_sha256=record["sha256"], size=record["taille"])))
            packet.write(root / "lecture-test" / (alias + ".png"), alias + ".png")
        packet.writestr("LIRE.txt", "Ouvrir annotate.html après extraction du ZIP. Pour chaque photo, choisir la fiche "
            "review du même numéro et le PNG. Saisir un pseudonyme, puis tracer chaque tube observé et cliquer sur "
            "son entrée si elle est visible. Pour une entrée incertaine, sélectionner Ambigu ou Masqué. "
            "Cocher Inventaire complet si applicable. Exporter le JSON. Recharger la page pour la photo suivante. "
            "Renvoyer les deux exports avec la date de lecture. Le décompte initial et les commentaires sont conservés.\n")
    return summaries


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("root", "submission", "dest"):
        parser.add_argument(arg, type=Path)
    parser.add_argument("--empty", action="append", default=[])
    parser.add_argument("--invalid", action="append", default=[])
    args = parser.parse_args()
    register(args.root, args.submission, args.dest, args.empty, args.invalid)
