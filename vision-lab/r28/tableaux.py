"""Tables de revue reliées aux mesures de chaque photo."""
from r28.commun import SEEDS, STEPS, read, tag

POLICY = ('q_admis','q_separe','q_dice_total')


def fmt(value, places=3):
    return '—' if value is None else f'{value:.{places}f}'.replace('.',',')


def pct(rate):
    return '—' if rate is None else fmt(100*rate,1)+' %'


def qlabel(q):
    return '—' if q is None else 'q'+str(q)


def qs(values):
    return ', '.join(map(qlabel,values)) or '∅'


def qset(state):
    return ' / '.join(qlabel(state[k]) for k in POLICY)


def table(columns, data):
    def line(row):
        return '| '+' | '.join(map(str,row))+' |'
    return '\n'.join([line(columns),line(['---']*len(columns)),*(line(row) for row in data)])


def write(path, paragraphs):
    path.write_text('\n\n'.join(paragraphs)+'\n')


def record(seed, step, photo, caption):
    return f'[{caption}](mesures/couples/{tag(seed,step,photo)}.json)'


def identities(out, rows):
    paragraphs=['# Les 31 identités aux quatre contrôles',
        'Chaque ligne relie la carte native et les deux jeux de douze sorties par les identifiants de photo et de fût. Les deux valeurs d’une cellule vont de 512 à 768. Un tiret indique une attribution absente. Le rang q recommence à zéro à chaque contrôle. Les coordonnées et scores de toutes les boîtes et les quatre classes de pixels figurent dans les JSON en bout de ligne.',
        'Une arête admissible franchit le seuil personnel. Une arête séparée franchit aussi le test de recouvrement des autres identités. Les trois attributions globales sont calculées séparément. [Table des bascules](transitions.md) · [Détail des 62 trajectoires](mesures/trajectoires.json)']
    for seed in SEEDS:
        paragraphs.append(f'## Graine {seed}')
        for group in dict.fromkeys(r['provenance'] for r in rows):
            cells=[]
            details=[]
            for r in rows:
                if (r['graine'],r['provenance'])!=(seed,group):
                    continue
                photo,n=r['photo'],r['identite']
                a,b=(r['controles'][str(t)] for t in STEPS)
                def pair(fn):
                    return ' → '.join(str(fn(s)) for s in (a,b))
                cells.append([n,pair(lambda s:fmt(s['meilleur_RPN']['iou'])),
                    pair(lambda s:qlabel(s['meilleure_boite']['q'])+' '+fmt(s['meilleure_boite']['iou_boite'])),
                    pair(lambda s:qlabel(s['meilleur_dice']['q'])+' '+fmt(s['meilleur_dice']['dice'])),
                    fmt(a['seuil_dice']),pair(lambda s:fmt(s['meilleur_dice']['marge'],4)),
                    pair(lambda s:qlabel(s['appris']['q_separe'])),r['sens']])
                for step,s in zip(STEPS,(a,b)):
                    learn,full,oracle=s['appris'],s['plein'],s['oracle']
                    details.append([n,record(seed,step,photo,str(step)),qs(learn['admissibles']),qs(learn['separes']),
                        qset(learn),qset(full),fmt(full['meilleur_dice']),fmt(oracle['meilleur_dice']),
                        qs(oracle['admissibles']),qlabel(oracle['q_attribue'])])
            paragraphs.extend([f'### {group}',table(['Identité','IoU RPN','q boîte · IoU','q masque · Dice',
                'Seuil','Marge Dice','q séparé','Évolution'],cells),
                table(['Identité','Mesures du pas','q admis appris','q séparés appris','Attribués appris¹',
                       'Attribués pleins¹','Meilleur Dice plein','Borne max.','q possibles','q oracle un à un'],details)])
    paragraphs.append('¹ Les trois rangs correspondent successivement aux attributions admissible, séparée et de Dice total maximal. Les mesures JSON donnent aussi les listes d’arêtes du témoin plein, les boîtes en xyxy et les scores sans arrondi.')
    write(out/'identites.md',paragraphs)


