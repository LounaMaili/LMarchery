"""Consolider les affectations avant mise à jour et les évaluations après contrôle."""
import argparse
import json
from pathlib import Path

from r19.donnees import save
from r23.commun import ARMS, FOCUS, GAGNON, LYNCH, ROOT, STEPS, archive, measures


def replay():
    logs={arm:[json.loads(line) for line in (archive(arm)/'progression.jsonl').read_text().splitlines()] for arm in ARMS}
    reports={(arm,t):measures(arm,t) for arm in ARMS for t in STEPS}
    out={}
    for photo,nums in FOCUS.items():
        for num in nums:
            name=f'{photo}:{num}'
            info={}
            for arm in ARMS:
                sequence=[dict(pas=r['pas'],q_avant=r['positives'][name]) for r in logs[arm] if r['photo']==photo]
                changes=[i for i in range(1,len(sequence)) if sequence[i-1]['q_avant']!=sequence[i]['q_avant']]
                start=changes[-1] if changes else 0
                controls={}
                for step in STEPS:
                    r=reports[arm,step][photo]
                    cells=r['paires_par_identite'][name]
                    i=r['identites'][name]
                    assigned=i['affectation_perte_q']
                    controls[str(step)]=dict(q_perte_apres=assigned,q_entree_appariee=i['entree_q'],
                        minimum_px=min(c['erreur_entree_px704'] for c in cells),
                        par_requete=[dict(q=c['q'],point=r['sorties'][c['q']]['xy'],distance_px=c['erreur_entree_px704'])
                                     for c in cells],q_dans_10px=[c['q'] for c in cells if c['erreur_entree_px704']<=10],
                        derniere_presentation_avant_controle=next(r for r in reversed(sequence) if r['pas']<=step))
                info[arm]=dict(presentations=len(sequence),transitions=len(sequence)-1,changements=len(changes),
                    serie_finale=dict(q=sequence[-1]['q_avant'],longueur=len(sequence)-start,debut=sequence[start]['pas'],
                                      fin=sequence[-1]['pas']),avant_mises_a_jour=sequence,controles_apres=controls)
            a,b=(info[arm]['avant_mises_a_jour'] for arm in ARMS)
            if len(a)!=96 or [r['pas'] for r in a]!=[r['pas'] for r in b]:
                raise ValueError('Plans différents')
            idx=next((i for i in range(len(a)) if a[i]!=b[i]),len(a))
            info['dernier_pas_du_prefixe_identique']=a[idx-1]['pas'] if idx else None
            info['premiere_divergence']=a[idx]['pas'] if idx<len(a) else None
            out[name]=info
    n=out[GAGNON+':11']
    l=out[LYNCH+':4']
    assert [n[a]['changements'] for a in ARMS]==[35,14]
    assert n['dice']['serie_finale']==dict(q=7,longueur=45,debut=412,fin=762)
    assert [l[a]['changements'] for a in ARMS]==[4,15]
    assert (l['dernier_pas_du_prefixe_identique'],l['premiere_divergence'])==(106,113)
    assert l['temoin']['controles_apres']['96']['q_entree_appariee'] is None
    assert l['dice']['controles_apres']['96']['q_entree_appariee'] is not None
    assert all(not n['dice']['controles_apres'][str(t)]['q_dans_10px'] for t in STEPS)
    pairs=out[GAGNON+':9']['dice']['controles_apres']['768']['par_requete']
    assert pairs[7]['point']==pairs[11]['point'] and pairs[7]['distance_px']<=10
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',nargs='?',type=Path,default=ROOT/'historique.json')
    save(p.parse_args().out,replay())
