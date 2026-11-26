"""Régénérer les tableaux et les figures à partir des seuls registres numériques."""
import argparse
import json
from pathlib import Path

from r19.donnees import SEEDS, read, save
from r19.tracer import draw, label, node, page, panel, save_svg
from r20.tableaux import fmt, slot, table
from r21.plan import STEPS
from r24.mesures import ARMS

NAMES = {'R21':'R21', 'R22':'Dice R22', 'bloc8':'Bloc 8 R24'}
COLORS = {'R21':'#78828b', 'R22':'#227772', 'bloc8':'#a34220'}
FIELDS = ('masque_90_un_a_un','axes_apparies','masque_et_axe_un_a_un','masque_et_axe_separes',
          'contacts_couverts','triplets','inconnue_conjointe','VP','FP','FN','fusions','dedoublements',
          'supplementaires_p05','faux_avant_seuil','non_resolus_avant_seuil','contacts_au_seuil',
          'faux_etablis_au_seuil','non_resolus_au_seuil','silences_au_seuil')


def write(path,title,desc,cols,rows):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(f'# {title}\n\n{desc}\n\n'+table(cols,rows)+'\n')


def list_q(qs):
    return ', '.join(map(slot,qs)) or '—'


def tables(out):
    totals = read(out/'mesures/groupes.json')
    changes = read(out/'mesures/contrastes.json')
    index,moves,unknown,training = [],[],[],[]
    for seed in map(str,SEEDS):
        ins = read(out/f'instrumentation/{seed}.json')
        for span,groups in ins.items():
            st=groups['toutes']
            for g,parts in st['groupes'].items():
                training.append([seed,span,g,st['presentations'],st['gradients_ecretes'],
                    *[fmt(st['moyennes'][k],4) for k in ('masque','axe','entree','presence','dice')],
                    *[fmt(parts[k]['moyenne'],6) for k in ('norm_grad_avant','norm_grad_apres','delta_l2','delta_max_abs')]])
        for t in map(str,STEPS):
            for arm in ARMS:
                key=f'{arm}-{seed}-{t}'
                byid=read(out/f'identites/{key}.json')
                rows=[]
                for k,p in byid.items():
                    for n,i in p['identites'].items():
                        m=i['masque_R19']
                        fields=i['par_champ']
                        rows.append([p['provenance'],n,list_q(fields['masque']),list_q(fields['axe']),
                            list_q(i['masque_et_axe_qs']),slot(i['masque_et_axe_q']),list_q(fields.get('entree',[]))
                            if 'entree' in fields else 'inconnue',slot(i['entree_q']),slot(i['triplet_q']),
                            'oui' if i['champs_repartis'] else '—',m['VP'],m['FP'],m['FN'],
                            list_q(i['fusions_q']),list_q(i['dedoublements_q'])])
                        if 'entree' not in fields:
                            unknown.append([seed,t,NAMES[arm],p['provenance'],n,slot(i['masque_90_q']),
                                slot(i['axe_q']),list_q(i['masque_et_axe_qs']),slot(i['conjoint_q']),slot(i['presence_q'])])
                desc='M et A désignent les requêtes admissibles pour le masque et l’axe. M∩A recense leur intersection. MA, E et T sont les appariements un à un du couple, de l’entrée et du triplet. « Répartis » signifie que chaque champ admis existe mais qu’aucune requête ne les réunit tous. Les pixels suivent l’attribution R19.'
                write(out/f'tableaux/{key}.md',f'{NAMES[arm]} · Graine {seed} · Pas {t}',desc,
                      ['Provenance','Identité','M','A','M∩A','MA','Entrées disponibles','E','T','Répartis','VP','FP','FN','Fusions q','Dédoublements q'],rows)
                groups=totals[arm][seed][t]
                count=groups['toutes']['effectifs']
                index.append([seed,t,NAMES[arm],count['triplets'],count['contacts_couverts'],
                              f'[Identités](tableaux/{key}.md)',f'[Groupes](groupes/{key}.md)'])
                write(out/f'groupes/{key}.md',f'Groupes · {NAMES[arm]} · {seed} · {t}',
                    'Une ligne par photo, provenance et type d’inventaire. MA est le couple masque–axe apparié un à un. « M/A séparés » compte les identités dont les deux listes sont non vides et disjointes. Les cinq entrées inconnues sont évaluées sur leurs champs admis.',
                    ['Groupe','M','A','MA','M/A séparés','Entrées','Triplets','Inconnues MA','VP','FP','FN',
                     'Fusions','Dédoublements','Suppl. à présence 0,5','Faux avant seuil','Non résolus avant seuil',
                     'Entrées au seuil','Faux au seuil','Non résolus au seuil','Silences au seuil'],
                    [[g,*[v['effectifs'][f] for f in FIELDS]] for g,v in groups.items()])
            for ref in ('R22','R21'):
                for f,title in [('triplet_q','Triplets'),('entree_q','Entrées')]:
                    c=changes[seed][t][ref]['champs'][f]
                    moves.append([seed,t,ref,title,*[', '.join(c[key]) or '—' for key in ('gagnees','conservees','perdues')]])
    write(out/'tableaux.md','Mesures par état','Les deux références et le candidat portent sur les mêmes neuf photos du train. Les triplets sont comptés avant seuil sur les 26 identités complètes.',
          ['Graine','Pas','Bras','Triplets','Entrées','Par identité','Par photo et provenance'],index)
    write(out/'transitions.md','Gains, conservations et pertes','Chaque ligne compare le bloc 8 à la référence indiquée. Le préfixe de l’identité est le numéro stable de sa photo.',
          ['Graine','Pas','Référence','Champ','Gagnées','Conservées','Perdues'],moves)
    write(out/'inconnues.md','Cinq identités à entrée inconnue','Douze requêtes par photo. La dernière colonne applique la présence à 0,5 et exige les champs connus sur la même requête.',
          ['Graine','Pas','Bras','Provenance','Identité','Masque','Axe','M∩A','MA un à un','MA présence ≥ 0,5'],unknown)
    write(out/'apprentissage.md','Pertes et mouvements des poids','Moyennes par présentation. Les deux groupes partagent les mêmes pertes. Les normes sont mesurées avant et après l’écrêtage global. Δ est le déplacement effectif après AdamW, avant l’unité suivante.',
          ['Graine','Pas','Groupe','Prés.','Écrêtées','Masque','Axe','Entrée','Présence','Dice','Grad. avant','Grad. après','Δ L2','Δ max abs.'],training)


