"""Tables auditables des mesures par acquisition, provenance et flèche de développement."""
import argparse
from pathlib import Path

from r19.donnees import ROOT, SEEDS, read


def rows_to_md(headers, rows):
    return ['| '+' | '.join(headers)+' |','|'+'|'.join('---' for _ in headers)+'|',
            *['| '+' | '.join(str(c) for c in row)+' |' for row in rows]]


def tables(out):
    systems = ['B',*map(str,SEEDS)]
    reports = {s:read(out/'mesures'/(s+'.json')) for s in systems}
    a = ['# Développement exploré R19','',
         'Les contacts au seuil sélectionné, les contacts couverts par la liste entière et les axes sur toutes les sorties ont des dénominateurs distincts. Chaque provenance contribue une photo. Les trois graines portent sur les mêmes identités. Les décomptes de fusions et de dédoublements décrivent les recouvrements à 25 % de la référence. [Règles figées](protocole.md) · [Données par système](mesures/)','']
    rows = []
    for s,r in reports.items():
        v=r['par_strate']['ensemble']
        rows.append((s,format(r['seuil'],'.12g'),f"{v['apparies']}/{v['connus']}",v['propositions'],v['faux_etablis'],
            v['non_resolus'],v['silencieuses'],v['vides_declenches'],f"{v['contacts_couverts']}/{v['total_candidats']}",
            f"{v['axes_apparies']}/{v['axes_reference']}",f"{v['sorties_avec_axe']} / {v['fragments_sortis']}"))
    a+=rows_to_md(['Système','Seuil','Contacts / 16','Proposées','Faux','Non résolus','Photos silencieuses','Vides déclenchés',
                   'Contacts couverts / candidats','Axes R18 appariés','Sorties avec axe / fragments'],rows)
    a+=['','## Conditions de prise de vue','',
        'Les conditions peuvent se recouvrir. Les colonnes « Couv. » et « Candidats » comptent toutes les sorties avant sélection du seuil.','']
    rows=[]
    for g in sorted(k for k in reports['B']['par_strate'] if not k.startswith('source/') and k!='ensemble'):
        for s,r in reports.items():
            v=r['par_strate'][g]
            rows.append((g,s,v['photos'],f"{v['apparies']}/{v['connus']}",v['non_appariees'],v['faux_etablis'],v['non_resolus'],
                         v['vides_declenches'],v['silencieuses'],v['contacts_couverts'],v['total_candidats'],f"{v['axes_apparies']}/{v['axes_reference']}"))
    a+=rows_to_md(['Condition','Système','Photos','Contacts','Non app.','Faux','Non rés.','Vides décl.','Silencieuses','Couv.','Candidats','Axes'],rows)
    a+=['','## Acquisitions et provenances','']
    rows=[]
    for k,base in reports['B']['par_photo'].items():
        for s,r in reports.items():
            v=r['par_photo'][k];m=v['selection'];c=v['couverture'];ax=v['axes']
            rows.append((k,v['provenance'],s,f"{m['apparies']}/{m['connus']}",m['non_appariees'],m['faux_etablis'],m['non_resolus'],
                         m['silencieuses'],f"{c['apparies']}/{c['propositions']}",f"{c['faux_etablis']} / {c['non_resolus']}",
                         f"{ax['apparies']}/{ax['references']}",f"{ax['sorties_avec_axe']} / {ax['fragments_sortis']}"))
    a+=rows_to_md(['Acquisition','Provenance','Système','Contacts','Non app.','Faux','Non rés.','Silence','Couv. / candidats',
                   'Tous les faux / non rés.','Axes','Sorties avec axe / fragments'],rows)
    a+=['','## Identité des masques et entrées','']
    rows=[]
    for s in systems[1:]:
        v=reports[s]['par_strate']['ensemble'];assoc=v['entrees_selection']
        rows.append((s,v['masques_ref'],v['masques_trouves'],v['masques_manques'],f"{v['dice_toutes_identites']:.4f}",
                     f"{v['dice_retrouvees']:.4f}" if v['dice_retrouvees'] is not None else '—',v['sorties_fusion'],
                     v['identites_dedoublees'],assoc['correcte'],assoc['autre_identite'],assoc['masque_non_attribue'],assoc['reference_sans_masque']))
    a+=rows_to_md(['Graine','Masques R18','Intersection positive','Manqués','Dice toutes','Dice retrouvées','Sorties fusion',
                   'Références dédoublées','Entrée et fût corrects','Autre identité','Masque non attribué','Référence sans masque'],rows)
    a+=['','## Coordonnées distinctes émises par les requêtes','',
        'Décompte descriptif après le pilote. Deux entrées coïncident si leurs deux coordonnées décodées sont exactement égales dans la même photo. Les douze propositions originales par photo continuent de compter séparément dans toutes les mesures.','']
    predictions={s:read(out/'predictions'/s/'predictions.json') for s in systems[1:]}
    unique={s:{k:len({tuple(q['xy']) for q in v['sorties']}) for k,v in d.items()} for s,d in predictions.items()}
    a+=rows_to_md(['Acquisition','9301','9302','9303'],
                  [(k,*[unique[s][k] for s in systems[1:]]) for k in reports['B']['par_photo']]+
                  [('Total sur 132 sorties',*[sum(unique[s].values()) for s in systems[1:]])])
    a+=['','[Revue flèche par flèche](identites.md) · [Planches et crédits](figures/README.md) · [Gains et pertes par provenance](decision.json)']
    (out/'tableaux.md').write_text('\n'.join(a)+'\n')
    lines=['# Appariements par flèche','',
           'Les indices de requête commencent à zéro. Une absence d’entrée admise est indiquée par « hors cible ». « Manquée » porte uniquement sur une entrée admise. Le Dice utilise tous les pixels évaluables. Les fusions et dédoublements à recouvrement de 25 % figurent dans la dernière colonne.','']
    for k,base in reports['B']['par_photo'].items():
        ann=read(Path('r18/annotations')/(k+'.json'))
        if not ann['instances']:
            continue
        lines+=['## '+base['provenance']+' · '+k,'']
        rows=[]
        for i in ann['instances']:
            ident=i['instance']
            for s in systems:
                p=reports[s]['par_photo'][k]
                selected=next((m for m in p['selection']['paires'] if m['instance']==ident),None)
                covered=next((m for m in p['couverture']['paires'] if m['instance']==ident),None)
                axis=next((m for m in p['axes']['paires'] if m['instance']==ident),None)
                adm=selected or ident in p['selection']['sans_proposition_appariee']
                mask=p.get('masques',{})
                dice=next((m for m in mask.get('par_identite',[]) if m['instance']==ident),None)
                flags=[]
                if ident in mask.get('dedoublements',{}):
                    flags.append('dédoublée '+','.join(map(str,mask['dedoublements'][ident])))
                merges=[j for j,v in mask.get('fusions',{}).items() if ident in v]
                if merges:flags.append('fusion '+','.join(merges))
                rows.append((ident,s,str(selected['sortie']) if selected else 'manquée' if adm else 'hors cible',
                             covered['sortie'] if covered else '—',axis['sortie'] if axis else '—',
                             f"{dice['dice']:.4f} (q{dice['sortie']})" if dice and dice['sortie'] is not None else '0' if dice else '—',
                             ' · '.join(flags) or '—'))
        lines+=rows_to_md(['Flèche','Système','Entrée au seuil','Entrée tous rangs','Axe apparié','Dice (requête)','Erreurs de recouvrement'],rows)+['']
    (out/'identites.md').write_text('\n'.join(lines).rstrip()+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    tables(parser.parse_args().out)
