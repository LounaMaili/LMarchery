"""Préparer et identifier les seules entrées, les six poids et la capture déjà disponible."""
from pathlib import Path

from r19.donnees import fingerprint, load_image, read
from r19.modele import photograph
from r21.plan import tensor_state
from r25.commun import ARMS, REUSE, SEEDS, captures, historical

def weights(worktrees):
    inventory=read('r24/inventaire-local.json')['fichiers']
    result={}
    for arm in ARMS:
        for seed in SEEDS:
            stage='r22' if arm=='dice' else 'r24'
            name=Path(f'vision-lab-{stage}/vision-lab/artifacts/{stage}/{arm}/{seed}/768/poids.pt')
            path=worktrees/name
            row=next(x for x in inventory if x['emplacement'].endswith('/'+name.as_posix()))
            control=read(historical(arm,seed)/'controle.json')
            sha=fingerprint(path)
            if sha!=control['poids_sha256'] or sha!=row['sha256'] or path.stat().st_size!=row['octets']:
                raise ValueError('Checkpoint différent '+str(name))
            result[f'{arm}-{seed}']=dict(fichier=str(name),sha256=sha,octets=row['octets'],etat=control['sha256_etat'])
    return result


def reused(worktrees):
    row=next(r for r in read('r23/execution.json')['passages'] if (r['bras'],r['pas'],r['photo'])==('dice',768,REUSE[2]))
    name=Path('vision-lab-r23/vision-lab/artifacts/r23/capture')/row['fichier']
    path=worktrees/name
    prev=read('r23/parite.json')['etats']['dice-768-'+REUSE[2]]
    control=read(historical(*REUSE[:2])/'controle.json')
    if (not prev['parite'] or prev['poids']!=control['poids_sha256'] or row['sha256']!=prev['champs_locaux'] or
            fingerprint(path)!=row['sha256'] or path.stat().st_size!=row['octets']):
        raise ValueError('Capture R23 différente')
    return dict(fichier=str(name),sha256=row['sha256'],octets=row['octets'],champs_sha256=row['sha256_champs_f32'])


def rgb_inputs(worktrees,refs):
    parity=read('r24/comparabilite.json')['cibles']
    rgbs,observed={},{}
    for k in sorted({k for _,_,k in captures()}):
        spec=refs[k]['spec']
        if spec['partition']!='train':
            raise ValueError('Photo hors train')
        rgb=photograph(load_image(spec,worktrees))
        h=tensor_state({'rgb':rgb})['sha256']
        if h!=parity[k]['rgb']:
            raise ValueError('Transformation différente '+k)
        rgbs[k]=rgb
        observed[k]=dict(photo=k,provenance=spec['provenance'],fichier=spec['fichier_reference'],
            sha256=spec['sha256_reference'],sha256_BGR=spec['sha256_BGR_travail'],sha256_RGB=h,
            sha256_annotation=spec['sha256_annotation'],sha256_masque_R18=spec['sha256_masque'],
            grille=refs[k]['grid'].tolist(),repere_704=refs[k]['view'].tolist())
    return rgbs,observed