def transitions(out, rows, figures):
    captions={'bascule_boite':'Couverture de boîte','seuil_masque_boite_couvrante':'Seuil de masque, boîte couvrante',
              'appariement_seul':'Attribution seule'}
    pars=['# Changements d’attribution entre 512 et 768',
          'Chaque section suit une identité R18. Les boîtes sont en xyxy, en pixels natifs. Les nombres sont arrondis pour la lecture, la décision utilise les produits entiers. La meilleure proposition RPN est cherchée sur toutes les propositions RPN archivées. Elle peut différer de l’ancêtre de la sortie finale. [Trajectoires sans arrondi](mesures/trajectoires.json) · [Toutes les identités](identites.md)']
    by_id={(f['graine'],f['identite']):f['fichier'] for f in figures['transitions']}
    for row in rows:
        if row['sens']=='stable':
            continue
        seed,n,k=row['graine'],row['identite'],row['photo']
        pars.append(f"## {n} · {seed} · {row['sens']}")
        pars.append(f"{row['provenance']}. {captions.get(row['categorie'],row['categorie'])}. [Planche](figures/{by_id[seed,n]})")
        metrics, boxes=[],[]
        for step in STEPS:
            s=row['controles'][str(step)]
            m,b,r=s['meilleur_dice'],s['meilleure_boite'],s['meilleur_RPN']
            learn=s['appris']
            metrics.append([record(seed,step,k,str(step)),fmt(r['iou']),fmt(b['iou_boite']),fmt(m['dice'],5),
                fmt(s['seuil_dice'],5),fmt(m['marge'],5),qs(learn['admissibles']),qs(learn['separes']),qset(learn)])
            for purpose,v,score,overlap in [('Meilleur RPN',r,'logit',r['iou']),
                        ('Meilleure boîte finale',b,'score',b['iou_boite']),('Meilleur Dice',m,'score',m['iou_boite'])]:
                boxes.append([step,purpose,qlabel(v['q']),' · '.join(fmt(x,2) for x in v['boite']),
                              fmt(v[score],6),fmt(overlap,4)])
        pars.extend([table(['Pas','IoU RPN','IoU finale max.','Dice max.','Seuil','Marge','q admis','q séparés',
                            'Attribués admis / séparé / Dice'], metrics),
                     table(['Pas','Sélection locale','Rang','x₁ · y₁ · x₂ · y₂','Logit RPN ou score final','IoU boîte'],boxes)])
    write(out/'transitions.md',pars)


