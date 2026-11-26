"""Tables de revue tirées des mesures archivées, sans décodage ni modèle."""
from r19.donnees import read
from r25.analyser import last
from r25.commun import ARMS, ROOT, ZIG, captures, tag
from r25.sondes import STAGES

NAMES={'dice':'Dice R22','bloc8':'Bloc 8 R24'}
STATUS={'conserves':'conservé','pertes':'perte','gains':'gain','absents_deux':'absent des deux'}
LIMIT={'axe_seuille':'seuillage de l’axe','intersection_masque':'intersection avec le masque',
       'taille_composante':'taille des composantes','elongation_PCA':'critère d’élongation',
       'portee_PCA':'portée de la PCA','ligne_PCA':'ligne de PCA','decoupe_composante':'découpe par la composante',
       'portions_trop_courtes':'portions intérieures trop courtes','orientation':'orientation','attribution':'attribution'}
KEYS='axe · intersection · taille · élongation · portée · ligne PCA · intérieur · segment · orienté'


def write(path,lines):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text('\n'.join(lines).rstrip('\n')+'\n')


def parts(root,seed,k):
    return {arm:read(root/'identites'/(tag(arm,seed,k)+'.json'))['identites'] for arm in ARMS}


def qstr(q):
    return '—' if q is None else f'q{q}'


def qlist(qs):
    return ', '.join(map(qstr,qs)) or '—'


def profile(counts):
    return ' · '.join(str(counts[s]) for s in STAGES)


def choose(row,pair):
    if 'limite' in row:
        strong=row['q_appariee_autre_bras']
        qs=pair[row['bras_en_defaut']][row['identite']]['requetes']
        weak=max(qs,key=lambda q:(q['comptes']['oriente'],last(q['comptes']),-q['q']))['q']
        return list(dict.fromkeys([strong,weak]))
    if row['photo']==ZIG and row['graine']==9303 and row['identite']==ZIG+':2':
        return [8,5]
    return []


def stem(row):
    return f"{row['graine']}-{row['identite'].replace(':','-')}"


def measured(state):
    q=state['axe_q']
    if q is None:
        return f"— (max. {max(r['comptes']['oriente'] for r in state['requetes'])})"
    return f"q{q} ({state['requetes'][q]['comptes']['oriente']})"


def traces(root,out,arm,seed,k,states):
    name=tag(arm,seed,k)
    geo=f'../geometrie/{arm}-{seed}/{k}.json'
    lines=[f'# {NAMES[arm]} · {seed} · photo {k}','',
           f'Les identités et les douze profils sont dans le [bilan de la photo](../identites/{name}.json). '
           f'Les distances aux segments, angles et affectations sont dans la [géométrie finale]({geo}). '
           'Les deux couches float32 conservent la résolution native. Les colonnes de cellules sont calculées dans toute la grille.','',
           '| Requête | Cartes axe et masque | Cellules axe | Cellules masque | Intersection | Composantes | Segments | 21 sondes par identité |',
           '|---|---|---:|---:|---:|---:|---:|---|']
    for q in range(12):
        rel=f'{name}/q{q:02}'
        rec=read(root/'composantes'/(rel+'.json'))
        lines.append(f"| q{q} | [NPZ](../cartes/{rel}.npz) | {rec['cellules_axe']} | {rec['cellules_masque']} | "
                     f"{rec['cellules_intersection']} | [{len(rec['composantes'])}](../composantes/{rel}.json) | "
                     f"{rec['segments']} | [Logits et distances](../sondes/{rel}.json) |")
    lines+=['','## Profils des requêtes','','Effectif sur 21, dans l’ordre '+KEYS+
            '. La colonne « axe attribué » rend visible la concurrence entre identités.','',
            '| Identité | Requête | Profil | Axe attribué | Masque admis | Entrée admise | Triplet admissible |',
            '|---|---|---|---|---|---|---|']
    for n,state in states.items():
        for rec in state['requetes']:
            q=rec['q']
            flags=[state['axe_q']==q,rec['masque_admis'],rec['entree_admise'],rec['triplet_admissible']]
            lines.append('| '+' | '.join([n.split(':')[-1],qstr(q),profile(rec['comptes']),
                                          *('oui' if v else '—' for v in flags)])+' |')
    write(out/'traces'/(name+'.md'),lines)


