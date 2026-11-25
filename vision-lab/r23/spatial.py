"""Statistiques descriptives des cartes complètes. Aucune sélection de sortie."""
import math
import numpy as np

from r19.donnees import work_to


def strict_peaks(z,p):
    """Plus grand que tous les huit voisins existants en logit ET en probabilité."""
    h,w=z.shape
    result=np.ones((h,w),dtype=bool)
    for field in (z,p):
        padded=np.pad(field,1,constant_values=-np.inf)
        for dy in range(3):
            for dx in range(3):
                if (dy,dx)!=(1,1):
                    result &= field>padded[dy:dy+h,dx:dx+w]
    return result.ravel()


def regions(spec):
    grid=np.array(spec['grille'])
    yy,xx=np.indices(tuple(grid[::-1]))
    centers=work_to(np.stack([xx.ravel(),yy.ravel()],axis=1),grid,np.asarray(spec['repere_704']))
    disks={n:np.flatnonzero(np.linalg.norm(centers-i['xy_704'],axis=1)<=10)
           for n,i in spec['identites'].items()}
    return centers,disks


def ranks(sorted_values,value):
    size=len(sorted_values)
    return [int(1+size-np.searchsorted(sorted_values,value,side='right')),
            int(size-np.searchsorted(sorted_values,value,side='left'))]


def measure_query(z,p,spec,query,centers,disks):
    local=strict_peaks(z,p)
    z,p=z.ravel(),p.ravel()
    sorted_z,sorted_p=np.sort(z),np.sort(p)
    peak=int(p.argmax())
    max_z=float(z.max())
    secondary=local & (z<max_z) & (p<p[peak])
    width=spec['grille'][0]
    def cell(i):
        return dict(index=int(i),xy_grille=[int(i%width),int(i//width)],xy_704=centers[i].tolist(),
                    logit=float(z[i]),p=float(p[i]),rang_logit=ranks(sorted_z,z[i]),rang_p=ranks(sorted_p,p[i]),
                    maximum_local_strict=bool(local[i]))
    mass={n:float(p[indices].sum(dtype=np.float64)) for n,indices in disks.items()}
    result={}
    for name,indices in disks.items():
        weights=spec['identites'][name]['support']
        pair=query['identites'][name]
        support=[dict(**cell(b['index']),poids=b['poids'],log_p_historique=v)
                 for b,v in zip(weights,pair['log_p_support'],strict=True)]
        best=int(indices[np.argmax(z[indices])])
        modes=indices[secondary[indices]]
        competitor=max((n for n in disks if n!=name),key=lambda n:mass[n])
        result[name]=dict(support=support,
            perte_entree_recomposee=math.fsum(-b['poids']*b['log_p_historique'] for b in support),
            masse_disque=mass[name],cellules_disque=len(indices),meilleure_cellule_disque=cell(best),
            ecart_logit=max_z-float(z[best]),distance_pic_px704=float(np.linalg.norm(centers[peak]-spec['identites'][name]['xy_704'])),
            pic_decode_dans_disque=bool(peak in indices),modes_secondaires_stricts=len(modes),
            meilleur_mode_secondaire=cell(int(modes[np.argmax(z[modes])])) if len(modes) else None,
            principale_autre_entree=competitor,masse_principale_autre_entree=mass[competitor],**pair)
    union=np.unique(np.concatenate(list(disks.values())))
    summed=float(p.sum(dtype=np.float64))
    row=dict(q=query['q'],presence=query['presence'],pic_decode=cell(peak),maximum_logit=cell(int(z.argmax())),
             somme_p=summed,masse_hors_union=summed-float(p[union].sum(dtype=np.float64)),par_identite=result)
    n11,n9='wc71488242:11','wc71488242:9'
    if n11 in disks:
        row['concurrence_11_9']=dict(masse_11=mass[n11],masse_9=mass[n9],
            rapport_11_sur_9=mass[n11]/mass[n9] if mass[n9] else None,
            ecart_meilleurs_logits_11_moins_9=result[n11]['meilleure_cellule_disque']['logit']-result[n9]['meilleure_cellule_disque']['logit'],
            cellules_communes=int(np.intersect1d(disks[n11],disks[n9]).size))
    return row