def comparisons(out, matrix, partial):
    pars=['# Comparaison à boîte fixée et par provenance',
        'Les attributions apprises, pleines et optimistes sont recalculées indépendamment par photo. P / A ci-dessous signifie plein / appris. Une fusion compte une sortie et un dédoublement compte une identité selon la règle de 25 % de R27. Le gain est une identité séparée uniquement par le masque appris. Les neuf vues sont occupées. [Données sans arrondi](mesures/comparaison.json) · [Détail par identité](identites.md)']
    for key,rec in matrix.items():
        pars.append(f'## {key}')
        rows=[]
        def describe(name,v):
            f,a,o=v['plein'],v['appris'],v['oracle']
            def couple(field):
                return f"{f[field]} / {a[field]}"
            rows.append([name,a['photos'],a['identites'],couple('admis'),couple('separes'),couple('sorties_fusionnees'),
                couple('identites_dedoublees'),couple('non_apparies'),o['possibles_individuels'],o['cardinalite_un_a_un']])
        describe('Ensemble',rec['ensemble'])
        for s,v in rec['provenances'].items():
            describe(s,v)
        pars.append(table(['Provenance','Photos','Identités','Admis P / A','Séparés P / A','Fusions P / A','Dédoublées P / A',
                           'Non appariées P / A','Possibles seules','Oracle un à un'],rows))
        a=rec['ensemble']
        seps=a['comparaison_appariements']['separes']
        pars.append('**Gains du masque appris** — '+(', '.join(seps['gains_appris']) or 'aucun')+'. **Pertes** — '+(', '.join(seps['pertes_appris']) or 'aucune')+'.')
        feas=a['oracle']
        pars.append('**Identités sans boîte suffisante** — '+(', '.join(feas['impossibles_geometriques']) or 'aucune')+
            '. **Identités avec une arête suffisante écartées dans cette attribution globale** — '+(', '.join(feas['possibles_non_attribues']) or 'aucune')+'.')
        rows=[]
        for label, v in a['memes_couples'].items():
            rows.append([label,v['couples'],fmt(v['mediane_dice_plein']),fmt(v['mediane_dice_appris']),
                         fmt(v['mediane_difference']),v['dice_superieur'],v['dice_identique'],v['dice_inferieur'],
                         v['separes_plein'],v['separes_appris']])
        pars.append(table(['Couples fixés','n','Dice médian plein','Dice médian appris','Médiane Δ',
            'Δ > 0','Δ = 0','Δ < 0','Arêtes séparées pleines','Arêtes séparées apprises'],rows))
        c=a['croisement_IoU_borne_couples']
        d=a['croisement_IoU_borne_identites']
        pars.append(table(['Unité','IoU ≥ 0,5 et borne suffisante','IoU seule','Borne seule','Aucun'],
                    [[name,*row.values()] for name,row in [('Couples',c),('Identités¹',d)]]))
    pars.append('¹ Par identité, les deux prédicats sont existentiels et leur témoin q peut différer. Dans les tables de couples fixés, Δ = Dice appris moins Dice plein. Le suffixe de chaque ligne indique comment les couples ont été choisis, avant de comparer leurs deux Dice.')
    write(out/'comparaison.md',pars)

    pars=['# Annotation partielle des masques appariés',
        'Les tables décrivent les masques appris attribués par l’appariement séparé. Chaque pixel actif garde sa classe R18. Les deux autres politiques et toutes les associations individuelles, y compris leurs q et leurs aires, sont dans le JSON. [Mesures individuelles et groupées](mesures/partiel.json)']
    summaries=partial['separe']['resumes']
    for key in ['9302-512','9302-768','9302','9303-512','9303-768','9303','ensemble']:
        rec=summaries[key]
        pars.append(f'## {key}')
        rows=[]
        for label,v in [('Ensemble',rec['ensemble']),*rec['provenances'].items()]:
            inc,u=v['incidences'],v['union_spatiale']
            rows.append([label,v['associations'],*inc.values(),pct(v['mediane_fraction_ignoree']),pct(v['fraction_ignoree_ponderee']),
                         sum(u.values()),u['ignores'],pct(v['fraction_ignoree_union'])])
        pars.append(table(['Provenance','Associations','Identité','Autres','Fond certifié','255',
              'Médiane 255','255 pondéré','Union totale','255 en union','255 / union'],rows))
    pars.extend(['## Fractions ignorées par identité',
        'Les cellules donnent q et le pourcentage de l’aire native du masque appris séparé qui porte l’étiquette 255. Un tiret correspond à une identité sans attribution au contrôle.',
        table(['Identité','Provenance','9302 / 512','9302 / 768','9303 / 512','9303 / 768'],partial_rows(partial['separe']))])
    write(out/'annotation.md',pars)


def partial_rows(policy):
    records=policy['associations']
    index={(r['identite'],r['graine'],r['pas']):r for r in records}
    photos=policy['resumes']['ensemble']['identites']
    origins={n:v['provenance'] for v in read('r26/train.json')['photos'].values() for n in v['instances']}
    for n in photos:
        cells=[]
        for seed in SEEDS:
            for step in STEPS:
                r=index.get((n,seed,step))
                cells.append(qlabel(r['q'])+' · '+pct(r['fraction_ignoree']) if r else '—')
        yield [n,origins[n],*cells]
