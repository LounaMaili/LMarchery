"""Règle prospective de capacité des masques. Ne charge ni modèle ni image."""
from decimal import Decimal


def capacity(finals, controls, sources, protected, configuration):
    rule=configuration['decision']
    reports={}
    for seed in configuration['graines']:
        key=str(seed)
        end,mid=finals[key],controls[key]
        a,b=set(end['separes']),set(mid['separes'])
        if (not a.union(b)<=sources.keys() or len(a)!=len(end['separes']) or len(b)!=len(mid['separes'])
                or not all(0<=r['dice_median']<=1 for r in (end,mid))
                or min(end['sorties_fusionnees'],end['identites_dedoublees'])<0):
            raise ValueError('Bilan du candidat invalide')
        old=set(protected[key]['identites'])
        lost=sorted(old-a)
        extra=sorted(n for n in a-old if sources[n]!='Crigges')
        origins=sorted({sources[n] for n in extra})
        stable=rule['stabilite_512_768']
        jaccard=len(a&b)/len(a|b) if a|b else 1.
        delta=len(a)-len(b)
        dice_delta=Decimal(str(end['dice_median']))-Decimal(str(mid['dice_median']))
        flat=(abs(delta)<=stable['delta_effectif_separes_max'] and jaccard>=stable['jaccard_identites_min']
              and abs(dice_delta)<=Decimal(str(stable['delta_dice_median_max'])))
        passed=(not lost and len(extra)>=rule['nouveaux_separes_hors_Crigges'] and
                len(origins)>=rule['provenances_hors_Crigges'] and flat and
                end['sorties_fusionnees']<=protected[key]['maximum_fusions'] and
                end['identites_dedoublees']<=protected[key]['maximum_dedoublements'])
        reports[key]=dict(perdus=lost,nouveaux_hors_Crigges=extra,provenances=origins,
                          delta_separes=delta,jaccard=jaccard,delta_dice_median=float(dice_delta),stable=flat,conforme=passed)
    ok=(all(not r['perdus'] for r in reports.values()) and
        sum(r['conforme'] for r in reports.values())>=rule['graines_conformes_min'])
    return dict(GO_capacite=ok,graines=reports)
