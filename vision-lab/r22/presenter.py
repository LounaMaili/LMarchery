"""Régénérer les tables et figures à partir des mesures publiques."""
import argparse
from pathlib import Path

from r19.donnees import SEEDS, read, save
from r19.tracer import draw, label, node, page, panel, save_svg
from r20.tableaux import fmt, slot, table
from r21.plan import STEPS
from r22.mesures import ARMS

COLORS = {'temoin':'#137378','dice':'#a64924'}
LABELS = {'temoin':'Témoin R21','dice':'Dice R22'}
FIELDS = [('masque_90_un_a_un','Masques ≥ 90 %'),('axes_apparies','Axes'),
          ('contacts_couverts','Entrées'),('triplets','Triplets'),('champs_un_a_un','Champs disponibles'),
          ('inconnue_conjointe','Entrée inconnue'),('representees_p05','Présences appariées'),
          ('supplementaires_p05','Sorties supplémentaires'),('fusions','Fusions'),('dedoublements','Dédoublements')]
ID_FIELDS = [('masque_90_q','Masque ≥ 90 %'),('axe_q','Axe'),('entree_q','Entrée'),('triplet_q','Triplet'),
             ('conjoint_q','Champs disponibles'),('presence_q','Présence ≥ 0,5')]


def write_table(out, title, description, columns, rows):
    out.write_text(f'# {title}\n\n{description}\n\n'+table(columns,rows)+'\n')


def tables(out):
    grouped=read(out/'mesures/groupes.json')
    changes=read(out/'mesures/contrastes.json')
    rows,ids,moves,declines=[],[],[],[]
    for seed in map(str,SEEDS):
        for step in map(str,STEPS):
            for arm in ARMS:
                for group,obj in grouped[arm][seed][step].items():
                    b=obj['effectifs']
                    rows.append([seed,step,LABELS[arm],group,*[b[k] for k,_ in FIELDS],
                                 *[b[k] for k in ('VP','FP','FN')],fmt(obj['quantiles_masque_R19']['fraction'][2])])
                data=read(out/f'identites/{arm}-{seed}-{step}.json')
                for k,photo in data.items():
                    for name,i in photo['identites'].items():
                        mask=i['masque_R19']
                        ids.append([seed,step,LABELS[arm],photo['provenance'],k,name,
                            'connue' if 'entree' in i['champs'] else 'inconnue',
                            *[slot(i[f]) for f,_ in ID_FIELDS],slot(mask['q']),fmt(mask['fraction']),
                            mask['VP'],mask['FP'],mask['FN']])
            for f,title in ID_FIELDS:
                delta=changes[seed][step]['champs'][f]
                moves.append([seed,step,title,', '.join(delta['gagnees']) or '—',
                              ', '.join(delta['perdues']) or '—',len(delta['conservees'])])
            for group,delta in changes[seed][step]['variations_par_groupe'].items():
                lower_bad = [*FIELDS[:7],('inconnue_masque_90','Masques des entrées inconnues'),
                             ('inconnue_axe','Axes des entrées inconnues'),('inconnue_p05','Présences des entrées inconnues'),
                             ('contacts_au_seuil','Entrées au seuil R19'),('triplets_au_seuil','Triplets au seuil R19'),
                             ('VP','Pixels retrouvés')]
                higher_bad = [*FIELDS[7:],('FN','Pixels manqués'),('FP','Pixels ajoutés'),
                              ('faux_etablis_au_seuil','Faux établis au seuil'),('non_resolus_au_seuil','Non résolus au seuil'),
                              ('faux_avant_seuil','Faux avant seuil'),('non_resolus_avant_seuil','Non résolus avant seuil')]
                lost=[f'{title} {delta[k]:+}' for k,title in lower_bad if delta[k]<0]
                lost += [f'{title} {delta[k]:+}' for k,title in higher_bad if delta[k]>0]
                if lost:
                    declines.append([seed,step,group,', '.join(lost)])
    write_table(out/'tableaux.md','Résultats des états appariés',
        'Chaque ligne porte sur une graine, un état et un ensemble de photos. Les deux bras utilisent exactement les mêmes références. VP, FP et FN sont les sommes des attributions de masque R19.',
        ['Graine','Pas','Bras','Périmètre',*[t for _,t in FIELDS],'VP','FP','FN','Fraction médiane du plafond'],rows)
    write_table(out/'identites.md','Résultats par instance',
        'Un numéro q identifie une sortie locale à la photo, au bras et à l’état. Le tiret indique une absence d’appariement. La colonne Entrée connue/inconnue précise la validité. Les pixels et la fraction du plafond concernent le masque attribué par R19.',
        ['Graine','Pas','Bras','Provenance','Photo','Identité','Entrée de référence',*[t for _,t in ID_FIELDS],
         'q masque R19','Fraction','VP','FP','FN'],ids)
    write_table(out/'transitions.md','Identités gagnées et perdues',
        'Différences ensemblistes Dice moins témoin à exposition identique. Les identités conservées sont également nommées dans le JSON des contrastes.',
        ['Graine','Pas','Mesure','Gagnées','Perdues','Conservées'],moves)
    write_table(out/'reculs.md','Reculs et hausses des erreurs observées',
        'Différences appariées pour chaque groupe et photo, y compris au pas intermédiaire. Les hausses des non-résolus signalent une incertitude de lecture. Les échanges d’identités à effectif constant figurent dans les transitions.',
        ['Graine','Pas','Périmètre','Variation Dice moins témoin'],declines)


