"""Extraire les seuls enregistrements Crigges train admis, sans ouvrir de photo."""
import argparse
from pathlib import Path

import numpy as np

from r7.prepare import project
from r14.mesurer import read,save
from r15.corpus import corpus,fingerprint
from r15.restaurer import historique


def extract(out,worktrees=None):
    allowed={p['id']:p for p in corpus() if p['partition']=='train' and p['provenance']=='Crigges'}
    items={r['nom']:r for r in read('r14/inventaire.json') if r['nom'] in ('annotations.json','corpus.json')}
    records={}
    polygons={}
    for name in ['annotations.json','corpus.json']:
        spec=items[name]
        path=historique(spec['emplacement'],worktrees)
        if fingerprint(path)!=spec['sha256']:
            raise ValueError(name)
        for r in read(path):
            if r['split']!='train' or r['id'] not in allowed:
                continue
            k=r['id']
            if name=='annotations.json':
                records[k]={key:r[key] for key in ('sequence','frame','new_instance','previous_instances','previous_status',
                            'oriented_size','work_size','source_file','annotation_url','annotation_sha256')}
                if r['new_instance']:
                    points=project(r['polygon'],np.array(r['work_size'])/r['oriented_size']).tolist()
                    polygons[f"R7/{k}/{r['new_instance']}"]=dict(origine='LabelMe source de la nouvelle flèche',
                        photo=k,instance=r['new_instance'],points_travail=points)
            else:
                for m in r['marks']:
                    if not m['polygon']:
                        raise ValueError(k)
                    polygons[f"R8/{k}/{m['instance']}"]=dict(photo=k,instance=m['instance'],origine=m['origin'],
                        statut_relecture=m['reading'],points_travail=m['polygon'])
    if set(records)!=set(allowed):
        raise ValueError('Acquisitions manquantes')
    out.mkdir(parents=True,exist_ok=True)
    save(out/'historique-sequences.json',dict(sources=items,photos=records))
    save(out/'polygones.json',dict(repere='Pixels XY de travail orientés de la photo indiquée. R7 utilise la projection des centres de pixels du laboratoire.',polygones=polygons))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    p.add_argument('--worktrees',type=Path)
    extract(**vars(p.parse_args()))
