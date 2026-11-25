"""Extraire les cartes et les composantes de coût des mêmes huit champs après parité."""
import argparse
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from r19.donnees import fingerprint, read, save
from r19.pilote import setup
from r19.supervision import weighted_terms
from r20.rejouer import compact
from r23.commun import ROOT, SEED, archive, cases, measures, tag
from r23.parite import load_case
from r23.perimetre import inputs, references
from r23.spatial import regions


def describe(ref,target):
    info={}
    for n,xy in ref['entries'].items():
        idx=target['ids'].index(n)
        contact=target['contact'][idx]
        info[n]=dict(xy_704=xy.tolist(),support=[dict(index=k,poids=contact[k].item())
                    for k in contact.nonzero().flatten().tolist()])
    return dict(grille=ref['grid'].tolist(),repere_704=ref['view'].tolist(),provenance=ref['spec']['provenance'],identites=info)


def auxiliary(fields,logits,target,spec,report,cost):
    ids=target['ids']
    own=target['own']
    known=own+target['others']+target['background']
    mask=weighted_terms(fields[:,0],[(own,True),(target['others'],False),(target['background'].expand_as(own),False)])
    axis=weighted_terms(fields[:,1],[(target['axis_on'],True),(target['axis_off'],False)])
    log_p=fields[:,2].log_softmax(1)
    ce=-(target['contact'] @ log_p.T)
    probabilities=fields[:,0].sigmoid()
    pos=F.softplus(-logits)
    numerator=[2*(probabilities*a).sum(1)+1 for a in own]
    denominators=[a.sum()+(probabilities*n).sum(1)+1 for a,n in zip(own,known,strict=True)]
    soft=torch.stack([a/b for a,b in zip(numerator,denominators,strict=True)])
    _,disks=regions(spec)
    output=[]
    for q in range(12):
        pairs={}
        for n in spec['identites']:
            i=ids.index(n)
            v=report['paires_par_identite'][n][q]
            geo=report['identites'][n]
            selected=geo['affectation_perte_q']==q
            if selected:
                a=next(a for a in cost['par_identite'] if a['instance']==n)
                if (a['q']!=q or [a[k] for k in ('masque','axe','entree','presence_positive')]!=
                        [x.item() for x in (mask[i,q],axis[i,q],ce[i,q],pos[q])]):
                    raise ValueError('Composantes différentes de la perte affectée')
                if 'dice_doux' in a and soft[i,q].item()!=a['dice_doux']:
                    raise ValueError('Dice différent de la paire affectée')
            disk=disks[n]
            mb=fields[q,0,disk]>=0
            ab=mb & (fields[q,1,disk]>=0)
            pairs[n]=dict(log_p_support=[log_p[q,b['index']].item() for b in spec['identites'][n]['support']],
                CE_historique=ce[i,q].item(),BCE_masque=mask[i,q].item(),BCE_axe=axis[i,q].item(),
                presence_positive=pos[q].item(),cout_hongrois=cost['matrice_couts_affectation'][i][q],
                dice_doux=soft[i,q].item(),dice_binaire=v['dice'],fraction_plafond=v['fraction_plafond'],
                masque_admis=v['masque_90_pourcent'],sondes_axe=v['sondes_axe'],
                disque_cellules_masque=int(mb.sum()),disque_cellules_axe_dans_masque=int(ab.sum()),
                q_perte=geo['affectation_perte_q'],q_entree_geometrique=geo['entree_q'],q_axe_geometrique=geo['axe_q'],
                q_masque_geometrique=geo['masque_q'],q_triplet=geo['conjoint_q'])
        output.append(dict(q=q,presence=report['sorties'][q]['presence'],identites=pairs))
    return output


@torch.inference_mode()
def export(src,worktrees,out):
    setup(SEED)
    parity=read(out/'parite.json')
    if not parity['parite_complete'] or parity['passages_verifies']!=8:
        raise ValueError('Parité des huit passages préalable')
    refs=references()
    data=inputs(worktrees,refs)
    registry={k:describe(r,data[k][1]) for k,r in refs.items()}
    save(out/'references.json',registry)
    ledger=read(src/'execution.json')['passages']
    for case,entry in zip(cases(),ledger,strict=True):
        arm,step,k=case
        name=tag(*case)
        if entry['sha256']!=parity['etats'][name]['champs_locaux']:
            raise ValueError('Champs différents des champs vérifiés')
        fields,presence=load_case(src,case,entry)
        g=registry[k]['grille']
        logits=fields[:,2]
        probs=logits.softmax(1)
        costs=read(archive(arm)/str(step)/'pertes.json')[k]
        row=measures(arm,step)[k]
        aux=auxiliary(fields,presence,data[k][1],registry[k],row,costs)
        folder=out/'cartes'/name
        folder.mkdir(parents=True,exist_ok=True)
        for q,a in enumerate(aux):
            path=folder/f'q{q:02}.npz'
            np.savez_compressed(path,logits=logits[q].reshape(g[1],g[0]).numpy(),p=probs[q].reshape(g[1],g[0]).numpy())
            a['fichier']=str(path.relative_to(out))
            a['octets']=path.stat().st_size
            a['sha256']=fingerprint(path)
        compact(out/'paires'/(name+'.json'),dict(bras=arm,pas=step,photo=k,requetes=aux))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture',type=Path)
    p.add_argument('worktrees',type=Path)
    p.add_argument('out',type=Path)
    a=p.parse_args()
    export(a.capture,a.worktrees,a.out)
