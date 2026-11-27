"""Lecture unique des deux checkpoints R27. Le compteur précède chaque photo."""
import argparse
import os
import random
import subprocess
from pathlib import Path
from unittest.mock import patch

import numpy as np
import torch

from r27 import modele
from r27.essai import configure
from r29.commun import (ROOT, LOCAL, SEEDS, HISTORICAL, array_hash, checked,
                        digest, identical, read, save)
from r29.selection import explain


def inventory(capsules):
    config = read(ROOT/'configuration.json')
    for path, expected in config['sources'].items():
        if digest(path) != expected:
            raise ValueError('Source figée divergente : '+path)
    train = read('r26/train.json')['photos']
    prepared = read('r27/entrees.json')['photos']
    index = read('r27/sorties/index.json')['lectures']
    assert list(train) == config['photos'] and len(train)==9 and sum(len(v['instances']) for v in train.values())==31
    images = {}
    for key,spec in train.items():
        rec = prepared[key]
        image = np.load(checked(capsules/rec['fichier'],rec),allow_pickle=False)
        assert image.dtype == np.uint8 and image.shape == tuple(spec['entree_modele_xy'][::-1])+(3,)
        assert array_hash(image) == rec['sha256_pixels']
        images[key] = image
    checkpoints = {s:checked(capsules/config['checkpoints'][str(s)]['fichier'],config['checkpoints'][str(s)]) for s in SEEDS}
    archives = {(s,k):checked(Path('r27')/index[str(s)]['768'][k]['fichier'],index[str(s)]['768'][k])
                for s in SEEDS for k in train}
    return train,images,checkpoints,archives


def instrumented(model, image, native_xy):
    """Observer la fonction autonome existante sans répéter ses opérations de réseau."""
    trace = {}
    calls = []
    original_proposals,original_pool,original_suppress = modele.proposals,modele.pool,modele.suppress

    def record(name, tensor):
        if name in trace:
            raise RuntimeError('Capture répétée : '+name)
        trace[name] = tensor.detach().cpu().numpy().copy()

    def proposals(*args, **kwargs):
        result = original_proposals(*args,**kwargs)
        record('rpn',result[1][0])
        calls.append('rpn')
        return result

    def pool(head, features, boxes, mask=False):
        result = original_pool(head,features,boxes,mask=mask)
        if mask:
            calls.append('masque')
            record('entree_masque',boxes)
        else:
            calls.append('roi')
            record('roi_logits',result[0])
            record('roi_deltas',result[1])
        return result

    def suppress(boxes,scores,xy,threshold,pre_limit=None,positive=False):
        result = original_suppress(boxes,scores,xy,threshold,pre_limit,positive)
        if positive:
            assert threshold==.5 and pre_limit is None
            calls.append('selection')
            record('regression',boxes)
            record('score',scores)
            for name,value in zip(('survivantes_boites','survivantes_scores','nms'),result):
                record(name,value)
        return result

    with patch.object(modele,'proposals',proposals),patch.object(modele,'pool',pool),patch.object(modele,'suppress',suppress):
        history = modele.autonomous(model,image,native_xy)
    assert calls==['rpn','roi','selection','masque']
    return history,trace,calls


def compare(history, path):
    with np.load(path,allow_pickle=False) as reference:
        assert set(history)==set(reference.files)==set(HISTORICAL)
        return {k:dict(dtype=str(history[k].dtype),dimensions=list(history[k].shape),
                       sha256_instrumente=array_hash(history[k]),sha256_R27=array_hash(reference[k]),
                       identique=identical(history[k],reference[k])) for k in HISTORICAL}


def model_for(checkpoint, seed):
    state = torch.load(checkpoint,map_location='cpu')
    assert state['graine']==seed and state['pas']==768
    model = modele.architecture(1)
    model.load_state_dict(state['modele'],strict=True)
    model.eval()
    tensors = read('r27/tenseurs.json')
    for name,buffer in model.named_buffers():
        if name not in state['modele']:
            assert modele.tensor_hash(buffer)==tensors[name]['sha256']
    torch.set_rng_state(state['rng_torch'])
    np.random.set_state(state['rng_numpy'])
    random.setstate(state['rng_python'])
    return model


def charge(path, number, seed, photo):
    # Le marqueur persiste même si le processus ou la photo suivante échoue.
    with path.open('x') as f:
        f.write(f'{number} {seed} 768 {photo}\n')
        f.flush()
        os.fsync(f.fileno())


def run(capsules):
    runtime = configure()
    commit = subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    branch = subprocess.check_output(['git','ls-remote','origin','refs/heads/codex/vision-lab-r29'],text=True).split()
    assert branch and branch[0]==commit, 'Le gel doit être publié'
    assert not subprocess.check_output(['git','status','--porcelain','--','r27','r28','r29','r29.md'])
    train,images,checkpoints,archives = inventory(capsules)
    LOCAL.mkdir(exist_ok=False,parents=True)
    save(LOCAL/'lancement.json',dict(gel=commit,environnement=runtime,budget=18,graines=list(SEEDS),pas=768))
    results = []
    for seed in SEEDS:
        model = model_for(checkpoints[seed],seed)
        for key,spec in train.items():
            serial = len(results)+1
            assert serial<=18
            name = f'{seed}-{key}'
            charge(LOCAL/(name+'.consomme'),serial,seed,key)
            history,trace,calls = instrumented(model,images[key],spec['dimensions'])
            # En cas de désaccord ces deux fichiers gardent le passage authentique.
            np.savez_compressed(LOCAL/(name+'-historique.npz'),**history)
            np.savez_compressed(LOCAL/(name+'-observations.npz'),**trace)
            fields = compare(history,archives[seed,key])
            audit = dict(numero=serial,graine=seed,pas=768,photo=key,appels=calls,
                         photo_entiere=1,origine_R27=str(archives[seed,key]),champs=fields)
            save(LOCAL/(name+'-parite.json'),audit)
            if not all(v['identique'] for v in fields.values()):
                raise RuntimeError('Parité historique divergente : '+name)
            xy = spec['entree_modele_xy']
            stages = explain(trace['regression'],trace['score'],xy,trace['nms'])
            keep = trace['nms']
            assert identical(stages['borne'][keep],trace['survivantes_boites'])
            assert identical(trace['score'][keep],trace['survivantes_scores'])
            assert identical(trace['entree_masque'],trace['survivantes_boites'][:12])
            assert identical(keep[:12],history['propositions'])
            scale = (torch.tensor(list(spec['dimensions'])*2)/torch.tensor(list(xy)*2)).numpy()
            assert identical(trace['rpn']*scale,history['rpn_boites'])
            assert identical(trace['entree_masque']*scale,history['boites'])
            np.savez_compressed(LOCAL/(name+'-etages.npz'),**stages,echelle=scale)
            results.append(audit)
            print(f'{serial}/18  {name}  huit champs identiques, sélection reproduite',flush=True)
        del model
    save(LOCAL/'fin.json',dict(gel=commit,passages=len(results),etats=[r['photo'] for r in results],
                              tetes_masque_finales=len(results),tetes_masque_intermediaires=0))
    assert len(results)==len(list(LOCAL.glob('*.consomme')))==18


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--conservation',type=Path,default=Path('../../vision-lab-r27/vision-lab'))
    run(parser.parse_args().conservation)
