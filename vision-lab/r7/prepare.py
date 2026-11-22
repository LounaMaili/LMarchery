"""Verify source pixels and partial LabelMe geometry, then create working derivatives."""
import argparse
from collections import Counter
import json
from pathlib import Path

import cv2 as cv
import numpy as np

from lab_data import fingerprint, write_json
from r7.acquire import LICENSE, PAGE

SIDE = 1408


def geometry(annotation, filename, size):
    if annotation.get("imagePath") != filename or [annotation.get("imageWidth"),
                                                   annotation.get("imageHeight")] != size:
        raise ValueError("image_label_mismatch")
    polygons, circles = [], []
    for shape in annotation["shapes"]:
        coords = np.asarray(shape["points"], dtype=float)
        if coords.ndim != 2 or coords.shape[1] != 2 or not np.isfinite(coords).all():
            raise ValueError("nonfinite_or_invalid_geometry")
        if (coords < 0).any() or (coords >= size).any():
            raise ValueError("geometry_outside_original")
        pair = shape["label"], shape["shape_type"]
        if pair == ("arrow","polygon") and len(coords) >= 3:
            if cv.contourArea(coords.astype(np.float32)) < 1:
                raise ValueError("empty_polygon")
            if coords.tolist() not in polygons:
                polygons.append(coords.tolist())
        elif pair == ("impact","circle") and len(coords) == 2:
            circles.append(coords.tolist())
        else:
            raise ValueError("unexpected_shape")
    if len(polygons) != 1:
        raise ValueError("ambiguous_instance_polygons")
    unique = {tuple(s[0]) for s in circles}
    point, reason, radius = None, "missing_or_conflicting_point", None
    if len(unique) == 1:
        point = list(next(iter(unique)))
        polygon = np.asarray(polygons[0], np.float32)
        radius = max(float(np.linalg.norm(np.subtract(s[0],s[1]))) for s in circles)
        if cv.pointPolygonTest(polygon, tuple(point), True) < -max(12, radius*2):
            point, reason = None, "point_away_from_mask"
        else:
            reason = "source_candidate"
    return polygons[0], point, reason, radius, len(circles)-len(unique)


def resized(im, side=SIDE):
    h,w = im.shape[:2]
    ratio = min(1., side/max(w,h))
    shape = (round(w*ratio), round(h*ratio))
    if shape != (w,h):
        return cv.resize(im, shape, interpolation=cv.INTER_AREA)
    return im


def project(points, scale):
    return (np.asarray(points, dtype=float)+.5)*scale-.5


def prepare(root, output):
    from PIL import Image
    src = json.loads((root/"inventory.json").read_text())
    if (src["version"], src["credit"], src["license"]) != (1,"Crigges",LICENSE):
        raise ValueError("Unexpected dataset or license")
    files = {r["path"]:r for r in src["files"]}
    output.mkdir(parents=True,exist_ok=True)
    (output/"work").mkdir(exist_ok=True)
    rows, rejected, hashes = [], [], set()
    for name, spec in files.items():
        if not name.endswith(".png"):
            continue
        seq, file = name.split("/")
        number = int(Path(file).stem)
        path = root/"originals"/name
        if fingerprint(path) != spec["sha256"]:
            raise ValueError("Original changed: "+name)
        with Image.open(path) as raw:
            orientation = raw.getexif().get(274,1)
        image = cv.imread(str(path),cv.IMREAD_COLOR | cv.IMREAD_IGNORE_ORIENTATION)
        if image is None:
            raise ValueError("Unreadable original: "+name)
        size = list(image.shape[1::-1])
        label_file = name[:-3]+"json"
        label = files.get(label_file)
        poly, point, status, radius, repeated = None, None, "source_empty", None, 0
        try:
            if orientation != 1:
                raise ValueError("unresolved_orientation")
            if spec["sha256"] in hashes:
                raise ValueError("duplicate_original")
            if number:
                label_path = root/"originals"/label_file
                if label is None or fingerprint(label_path) != label["sha256"]:
                    raise RuntimeError("Missing or changed label: "+label_file)
                poly, point, status, radius, repeated = geometry(json.loads(label_path.read_text()),
                                                               file, size)
        except ValueError as err:
            rejected.append(dict(path=name, reason=str(err), original_sha256=spec["sha256"]))
            continue
        hashes.add(spec["sha256"])
        work = resized(image)
        key = seq+"-"+Path(file).stem
        dest = output/"work"/(key+".png")
        if not cv.imwrite(str(dest),work,[cv.IMWRITE_PNG_COMPRESSION,1]):
            raise OSError(dest)
        rows.append(dict(id=key, split="train", source=PAGE, credit="Crigges", version=1,
                         license="CC BY 4.0", group="crigges-lg-g4", sequence=seq, frame=number,
                         source_file=name, source_sha256=spec["sha256"], source_url=spec["url"],
                         annotation_url=label["url"] if label else None,
                         annotation_sha256=label["sha256"] if label else None,
                         oriented_size=size, exif_orientation=orientation, work_size=list(work.shape[1::-1]),
                         work_file="work/"+dest.name, work_sha256=fingerprint(dest),
                         new_instance=key if number else None, polygon=poly, source_point=point,
                         circle_radius_px=radius, repeat_circles=repeated, geometry_status=status,
                         previous_instances=[f"{seq}-{i:02}" for i in range(1,number)],
                         previous_status="sequence_history_only_no_current_geometry",
                         source_exhaustive=number<=1))
    write_json(output/"candidates.json",rows)
    write_json(output/"exclusions.json",rejected)
    print(len(rows), "candidates;", Counter(r["reason"] for r in rejected))


def sheets(root):
    rows=json.loads((root/"candidates.json").read_text())
    # Unmarked local pixels, with a separate cross on the right for source-point inspection.
    positives=[r for r in rows if r["source_point"] is not None]
    for start in range(0,len(positives),24):
        page=np.full((6*125,4*230,3),230,np.uint8)
        for i,r in enumerate(positives[start:start+24]):
            im=cv.imread(str(root/r["work_file"]))
            scale=np.array(r["work_size"])/r["oriented_size"]
            x,y=np.rint(project(r["source_point"],scale)).astype(int)
            patch=cv.getRectSubPix(im,(110,100),(float(x),float(y)))
            marked=patch.copy()
            cv.drawMarker(marked,(55,50),(255,0,255),cv.MARKER_CROSS,10,1)
            x0,y0=(i%4)*230,(i//4)*125
            page[y0:y0+100,x0:x0+220]=np.hstack([patch,marked])
            cv.putText(page,r["id"],(x0+2,y0+119),0,.49,(0,0,0),1)
        cv.imwrite(str(root/f"contacts-{start:03}.jpg"),page)
    for number in (0,1):
        group=[r for r in rows if r["frame"]==number]
        page=np.full((((len(group)+3)//4)*190,4*185,3),230,np.uint8)
        for i,r in enumerate(group):
            im=resized(cv.imread(str(root/r["work_file"])),162)
            x,y=(i%4)*185,(i//4)*190
            page[y:y+im.shape[0],x:x+im.shape[1]]=im
            cv.putText(page,r["id"],(x+2,y+183),0,.5,(0,0,0),1)
        cv.imwrite(str(root/f"count-{number}.jpg"),page)


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("source",type=Path)
    p.add_argument("output",type=Path)
    args=p.parse_args()
    cv.setNumThreads(1)
    prepare(args.source,args.output)
    sheets(args.output)
