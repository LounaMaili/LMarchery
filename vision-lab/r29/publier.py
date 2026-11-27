"""Générer les tables, les témoins nominatifs et les figures du diagnostic."""
import argparse
from pathlib import Path

import numpy as np

from r28.tableaux import table, write
from r29.commun import ROOT, SEEDS, STAGES, LABELS, read
from r29.figurer import figures
from r29.lecture import export, ranks


def source_tables(dest, controls):
    pars=['# Capacités par étage et provenance',
          'Une possibilité individuelle exige au moins une boîte dont la borne atteint le seuil personnel R27. Le maximum un à un impose une boîte distincte par identité d’une photo. Les chiffres par provenance somment les photos dans leurs propres repères. [Valeurs JSON](mesures/capacites.json) · [Cas individuels](identites.md)']
    for seed,group in controls.items():
        pars.append('## Graine '+seed)
        stages=group['ensemble']['etages']
        pars.append(table(['Étage','Boîtes','Arêtes suffisantes','Individuelles','Un à un','Sans possibilité','Places perdues par partage'],
            ([label,v['boites'],v['arêtes'],v['individuelles'],v['un_a_un'],v['identites']-v['individuelles'],v['deficit_partage']]
             for label,v in zip(LABELS,stages.values()))))
        rows=[]
        for name,v in group['provenances'].items():
            rows.append([name,v['photos'],v['etages']['rpn']['identites'],
                *[f"{v['etages'][k]['individuelles']} / {v['etages'][k]['un_a_un']}" for k in STAGES]])
        pars.append(table(['Provenance','Photos','Fûts',*LABELS],rows))
        pars.append('Chaque cellule de provenance donne les identités individuellement possibles puis le maximum un à un.')
        changes=group['ensemble']['changements']
        pars.append(table(['Opération','Arêtes gagnées','Arêtes perdues','Identités gagnées','Identités perdues'],
          ([name,changes[k]['aretes_gagnees'],changes[k]['aretes_perdues'],changes[k]['gains_individuels'],
            changes[k]['pertes_individuelles']] for k,name in zip(STAGES[1:],LABELS[1:]))))
        reg=group['ensemble']['regression']
        pars.append(f"Sur les mêmes ancêtres RPN, la régression augmente la borne de {reg['boites_avec_borne_amelioree']} couples boîte–identité, la diminue pour {reg['boites_avec_borne_degradee']} et la conserve pour {reg['boites_avec_borne_identique']}. Ces effectifs incluent les changements qui restent du même côté du seuil.")
    write(dest/'provenances.md',pars)


def nominal(dest, rows, directory):
    identities=['# Les 31 identités aux deux graines',
        'La valeur de chaque étage est le nombre de boîtes individuellement suffisantes pour le fût de la ligne. Le lien de chaque identité donne les 1 000 ancêtres, leurs intersections et leurs bornes avant régression, après régression et après bornage. La colonne q donne le rang attribué dans le préfixe final. Un tiret avec une possibilité finale indique une concurrence. [Capacités par provenance](provenances.md) · [Certificats de concurrence](retraits.md)']
    losses=['# Perte de la dernière possibilité et concurrence',
        'Le rang NMS part de zéro dans chaque photo. Les douze sorties occupent les rangs 0 à 11. Chaque perte individuelle ci-dessous a encore au moins une boîte suffisante après NMS. Les identités et les ancêtres j sont suivis dans le repère de leur passage. [Tous les témoins de perte et leurs scores](mesures/retraits.json) · [Toutes les possibilités par photo](mesures/README.md)']
    for seed in SEEDS:
        identities.append(f'## Graine {seed}')
        losses.append(f'## Graine {seed}')
        lost,conf=[],[]
        for tag,r in rows.items():
            if r['graine']!=seed:
                continue
            photo=r['photo']
            names=list(r['identites'])
            with np.load(ROOT/'traces'/(tag+'.npz'),allow_pickle=False) as a:
                kept=ranks(a['nms'])
                final=ranks(a['propositions'])
                score=a['score']
            identity_rows=[]
            for n in names:
                counts=[len(r['etages'][k]['par_identite'][n]['possibles_j']) for k in STAGES]
                end=r['etages']['final']['par_identite'][n]
                after_nms=r['etages']['nms']['par_identite'][n]
                best_rank=min((kept[j] for j in after_nms['possibles_j']),default=None)
                q=end['rang_attribue']
                state='attribuée' if q is not None else ('concurrence' if counts[-1] else 'sans boîte suffisante')
                identity_rows.append([f"[{n}](mesures/bornes/{directory[tag]['bornes'][n]})",*counts,
                    best_rank if best_rank is not None else '—',f'q{q}' if q is not None else '—',state])
                for e in r['identites'][n]['transitions']:
                    if e['possibilite']=='perte':
                        js=e['pertes_j']
                        w=min(js,key=kept.get) if e['avant']=='nms' else js[0]
                        lost.append([n,r['provenance'],e['avant'],e['apres'],len(js),w,kept.get(w,'—'),f"{score[w]:.8f}"])
            identities.extend([f"### {r['provenance']} · {photo}",table(['Identité','RPN','Régression','Bornage','Admission','NMS','Final',
                   'Premier rang NMS suffisant','q affecté','Résultat'],identity_rows)])
            for k in STAGES:
                for c in r['etages'][k]['concurrences']:
                    js=c['origines_temoin']
                    conf.append([r['provenance'],k,', '.join(c['identites_temoin']),', '.join(str(j) for j in js),
                        ', '.join(f'q{final[j]}' if j in final else '—' for j in js),c['deficit']])
        losses.extend([table(['Identité','Provenance','Dernier étage possible','Premier étage impossible','Boîtes perdues',
            'j le mieux classé','Rang NMS','Score RoI'],lost),
            table(['Provenance','Étage','Identités du certificat S','Tous les voisins j de S','Rangs finaux','Déficit'],conf)])
    losses.append('Un déficit compte des identités en excès sur des sorties partageables, indépendamment du départage choisi. Toutes les identités d’un certificat gardent une possibilité individuelle. Les JSON par photo donnent aussi la composante entière et l’attribution retenue.')
    write(dest/'identites.md',identities)
    write(dest/'retraits.md',losses)


