"""Décomposer, hors gradient, les coûts calculés par la supervision R19."""
import torch
from torch.nn import functional as F

from r19.affecter import minimum
from r19.supervision import extra_on_background, weighted_terms


@torch.no_grad()
def diagnose(fields,presence,target,parts,audit):
    count=len(target['ids'])
    known=[target[name].sum(1)>0 for name in ('own','axis_on','contact')]
    cost=[weighted_terms(fields[:,0],[(target['own'],True),(target['others'],False),(target['background'].expand(count,-1),False)]),
          weighted_terms(fields[:,1],[(target['axis_on'],True),(target['axis_off'],False)]),
          -(target['contact']@fields[:,2].log_softmax(1).T)]
    joint=sum(1.*k[:,None]*c for k,c in zip(known,cost,strict=True))
    denom=sum(k.float() for k in known).clamp_min(1)
    objective=joint/denom[:,None]+.25*F.softplus(-presence)[None,:]
    assignments=minimum(objective.numpy()).tolist()
    negatives=extra_on_background(fields,sorted(set(range(len(presence)))-set(assignments)),target['pure_bg'])
    if len(negatives)!=audit['presences_negatives']:
        raise ValueError('Désaccord sur la présence surnuméraire')
    names=('masque','axe','entree')
    rows=[]
    for i,(ident,q) in enumerate(zip(target['ids'],assignments,strict=True)):
        values={k:float(c[i,q]) if v[i] else None for k,c,v in zip(names,cost,known,strict=True)}
        rows.append(dict(instance=ident,q=q,**values,presence_positive=float(F.softplus(-presence[q])),
                         cout_affectation=float(objective[i,q])))
    for k in names:
        terms=[r[k] for r in rows if r[k] is not None]
        mean=sum(terms)/len(terms) if terms else 0.
        if abs(mean-parts[k].item())>1.e-5*max(1,abs(mean)):
            raise ValueError('Désaccord avec la perte R19 '+k)
    presence_classes=[]
    if assignments:
        presence_classes.append(F.softplus(-presence[assignments]).mean())
    if negatives:
        presence_classes.append(F.softplus(presence[negatives]).mean())
    actual=sum(presence_classes)/len(presence_classes) if presence_classes else presence.sum()*0
    torch.testing.assert_close(actual,parts['presence'])
    return dict(par_identite=rows,negatifs=negatives,logits_presence=presence.tolist(),
                pertes_presence_negatives={str(q):float(F.softplus(presence[q])) for q in negatives},
                matrice_couts_affectation=objective.tolist(),pertes={k:v.item() for k,v in parts.items()})


def grad_groups(model):
    squared={}
    for name,param in model.named_parameters():
        if param.grad is not None:
            group=name.split('.')[0]
            squared[group]=squared.get(group,0.)+float(param.grad.detach().double().square().sum())
    return {k:v**.5 for k,v in squared.items()}
