"""Comparaison appariée sur les 17 photos R12 déjà explorées."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
from time import perf_counter

import cv2 as cv
import numpy as np
import torch

from lab_data import fingerprint
from r7.model import ContactNet,decode,full_maps
from r7.prepare import resized
from r10.diagnostic import read
from r10.tableaux import RUNS
from r11.comparer import inputs
from r12.geometrie import repere,geometry_index
from r12.comparer import save
from r13.entrees import inventory
from r13.diagnostic import audit,components,geometry,shortened
from r13.segments import reconstruct


METHODS=('H','A','B','O0','O20','O40','F20','F40')


def compare(r8,r9,r12,out):
    if inventory()!=read(Path('r13/entrees.json')):
        raise ValueError('Entrées publiques différentes du gel')
    rows,images,weights,_=inputs(r8,r9)
    for row in read(Path('r12/nouvelles.json'))['photos']:
        if row['split']=='nouveau':
            path=r12/'data/r12/media'/(row['id']+'.jpg')
            assert fingerprint(path)==row['source']['sha256']
            rows.append(row)
            images[row['id']]=resized(cv.imread(str(path)))
    assert len(rows)==17
    out.mkdir(parents=True,exist_ok=False)
    masks=out/'masques'
    masks.mkdir()
    cv.setNumThreads(1)
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    for name in RUNS:
        torch.manual_seed(int(name.split('-')[1]))
        model=ContactNet().to(memory_format=torch.channels_last)
        model.load_state_dict(torch.load(weights[name],weights_only=True,map_location='cpu'))
        model.eval()
        previous=read(Path('r12/predictions')/(name+'.json'))
        result={}
        for row in rows:
            k=row['id']
            im=images[k]
            maps=full_maps(model,im)
            peaks,tubes=decode(maps,max_peaks=192)
            digest=sha256(maps.tobytes(order='C')).hexdigest()
            old=previous[k]
            assert digest==old['carte_sha256'] and len(peaks)==len(old['candidats'])==192
            scale,board,mask,supported=repere(im,maps[1])
            start=perf_counter()
            native,labels,reasons=components(maps[1])
            a=[l*scale for l in native]
            t_a=perf_counter()-start
            assert np.array_equal(np.asarray(a),np.asarray(old['axes_detectes_repere704']))
            start=perf_counter()
            b=reconstruct(board,supported)
            t_b=perf_counter()-start
            coords=np.array([p for p,_ in peaks])*scale
            heat=np.array([s for _,s in peaks])
            scores={'H':heat}
            for method,axes in [('A',a),('B',b)]:
                scores[method]=heat+(1-heat)*geometry_index(coords,axes,board,supported)
            removed={}
            for method in METHODS[3:]:
                axes,removed[method]=shortened(row,scale,int(method[1:]),method[0]=='O')
                scores[method]=heat+(1-heat)*geometry_index(coords,axes,board,supported,annotated=True)
            packed=[[xy]+[float(scores[m][i]) for m in METHODS] for i,(xy,s) in enumerate(peaks)]
            for p,ref in zip(packed,old['candidats'],strict=True):
                assert [p[0],p[1],p[2],p[4]]==ref[:4]
            if name=='ordinary-9301':
                assert cv.imwrite(str(masks/(k+'.png')),mask*255)
            result[k]=dict(carte_sha256=digest,temps_s=dict(A=t_a,B=t_b),echelle=scale.tolist(),
                           axes_A=[l.tolist() for l in a],axes_B=[l.tolist() for l in b],
                           audit=audit(row,scale,maps[1],labels,reasons),
                           A=geometry(row,a,scale,mask,supported),B=geometry(row,b,scale,mask,supported),
                           traits_supprimes=removed,candidats=packed)
        save(out/(name+'.json'),result)
        print(name,len(result),'parités vérifiées',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('r8','r9','r12','out'):
        p.add_argument(key,type=Path)
    compare(**vars(p.parse_args()))