def figures(out,archives):
    folder=out/'figures'
    folder.mkdir(parents=True,exist_ok=True)
    totals=read(out/'mesures/groupes.json')
    final={}
    colors=['#195c70','#973f37','#735fa2']
    for seed in map(str,SEEDS):
        final[seed]={arm:{g:v['effectifs']['triplets'] for g,v in totals[arm][seed]['768'].items()
                          if g.startswith('source/')} for arm in ARMS}
    sources=sorted(final['9301']['R22'])
    root=page(1120,490,'Triplets complets par provenance au pas 768',
              'Nombre d’identités appariées avant seuil. Chaque triplet réunit masque, axe et entrée sur une même requête.')
    for j,seed in enumerate(map(str,SEEDS)):
        x=273+277*j
        label(root,x+85,108,'Graine '+seed,16,'bold','middle')
        for i,src in enumerate(sources):
            y=148+35*i
            if j==0:
                label(root,28,y,src.removeprefix('source/'),14)
            for a,arm in enumerate(ARMS):
                xx=x+61*a
                n=final[seed][arm][src]
                node(root,'rect',x=xx,y=y-17,width=45,height=23,rx=4,fill=COLORS[arm] if n else '#e1e5e4')
                node(root,'text',x=xx+22,y=y,font_size=14,text_anchor='middle',fill='white' if n else '#34444a').text=str(n)
        for a,arm in enumerate(ARMS):
            label(root,x+22+61*a,442,arm if arm!='bloc8' else 'R24',13,'bold','middle')
    save_svg(root,folder/'provenances.svg')
    series={}
    for seed in map(str,SEEDS):
        rows=[json.loads(line) for line in (archives/seed/'progression.jsonl').read_text().splitlines()]
        series[seed]=[]
        for start in range(0,768,32):
            block=rows[start:start+32]
            mean=lambda vals:sum(vals)/len(vals)
            obs=dict(fin=start+32,ecretage=mean([int(r['ecrete']) for r in block]))
            for k in ('total','masque','axe','entree','presence','dice'):
                obs[k]=mean([r[k] for r in block])
            for g in ('tete','bloc8'):
                obs['grad_'+g]=mean([r['groupes'][g]['norm_grad_avant'] for r in block])
                obs['delta_'+g]=mean([r['groupes'][g]['delta']['l2'] for r in block])
            series[seed].append(obs)
    for name,fields,title,sub in [
        ('pertes',[('total','Totale'),('masque','Masque'),('axe','Axe'),('entree','Entrée'),('presence','Présence'),('dice','Dice')],
         'Pertes du candidat au fil des présentations','Moyennes sur 32 présentations successives. Les composantes sont présentées avant pondération.'),
        ('optimisation',[('grad_tete','Gradient de la tête'),('grad_bloc8','Gradient du bloc 8'),('ecretage','Fraction écrêtée'),
         ('delta_tete','Déplacement L2 de la tête'),('delta_bloc8','Déplacement L2 du bloc 8')],
         'Gradients et déplacements observés','Chaque point résume 32 mises à jour. Les gradients sont mesurés avant écrêtage et les déplacements après AdamW.')]:
        root=page(1080,560,title,sub)
        for j,(k,title) in enumerate(fields):
            top=max(r[k] for rr in series.values() for r in rr)
            top=1. if k=='ecretage' else max(float(f'{top*1.05:.2g}'),top,1e-8)
            fx,fy=panel(root,60+350*(j%3),128+200*(j//3),275,126,title,768,top,[0,192,384,576,768],[0,top/2,top])
            for c,(seed,rows) in zip(colors,series.items(),strict=True):
                draw(root,[(fx(r['fin']),fy(r[k])) for r in rows],c)
        for j,(c,seed) in enumerate(zip(colors,series,strict=True)):
            x=45+j*145
            draw(root,[(x,521),(x+25,521)],c,3)
            label(root,x+31,526,seed)
        label(root,505,526,'Abscisse en mises à jour du candidat.',13)
        save_svg(root,folder/(name+'.svg'))
    save(folder/'donnees.json',dict(triplets_768=final, trajectoires_par_bloc_de_32=series))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    p.add_argument('--archives',type=Path,default=Path('r24/archives/bloc8'))
    a=p.parse_args()
    tables(a.out)
    figures(a.out,a.archives)
