"""Tables de lecture de toutes les identités et des douze requêtes à chaque passage."""
import argparse
from pathlib import Path

from r19.donnees import read, save
from r20.corpus import ROOT


def fmt(x, digits=3):
    return '—' if x is None else f'{x:.{digits}f}'.replace('.', ',')


def slot(q):
    return '—' if q is None else f'q{q}'


def table(columns, rows):
    def line(parts):
        return '| ' + ' | '.join(map(str, parts)) + ' |'
    return '\n'.join([line(columns), line(['---'] * len(columns)), *map(line, rows)])


def render(out, archives):
    spec = read(ROOT / 'configuration.json')
    sources = {s['photo']: s['provenance'] for s in spec['train']}
    photos, identities, queries, groups = [], [], [], []
    by_origin = {}
    for seed in spec['checkpoints_A']:
        data = read(out / 'mesures' / f'A-{seed}.json')
        by_origin[seed] = {}
        for source, totals in data['par_provenance'].items():
            items = [v for k,v in data['par_photo'].items() if sources[k]==source]
            coverage = {k:sum(v['couverture'][k] for v in items)
                        for k in ('propositions','apparies','non_appariees','faux_etablis','non_resolus')}
            by_origin[seed][source] = dict(bilan=totals, couverture_avant_seuil=coverage)
            groups.append([seed, source, len(items), totals['entrees_admises'], coverage['apparies'],
                           coverage['faux_etablis'], coverage['non_resolus'], totals['triplets'],
                           totals['contacts_au_seuil'], totals['faux_etablis_au_seuil'],
                           totals['non_resolus_au_seuil'], totals['triplets_au_seuil']])
        for key, result in data['par_photo'].items():
            b = result['bilan']
            photos.append([seed, key, sources[key], b['entrees_admises'], b['contacts_couverts'],
                b['contacts_au_seuil'], b['propositions_au_seuil'], b['faux_etablis_au_seuil'],
                b['non_resolus_au_seuil'], b['masques_apparies'], fmt(b['somme_dice_masques_R19']/b['masques_admis']),
                b['axes_apparies'], b['triplets'], b['triplets_au_seuil'], b['coordonnees_distinctes'],
                b['fusions'], b['dedoublements'], b['sorties_hors_triplet']])
            for name, row in result['identites'].items():
                cells = result['paires_par_identite'][name]
                q = row['masque_q']
                a = row['axe_q']
                j = row['conjoint_q']
                joint = '—' if j is None else f"q{j} · {fmt(cells[j]['dice'])} · {cells[j]['sondes_axe']} · {fmt(cells[j]['erreur_entree_px704'],2)}"
                identities.append([seed, key, name, 'complète' if row['complete'] else 'entrée exclue',
                    '—' if q is None else f"q{q} · {fmt(cells[q]['dice'])}",
                    '—' if a is None else f"q{a} · {cells[a]['sondes_axe']}",
                    slot(row['entree_q']), slot(row['entree_au_seuil_q']), joint,
                    slot(row['conjoint_au_seuil_q']), slot(row['affectation_perte_q'])])
            queries.extend(query_rows(seed, key, result))
    body = ['# A · Neuf photos train, trois checkpoints R19',
        'Chaque ligne utilise 12 requêtes. Les positions distinctes sont comptées à l’intérieur de la photo. Une fusion compte un masque qui recouvre au moins 25 % de plusieurs fûts. Un dédoublement compte une référence couverte à ce niveau par plusieurs masques. Le Dice est la moyenne des masques admis, après affectation indépendante. Les faux et non résolus sont les contacts non appariés au seuil hérité.',
        table(['Graine','Photo','Provenance','Entrées admises','Couvertes','Au seuil','Propositions au seuil','Faux','Non résolus','Masques appariés','Dice moyen','Axes appariés','Triplets','Triplets au seuil','Positions distinctes','Fusions','Dédoubl.','Hors triplet'], photos),
        '## Résultats des huit provenances',
        table(['Graine','Provenance','Photos','Entrées admises','Couvertes','Faux avant seuil','Non résolus avant seuil','Triplets','Entrées au seuil','Faux au seuil','Non résolus au seuil','Triplets au seuil'],groups),
        '[Effectifs et valeurs numériques par provenance](mesures/provenances.json)',
        '## Affectations par flèche',
        'Les quatre colonnes indépendantes montrent les numéros attribués par R19. Le triplet exige les trois mesures sur la même requête. L’axe donne le nombre de sondes sur 21, la distance d’entrée est en pixels au grand côté de 704. Une entrée exclue conserve ses cibles masque et axe. Un tiret marque une valeur indisponible ou un appariement absent. Les 12 possibilités de chaque référence figurent dans la matrice JSON de sa graine.',
        table(['Graine','Photo','Identité','Validité','Masque q · Dice','Axe q · sondes','Entrée q','Entrée au seuil','Triplet q · Dice · sondes · px','Triplet au seuil','q de la perte'], identities),
        '[Les 324 sorties avec leur score](sorties-A.md) · [9301](mesures/A-9301.json) · [9302](mesures/A-9302.json) · [9303](mesures/A-9303.json)']
    save(out/'mesures/provenances.json',by_origin)
    (out/'photos-et-identites-A.md').write_text('\n\n'.join(body)+'\n')
    intro = 'Les coordonnées sont les centres de sortie dans le repère 704. « Même pic » compte les autres requêtes à cette position. « Fûts fusionnés » exige une couverture de 25 % par fût. L’affectation de perte inclut les cibles partielles. Les scores sont arrondis pour la lecture, les décisions utilisent leur précision archivée.'
    (out/'sorties-A.md').write_text('# A · Les 324 sorties\n\n'+intro+'\n\n'+
        table(QUERY_COLUMNS, queries)+'\n\n[Retour aux photos et aux identités](photos-et-identites-A.md)\n')

    states, costs, outputs, summary = [], [], [], []
    for step in read(archives/'B/execution.json')['controles']:
        key = spec['photo_B']
        result = read(out/'mesures'/f'B-{step:04d}.json')
        folder = archives/'B'/f'{step:04d}'
        loss = read(folder/'pertes.json')[key]
        control = read(folder/'controle.json')
        by_id = {d['instance']:d for d in loss['par_identite']}
        for name, row in result['identites'].items():
            owner = row['conjoint_q']
            q = row['affectation_perte_q'] if owner is None else owner
            c = result['paires_par_identite'][name][q]
            states.append([step,name,'complète' if row['complete'] else 'entrée exclue',slot(q),
                'perte' if owner is None else 'triplet',fmt(c['dice'],5),fmt(c['fraction_plafond'],5),
                c['sondes_axe'],fmt(c['erreur_entree_px704']),slot(row['masque_q']),slot(row['axe_q']),
                slot(row['entree_q']),slot(row['conjoint_au_seuil_q'])])
            t=by_id[name]
            costs.append([step,name,slot(t['q']),*[fmt(t[k],5) for k in
                         ('masque','axe','entree','presence_positive','cout_affectation')]])
        for row, s in zip(query_rows(step,key,result), loss['logits_presence'], strict=True):
            outputs.append([*row,fmt(s,5)])
        summary.append([step,fmt(control['gradient_avant_ecretage'],4),*[fmt(loss['pertes'][k],5) for k in ('masque','axe','entree','presence')],
                        len(loss['negatifs'])])
    body = ['# B · Une seule trajectoire de mémorisation',
        'La requête examinée est celle du triplet lorsqu’il existe, sinon celle choisie par le coût de la perte à cet état. Les trois affectations indépendantes peuvent avoir d’autres numéros. Le plafond de Dice vaut 1 sur les pixels évaluables des trois références.',
        table(['Pas','Identité','Validité','q examiné','Choix','Dice absolu','Fraction du plafond','Sondes / 21','Entrée (px)','q masque seul','q axe seul','q entrée seule','q conjoint au seuil'],states),
        '## Pertes aux contrôles',
        'Chaque tâche est calculée sur ses cibles admises. La norme est mesurée avant écrêtage. Au dernier état, le gradient est mesuré pour le diagnostic.',
        table(['Pas','Norme','Masque','Axe','Entrée','Présence','Négatifs éligibles'],summary),
        table(['Pas','Identité','q de perte','Masque','Axe','Entrée','Présence positive','Coût d’affectation'],costs),
        '## Toutes les requêtes',intro,table([*QUERY_COLUMNS,'Logit de présence'],outputs),
        '[Valeurs du pas 0](mesures/B-0000.json) · [Valeurs du pas 96](mesures/B-0096.json) · [Pertes et gradients des 96 mises à jour](archives/B/progression.jsonl)']
    (out/'identites-et-sorties-B.md').write_text('\n\n'.join(body)+'\n')


QUERY_COLUMNS = ['Graine ou pas','Photo','q','Score','Au seuil','x','y','Même pic','Fragments',
                 'Fûts fusionnés','Cible perte','Entrée appariée','Triplet','Présence négative']


def query_rows(state, key, result):
    for v in result['sorties']:
        yield [state,key,slot(v['q']),fmt(v['score'],7),'oui' if v['garde_au_seuil'] else '—',
               *[fmt(n,3) for n in v['xy']],v['autres_au_meme_maximum'],v['fragments'],
               ', '.join(v['couverture_multiple']) or '—', v['affectation_cible'] or '—',
               v['entree_attribuee'] or '—',v['triplet_attribue'] or '—',
               'oui' if v['presence_negative_eligible'] else '—']


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path, nargs='?', default=ROOT)
    parser.add_argument('--archives', type=Path, default=ROOT/'archives')
    args=parser.parse_args()
    render(args.out,args.archives)