def indexes(dest, rows, directory):
    lines=[]
    for tag,r in rows.items():
        ranges=directory[tag]['propositions']
        links=' · '.join(f'[{i*250}–{i*250+249}](propositions/{path})' for i,path in enumerate(ranges))
        lines.append([r['graine'],r['photo'],r['provenance'],f'[Graphe et appariements](photos/{tag}.json)',links,
            f'[I par boîte](../intersections/{tag}.json)',f'[NPZ](../traces/{tag}.npz)'])
    write(dest/'mesures'/'README.md',[
        '# Mesures et traces R29',
        '[Contrôles et budget réalisé](verification.json) · [Capacités par graine et par provenance](capacites.json) · [Toutes les pertes de dernière arête](retraits.json) · [Index des 62 matrices de bornes et des traces lisibles](index-lecture.json) · [Parité bit à bit des huit champs et registre des passages](../traces/index.json)',
        table(['Graine','Photo','Provenance','Mesures','Propositions par rang RPN','Intersections','Trace exacte'],lines),
        'Chaque page de propositions décrit jusqu’à 250 des 1 000 ancêtres j de cette photo. Les colonnes donnent les coordonnées xyxy natives et modèle, les logits, deltas et scores originaux, le rang de score, d’admission et de NMS ainsi que le q final. Le motif de retrait est exclusif. En cas de suppression, la ligne donne le premier survivant responsable, leur IoU FP32 et son éventuel q final.',
        'Dans les matrices de bornes, I est un entier et D vaut 2I/(T+I). Les colonnes de sélection réutilisent le D borné du même j. Les listes de chaque étage dans les mesures par photo permettent d’identifier les couples présents et les arêtes suffisantes. Le maximum un à un se calcule par photo. Tous les rangs partent de zéro.',
        'Une arête perdue cesse d’être suffisante pour l’identité et le même ancêtre. Une identité perd sa possibilité quand sa dernière arête disparaît. Dans une composante en concurrence, la taille du certificat moins le nombre de ses voisins donne le déficit, quel que soit le choix des identités affectées.',
        '[Tableaux par provenance](../provenances.md) · [Toutes les identités](../identites.md) · [Témoins des retraits](../retraits.md) · [Figures](../figures/README.md)'])
    write(dest/'figures'/'README.md', ['# Figures R29',
        'Le premier graphique suit la borne géométrique par graine et par étage. Le second répartit les places perdues au préfixe final entre absence de boîte suffisante et concurrence. [Valeurs numériques des deux figures](donnees.json) · [Effectifs par provenance](../provenances.md)',
        '![Couverture aux six étages](capacites.svg)','![Décomposition des capacités finales par provenance](provenances.svg)'])


def publish(dest,metrics):
    directory=export(dest/'mesures',metrics)
    controls=read(metrics/'capacites.json')
    rows={tag:read(metrics/'photos'/(tag+'.json')) for tag in directory}
    figures(dest/'figures',controls)
    source_tables(dest,controls)
    nominal(dest,rows,directory)
    indexes(dest,rows,directory)


if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('sortie',type=Path,nargs='?',default=ROOT)
    cli.add_argument('--mesures',type=Path,default=ROOT/'mesures')
    args=cli.parse_args()
    publish(args.sortie,args.mesures)
