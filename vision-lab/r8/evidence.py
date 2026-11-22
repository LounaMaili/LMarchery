"""Pack local evidence separately from aggregate publishable measurements."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import numpy as np

from benchmark import maximum_matches
from lab_data import fingerprint, write_json
from r8.corpus import read

def summarize(rows):
    return dict(photos=len(rows),supports=len({b for r in rows for b in r["boards"]}),
                groups=len({r["group"] for r in rows}),tubes=sum(len(r["marks"]) for r in rows),
                contacts=sum(m["entry"] is not None for r in rows for m in r["marks"]),
                empty=sum("empty" in r["flags"] for r in rows))


def localization(rows, peaks, threshold):
    distances = []
    for row in rows:
        size = np.array(row["size"])
        scale = np.rint(size * min(1, 704/max(size))) / size
        truth = np.array([m["entry"] for m in row["marks"] if m["entry"] is not None]).reshape(-1, 2)*scale
        found = np.array([p for p,s in peaks[row["id"]] if s >= threshold]).reshape(-1, 2)*scale
        for i,j in maximum_matches(truth, found, 10):
            distances.append(float(np.linalg.norm(truth[i]-found[j])))
    return dict(matches=len(distances), median_px=float(np.median(distances)) if distances else None,
                p90_px=float(np.percentile(distances, 90)) if distances else None)

def package(root,out,aggregate):
    trials=out/"train"
    corpus=read(root/"corpus.json")
    report=dict(parent="33c12ba263bd2cd53be720045c533e54c7a4ee15",
                protocol_sha256=fingerprint(Path(__file__).with_name("protocole.md")),
                corpus_sha256=fingerprint(root/"corpus.json"),
                public_downloaded=len(read(root/"inventory.json")["assets"]),
                corpus={k:summarize([r for r in corpus if r["split"]==s and r["pool"]==p])
                        for k,s,p in (("control","train","control"),("new_train","train","new"),
                                      ("new_dev","dev","new"),("reserved","test","new"))},
                visual=read(out/"audit/categories.json"),audit=read(out/"audit/audit-replay.json"),
                choice=read(trials/"selection.json"),runs={},dev={})
    for path in sorted(trials.glob("?-????.json")):
        r=read(path)
        report["runs"][path.stem]={k:r[k] for k in (
              "arm","seed","steps","initial_sha256","checkpoint_sha256","crop_digests","wall_s",
              "pixels_positive_negative_ignored","positive_mass","dev_threshold")}
        report["runs"][path.stem]["curve"]=[{"step":c["step"],**{label:{g:c[label][g] for g in
               ("all","worn","grouped")} for label in ("at050","at090")}} for c in r["curve"]]
    for name,r in read(trials/"development.json").items():
        devrows=[row for row in corpus if row["split"]=="dev"]
        peaks=read(trials/(name+"-dev-peaks.json"))
        report["dev"][name]=dict(threshold=r["locked"],operating=r["operating"]["per_group"],
                                at050=r["at050"]["per_group"],at090=r["at090"]["per_group"],
                                localization={k:localization(devrows,peaks,t) for k,t in
                                    (("operating",r["locked"]),("at050",.5),("at090",.9))})
    write_json(aggregate,report)
    rights=("Photos publiques : attribution, licence et lien de la notice pour chaque image dans inventory.json.\n"
            "Annotations LabelMe source Crigges : CC BY 4.0. Les tracés propagés dérivent de ces annotations.\n"
            "Lectures ajoutées R8 : annotations de recherche locales, redistribution distincte non autorisée par ce manifeste.\n"
            "Les mentions et conditions individuelles des diffuseurs accompagnent les fichiers.\n")
    with ZipFile(out/"photos-publiques.zip","w",ZIP_DEFLATED) as z:
        z.write(root/"inventory.json","inventory.json")
        z.writestr("DROITS.txt",rights)
        for a in read(root/"inventory.json")["assets"]:
            for path,sha in ((root/a["image"],a["sha256"]),(root/"media"/(a["id"]+".html"),a["notice_sha256"])):
                if fingerprint(path)!=sha:
                    raise ValueError("Source differs from frozen rights and photo inventory")
                z.write(path,"media/"+path.name)
    with ZipFile(out/"corpus-annotations.zip","w",ZIP_DEFLATED) as z:
        z.writestr("DROITS.txt",rights)
        for nm in ("inventory","readings","corpus","new-public","openverse-verified","targets-verified",
                   "commons-retry","eligibility","duplicates"):
            z.write(root/(nm+".json"),nm+".json")
        z.write(root/"control/control-proposals.json","control/control-proposals.json")
        z.write(root/"cle-test.json","cle-test.json")
        for a in read(root/"inventory.json")["assets"]:
            p=root/"media"/(a["id"]+".html")
            z.write(p,"notices/"+p.name)
    with ZipFile(out/"apprentissage.zip","w",ZIP_DEFLATED) as z:
        for p in sorted(trials.glob("*")):
            z.write(p,"train/"+p.name)
        for p in (out/"audit").glob("*.json"):
            z.write(p,"audit/"+p.name)
        z.write(out/"before-learning.sha256","before-learning.sha256")
        z.write(aggregate,"mesures.json")

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("root","out","aggregate"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    package(a.root,a.out,a.aggregate)
