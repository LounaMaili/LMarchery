"""Modification isolée de la composante présence sur les vues au registre exhaustif."""
import torch
from torch.nn import functional as F

from r19.supervision import losses as r19_losses
from r20.instrumenter import diagnose


def losses(fields,presence,target,exhaustive):
    total,parts,audit=r19_losses(fields,presence,target)
    decoded=diagnose(fields,presence,target,parts,audit)
    positives=[r['q'] for r in decoded['par_identite']]
    if exhaustive:
        negative=sorted(set(range(len(presence)))-set(positives))
        terms=[]
        if positives:terms.append(F.softplus(-presence[positives]).mean())
        if negative:terms.append(F.softplus(presence[negative]).mean())
        parts['presence']=sum(terms)/len(terms) if terms else total*0
        total=parts['masque']+parts['axe']+parts['entree']+.25*parts['presence']
        audit['presences_negatives']=len(negative)
        decoded['negatifs']=negative
        decoded['pertes_presence_negatives']={str(q):float(F.softplus(presence[q].detach())) for q in negative}
        decoded['pertes']['presence']=parts['presence'].item()
    return total,parts,audit,decoded
