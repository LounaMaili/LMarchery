"""Ventiler les lectures gelées et les masses R16 sans estimer de modèle."""
import argparse
from collections import Counter,defaultdict
from fractions import Fraction
from pathlib import Path

from r14.mesurer import read,save


def summary(pairs):
    counts=Counter()
    identities=set()
    mass=Fraction(0)
    mass_status=defaultdict(Fraction)
    for c,r in pairs:
        f=c['filiation'][0]
        counts['geometries']+=1
        counts[r['bilan_geometrie']]+=1
        counts['matiere_tube']+=r['matiere']=='tube_confirme'
        counts['matiere_indecidable']+=r['matiere']=='indecidable'
        counts['fragments_utilisables']+=r['usage_visible']!='aucun_fragment_retenu'
        counts['portions_seules_delimitees']+=r['usage_visible']=='portion_delimitee'
        counts['fragments_corrects_incomplets']+=r['etendue']=='fragment_correct_incomplet'
        counts['traces_partie_visible']+=r['etendue']=='suit_partie_visible'
        counts['exclusions_maintenues']+=r['maintien_exclusion_R16']
        counts['corrections_annotation_proposees']+=bool(r['correction'])
        counts['bouts_pertinents']+=r['bout']=='pertinent_sur_jonction'
        counts['entree_inconnue']+=r['bout']=='entree_inconnue'
        counts['bout_sans_relation_etablie']+=r['bout']=='sans_relation_etablie'
        counts['bouts_dans_chiffre']+=r['bout_sur_imprime']
        for a in r['associations']:
            identities.add(a['instance'])
            counts['liens_'+a['lecture']]+=1
        if f['poids_train_R16'] is not None:
            w=Fraction(f['poids_train_R16']).limit_denominator(10000)
            mass+=w
            mass_status[r['bilan_geometrie']]+=w
    for k in ('confirmee','erreur_demontree','indecidable','liens_correcte','liens_incorrecte','liens_ambigue'):
        counts.setdefault(k,0)
    return dict(**counts,instances_distinctes=len(identities),masse_positive_R16=float(mass),
                masse_positive_exacte=str(mass),masse_positive_par_bilan={k:float(v) for k,v in mass_status.items()})


def render(out):
    c=read('r17/cas-geles.json')['cas']
    rs=read('r17/lectures.json')['lectures']
    if [r['cas'] for r in rs]!=[v['cas'] for v in c]:
        raise ValueError('Cas incomplets ou hors gel')
    pairs=list(zip(c,rs,strict=True))
    strata={}
    for name in ('ensemble','provenance','photo'):
        values=sorted({r[name] for r in rs})
        strata['par_'+name]={s:summary([(c,r) for c,r in pairs if r[name]==s]) for s in values}
    totals=summary(pairs)
    # Le sous-ensemble d’un groupe peut aussi contenir des cas exclus ou développement.
    for key,st in strata.items():
        prop=key.removeprefix('par_')
        for s,n in st.items():
            n['positifs_bruts_R16']=sum(r[prop]==s and r['ensemble']=='positif_fit' for r in rs)
            n['fraction_des_positifs_bruts_R16']=str(Fraction(n['positifs_bruts_R16'],12))
            n['fraction_de_la_masse_positive_R16']=str(Fraction(n['masse_positive_exacte'])/Fraction(totals['masse_positive_exacte']))
    out.mkdir(parents=True,exist_ok=True)
    save(out/'bilan.json',dict(total=totals,**strata))
    rows=['# Correspondances relues par acquisition et provenance','',
          'Le bilan attribue une décision à chaque segment. Les liens vers deux identités possibles restent distincts dans le JSON. Un segment entièrement dans une région ignorée peut être reconnu visuellement tout en restant exclu de la supervision. La masse positive est celle du fit R16. Les propositions documentaires de correction portent sur les annotations initiales.',
          '', '| Acquisition | Provenance | Cas | Confirmés | Erreurs d’association | Indécidables | Fragments utilisables | Exclusions maintenues | Corrections d’annotation | Positifs bruts | Masse positive |',
          '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for name,s in strata['par_photo'].items():
        p=next(r['provenance'] for r in rs if r['photo']==name)
        vals=[name,p]+[s[k] for k in ('geometries','confirmee','erreur_demontree','indecidable','fragments_utilisables',
                     'exclusions_maintenues','corrections_annotation_proposees','positifs_bruts_R16','masse_positive_exacte')]
        rows.append('| '+' | '.join(map(str,vals))+' |')
    rows+=['','Crigges regroupe deux acquisitions. Les autres provenances comptent une acquisition chacune. '
           '[Totaux par provenance, liens par identité et masses exactes](bilan.json)',
           '', '## Lectures individuelles','',
           '| Cas | Instance(s) | Association | Portion exploitable | Relation du bout | Observation |','|---|---|---|---|---|---|']
    for case,r in pairs:
        name=r['cas']
        row=[f'[{name}](figures/{name}.jpg)',', '.join(a['instance'] for a in r['associations']),
             r['bilan_geometrie'].replace('_',' '),r['usage_visible'].replace('_',' '),r['bout'].replace('_',' '),r['observation']]
        rows.append('| '+' | '.join(row)+' |')
    (out/'tableaux.md').write_text('\n'.join(rows)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path)
    render(p.parse_args().out)