def render(root=ROOT,out=ROOT):
    rows=read(root/'bilan-identites.json')
    table=['# Bilan par graine, photo et identité','',
           'Chaque cellule Dice ou bloc 8 donne la requête attribuée et ses sondes orientées sur 21. '
           'En l’absence d’attribution, elle donne le maximum atteint par une seule requête. '
           'Les colonnes suivantes recensent toutes les requêtes admissibles et les profils des deux bras.','',
           '[Transitions détaillées](transitions.md) · [Bilan numérique](bilan-identites.json) · [Archives des neuf photos](archives.md)']
    transitions=['# Les 13 pertes et les 5 gains','',
          'Pour chaque transition, la limite descriptive est celle atteinte le plus en aval par une requête du bras en défaut. '
          'Toutes les requêtes atteignant cette limite sont nommées. Les autres peuvent rencontrer une limite différente. '
          'Le profil de chaque ligne donne les sondes proches sur 21 dans cet ordre, '+KEYS+'. '
          'À la dernière étape, le segment doit également satisfaire l’orientation.','',
          'Les profils de la requête attribuée dans le bras qui réussit et de la meilleure requête illustrée sont comparés '
          'à numéro fixe. Si ces deux requêtes ne représentent pas toutes les limites, une ligne supplémentaire donne le '
          'profil de la première requête atteignant la limite en aval. Les douze profils restent disponibles dans les tables de la photo.','']
    for arm,seed,k in captures():
        if arm!=ARMS[0]:
            continue
        group=[r for r in rows if r['graine']==seed and r['photo']==k]
        pair=parts(root,seed,k)
        title=f"{group[0]['provenance']} · {seed} · {k}"
        table+=['',f'## {title}','',
                ' · '.join(f'[{NAMES[a]}, douze requêtes](traces/{tag(a,seed,k)}.md)' for a in ARMS),'',
                '| Nº | Statut | Dice (sur 21) | Bloc 8 (sur 21) | Admissibles Dice / bloc 8 | Triplet Dice / bloc 8 |',
                '|---|---|---|---|---|---|']
        for a in ARMS:
            traces(root,out,a,seed,k,pair[a])
        for row in group:
            n=row['identite']
            short=n.split(':')[1]
            candidates=' / '.join(qlist(row['admissibles'][s]) for s in ARMS)
            triples=' / '.join(qstr(row['triplets'][s]) for s in ARMS)
            selected=choose(row,pair)
            sheet=f'figures/{stem(row)}.svg'
            identity=f'[{short}]({sheet})' if selected else short
            table.append(f"| {identity} | {STATUS[row['statut']]} | {measured(pair['dice'][n])} | {measured(pair['bloc8'][n])} | {candidates} | {triples} |")
            if 'limite' not in row:
                continue
            advanced=row['requetes_les_plus_avancees']
            transitions += [f"## {row['provenance']} nº {short} · {seed} · {STATUS[row['statut']]}",'',
                f"Bras en défaut **{NAMES[row['bras_en_defaut']]}**. Limite observée **{LIMIT[row['limite']]}**. "
                f"Cette limite est atteinte par {qlist(advanced)}. "
                f"[Planche]({sheet}) · "+' · '.join(f'[{NAMES[a]}](traces/{tag(a,seed,k)}.md)' for a in ARMS),'',
                '| Requête et sélection | Dice · neuf étapes | Bloc 8 · neuf étapes |','|---|---|---|']
            illustrate=selected.copy()
            if not set(selected).intersection(advanced):
                illustrate.append(advanced[0])
            for j,q in enumerate(illustrate):
                role=('attribuée' if j==0 else 'illustrée côté en défaut') if q in selected else 'limite en aval'
                profiles=[profile(pair[a][n]['requetes'][q]['comptes']) for a in ARMS]
                transitions.append(f"| q{q} · {role} | {profiles[0]} | {profiles[1]} |")
            for c in row['concurrence']:
                targets=', '.join(f"nº {v['identite'].split(':')[1]} ({v['sondes']}/21)" for v in c['attribution'])
                transitions+=['',f"La requête q{c['q']} couvre {c['sondes']}/21 sondes de cette identité. L’appariement l’attribue à {targets}."]
            transitions.append('')
    write(out/'tableaux.md',table)
    write(out/'transitions.md',transitions)


if __name__=='__main__':
    render()