def figures(out):
    folder=out/'figures'
    folder.mkdir(exist_ok=True,parents=True)
    grouped=read(out/'mesures/groupes.json')
    data={'distribution':{},'pixels':{},'provenances':{}}
    for s in map(str,SEEDS):
        data['distribution'][s]={}
        data['pixels'][s]={}
        for arm in ARMS:
            final=read(out/f'identites/{arm}-{s}-768.json')
            data['distribution'][s][arm]=sorted(i['masque_R19']['fraction'] for r in final.values() for i in r['identites'].values())
            data['pixels'][s][arm]={t:{k:grouped[arm][s][t]['toutes']['effectifs'][k] for k in ('VP','FP','FN')}
                                    for t in map(str,STEPS)}
    root=page(1040,355,'Qualité des 31 masques attribués au pas 768',
              'Pour chaque fraction du plafond individuel, la courbe compte les identités dont le masque atteint cette fraction.')
    for j,s in enumerate(map(str,SEEDS)):
        fx,fy=panel(root,53+342*j,132,274,137,s,1,31,[0,.25,.5,.75,.9,1],[0,10,20,31])
        node(root,'line',x1=fx(.9),x2=fx(.9),y1=fy(0),y2=fy(31),stroke='#797d80',stroke_dasharray='3 3')
        for arm in ARMS:
            vals=data['distribution'][s][arm]
            pts=[(fx(0),fy(len(vals)))]
            for v in sorted(set(vals)):
                pts.extend([(fx(v),fy(sum(x>=v for x in vals))),(fx(v),fy(sum(x>v for x in vals)))])
            draw(root,pts,COLORS[arm],2, '6 3' if arm=='temoin' else None)
    for j,arm in enumerate(ARMS):
        x=54+170*j
        draw(root,[(x,320),(x+24,320)],COLORS[arm],3,'6 3' if arm=='temoin' else None)
        label(root,x+32,325,LABELS[arm])
    label(root,470,325,'Trait vertical à 0,90. Attribution un à un de Dice total maximal.',12)
    save_svg(root,folder/'distribution.svg')
    root=page(1020,460,'Pixels natifs sur le domaine évaluable',
              'Sommes des 31 attributions de masque R19. Dans chaque paire, le témoin est à gauche et le candidat Dice à droite.')
    for j,key in enumerate(('VP','FP','FN')):
        x=45+331*j
        label(root,x,110,{'VP':'Référence retrouvée','FP':'Pixels ajoutés à tort','FN':'Référence manquée'}[key],15,'bold')
        for t,step in enumerate(map(str,STEPS)):
            label(root,x,150+133*t,'Pas '+step,13,'bold')
            for i,s in enumerate(map(str,SEEDS)):
                y=174+133*t+27*i
                label(root,x,y,s,12)
                for a,arm in enumerate(ARMS):
                    v=data['pixels'][s][arm][step][key]
                    node(root,'rect',x=x+49+100*a,y=y-14,width=93,height=20,rx=2,fill=('#d7e9e6','#fae2d5')[a])
                    label(root,x+95+100*a,y,f'{v:,}'.replace(',',' '),13,anchor='middle')
    save_svg(root,folder/'pixels.svg')
    sources=[k for k in grouped['temoin']['9301']['768'] if k.startswith('source/')]
    root=page(960,459,'Masques, triplets et entrées par provenance',
              'Variation au pas 768. Chaque cellule donne le nombre d’identités appariées du candidat moins celui du témoin.')
    for j,(metric,title) in enumerate([FIELDS[0],FIELDS[3],FIELDS[2]]):
        x=263+226*j
        label(root,x+70,101,title,15,'bold','middle')
        for a,s in enumerate(map(str,SEEDS)):
            label(root,x+70*a,126,s,12,anchor='middle')
        for i,g in enumerate(sources):
            y=158+34*i
            if not j:
                label(root,30,y,g[7:],13)
            for a,s in enumerate(map(str,SEEDS)):
                delta=grouped['dice'][s]['768'][g]['effectifs'][metric]-grouped['temoin'][s]['768'][g]['effectifs'][metric]
                data['provenances'].setdefault(g,{}).setdefault(metric,{})[s]=delta
                node(root,'rect',x=x-25+70*a,y=y-17,width=50,height=24,rx=2,
                     fill='#c8e6df' if delta>0 else '#fae0ce' if delta<0 else '#e6e9e7')
                label(root,x+70*a,y,f'{delta:+}' if delta else '0',13,'bold','middle')
    save_svg(root,folder/'provenances.svg')
    save(folder/'donnees.json',data)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path,nargs='?',default=Path('r22'))
    dest=p.parse_args().out
    tables(dest)
    figures(dest)
