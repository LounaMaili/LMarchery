"""Références des deux vues et contrôle préalable, sans appeler le modèle."""
from hashlib import sha256
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.donnees import eval_size, fingerprint, input_size, read, work_to
from r20.operations import tensors
from r21.plan import tensor_state
from r23.commun import ARMS, PHOTOS, SEED, STEPS, archive


def references():
    specs={p['photo']:p for p in read('r21/configuration.json')['photos'] if p['photo'] in PHOTOS}
    records={p['photo']:p for p in read('r18/validites.json')['vues'] if p['photo'] in PHOTOS}
    refs={}
    for k in PHOTOS:
        spec,record=specs[k],records[k]
        if spec['partition']!='train' or record['partition']!='train':
            raise ValueError('Photo hors train')
        stem=Path('r18')
        annotation_path=stem/'annotations'/(k+'.json')
        mask_path=stem/'masques'/(k+'.png')
        if fingerprint(mask_path)!=spec['sha256_masque'] or fingerprint(annotation_path)!=spec['sha256_annotation']:
            raise ValueError('Référence différente '+k)
        annotation=read(annotation_path)
        mask=cv.imread(str(mask_path),cv.IMREAD_UNCHANGED)
        size=np.asarray(spec['dimensions_travail'])
        view=eval_size(size)
        grid=np.asarray(input_size(size))//2
        entries={i['instance']:work_to(i['entree_supervisee'],size,view) for i in record['instances'] if i['entree_valide']}
        axes={i['instance']:[work_to(line,size,view) for line in i['axes_supervises']]
              for i in record['instances'] if i['axe_valide']}
        ids={i['instance']:i['indice'] for i in record['instances'] if i['masque_valide']}
        ignored=[work_to(r['polygone'],size,view).astype(np.float32) for r in annotation['regions_ignorees'] if 'entree' in r['taches']]
        refs[k]=dict(spec=spec,record=record,annotation=annotation,mask=mask,size=size,view=view,grid=grid,
                     entries=entries,axes=axes,ids=ids,ignored=ignored,empty=False,
                     exhaustive=annotation['historique_R17_complet'] and len(entries)==len(record['instances']))
    return refs


def inputs(worktrees,refs):
    return {k:tensors(refs[k],worktrees) for k in PHOTOS}


def describe_inputs(data,refs):
    result={}
    for k,(rgb,target) in data.items():
        spec=refs[k]['spec']
        result[k]=dict(photo=spec,grille=refs[k]['grid'].tolist(),repere_704=refs[k]['view'].tolist(),
            forme_RGB=list(rgb.shape),RGB_f32_sha256=sha256(rgb.contiguous().numpy().astype('<f4').tobytes()).hexdigest(),
            identites=target['ids'],entrees_connues=list(refs[k]['entries']),
            cibles_tensorielles=tensor_state({n:v for n,v in target.items() if n!='ids'})['sha256'])
    return result


def weights(worktrees):
    inventory=read('r22/inventaire-local.json')['fichiers']
    result={}
    for arm in ARMS:
        result[arm]={}
        for step in STEPS:
            end=f'/{SEED}/{step}/poids.pt'
            records=[r for r in inventory if r['emplacement'].endswith(end) and
                     ('/comparaison/temoin/' if arm=='temoin' else '/r22/dice/') in r['emplacement']]
            if len(records)!=1:
                raise ValueError((arm,step))
            record=records[0]
            relative=Path(record['emplacement'].split('/build/worktrees/')[1])
            path=worktrees/relative
            c=read(archive(arm)/str(step)/'controle.json')
            if fingerprint(path)!=record['sha256'] or record['sha256']!=c['poids_sha256'] or path.stat().st_size!=record['octets']:
                raise ValueError(str(path))
            result[arm][str(step)]=dict(fichier=str(relative),octets=record['octets'],sha256=record['sha256'],
                                       etat_tensoriel=c['sha256_etat'],nom=record['nom'],role=record['role'])
    return result
