"""Rejouer les 624 paires à partir des logits et probabilités publics, sans modèle."""
import argparse
from pathlib import Path

import numpy as np

from r19.donnees import fingerprint, read
from r20.rejouer import compact
from r23.commun import ROOT, cases, measures, tag
from r23.spatial import measure_query, regions


def replay(src,out):
    specs=read(src/'references.json')
    totals=[]
    for arm,step,k in cases():
        name=tag(arm,step,k)
        spec=specs[k]
        centers,disks=regions(spec)
        auxiliary=read(src/'paires'/(name+'.json'))
        archive=measures(arm,step)[k]
        rows=[]
        for q,query in enumerate(auxiliary['requetes']):
            f=src/query['fichier']
            if query['q']!=q or f.stat().st_size!=query['octets'] or fingerprint(f)!=query['sha256']:
                raise ValueError('Carte publique différente')
            with np.load(f,allow_pickle=False) as maps:
                z,p=maps['logits'],maps['p']
                if z.shape!=tuple(spec['grille'][::-1]) or p.shape!=z.shape or any(a.dtype!=np.float32 for a in (z,p)):
                    raise ValueError('Grille différente')
                row=measure_query(z,p,spec,query,centers,disks)
            rec=archive['sorties'][q]
            if (row['pic_decode']['p']!=rec['pic_spatial'] or row['pic_decode']['xy_704']!=rec['xy'] or
                    row['presence']!=rec['presence'] or row['pic_decode']['p']*row['presence']!=rec['score']):
                raise ValueError('Pic, présence ou score différent des archives')
            for ident,a in row['par_identite'].items():
                a['ecart_recomposition_CE']=a['perte_entree_recomposee']-a['CE_historique']
                historical=archive['paires_par_identite'][ident][q]
                if a['pic_decode_dans_disque']!=(historical['erreur_entree_px704']<=10):
                    raise ValueError('Contradiction sur le disque de tolérance')
            rows.append(row)
        compact(out/'mesures'/(name+'.json'),dict(bras=arm,pas=step,photo=k,requetes=rows))
        totals.append(dict(bras=arm,pas=step,photo=k,provenance=spec['provenance'],entrees_connues=len(disks),
            paires=len(rows)*len(disks),q=12,couverture=archive['couverture'],au_seuil=archive['au_seuil'],
            seuil_score=archive['seuil'],temoins_vides=0))
    compact(out/'mesures'/'effectifs.json',totals)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=ROOT)
    p.add_argument('--source',type=Path,default=ROOT)
    a=p.parse_args()
    replay(a.source,a.out)
