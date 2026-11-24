"""Publier les effectifs entiers et les points de fonctionnement des deux bras."""
import argparse
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_bytes())


def table(lines, headings):
    lines.extend(['', '| '+' | '.join(headings)+' |', '|'+'---|'*len(headings)])


def row(lines, cells):
    lines.append('| '+' | '.join(map(str,cells))+' |')


def pair(stats, key):
    return f"{stats['B'][key]} → {stats['V'][key]}"


def render(measures,out):
    s = read(measures/'synthese.json')['poids']
    lines = ['# Tables R16 · Développement indépendant',
        '', 'B désigne le générateur R13 et V ses segments retenus à 0,5. Les flèches des tableaux indiquent B → V. Chaque checkpoint lit les mêmes onze photos.',
        '', '## Quatre critères de décision',
        '', 'Les deux premières fractions comptent les segments retirés sur le nombre initial. Les deux suivantes comptent les appariements conservés sur le nombre initial. Les seuils sont respectivement 60 %, 60 %, 90 % et 90 %.']
    table(lines,['Checkpoint','Vides retirés','Faux occupés retirés','Axes conservés','Bouts propres conservés','Sorties B → V','Médiane CPU V, µs'])
    for name,v in s.items():
        st=v['segments']
        frac=[f"{c['numerateur']}/{c['denominateur']}" for c in st['criteres'].values()]
        row(lines,[name,*frac,pair(st['bras'],'segments'),f"{st['cpu_mediane_ns']/1000:.3f}"])
    for mode,title in [('historique','Seuils contacts hérités de R13'),('local_R9','Seuils contacts choisis sur ces onze photos')]:
        lines+=['','## '+title,'','Chaque rappel a 18 entrées visibles au dénominateur. Le budget sur onze photos autorise deux propositions non appariées au total. Les JSON conservent les seuils avec leur précision de calcul.']
        table(lines,['Checkpoint','Bras','Seuil','Retrouvés / 18','Propositions','Non appariées','Faux établis','Non résolues','Silences / 11','Vides déclenchés / 4','Budget tenu'])
        for name,v in s.items():
            for arm,c in v['contacts'].items():
                p=c[mode]
                row(lines,[name,arm,f"{p['seuil']:.10f}",f"{p['matched']}/18",
                           *[p[k] for k in ('proposals','unmatched','false','unresolved','silent','empty_with_points')],
                           'oui' if p['budget_tenu'] else 'non'])
    lines+=['','## Provenances et conditions','',
            'Chaque ligne contient les comptes dans les deux bras. Le champ « non résolus sur occupées » regroupe les segments sans lecture et les lectures incertaines. Un segment compatible avec un axe peut être inclus dans ce total. Les lignes de conditions peuvent se recouper. Les faux des tableaux de contacts ont pour unité une proposition d’entrée, les faux géométriques un segment examiné.',
            '', 'Les JSON associés donnent aussi le détail des contacts à chaque seuil, chaque inventaire, les rangs documentaires hors sortie et les voisins par instance.']
    for name in s:
        detail=read(measures/(name+'.json'))
        lines+=['','### '+name,'',f'[Mesures et rangs]({measures.name}/{name}.json) · [Prédictions individuelles](predictions/{name}.json)']
        table(lines,['Provenance ou condition','Photos','Sorties','Parasites sur vides','Faux occupés','Non résolus sur occupées','Axes stricts','Entrées au bon bout','Hors sortie négatifs / tubes / incertains'])
        for k,p in detail['segments_par_strate'].items():
            if k=='ensemble':
                continue
            off=p['documentaires_hors_sortie']
            row(lines,[k.removeprefix('source/'),p['photos'],*[pair(p['bras'],k) for k in
                       ('segments','parasites_sur_vide','faux_occupes','non_resolus_occupes','strict_1a1','bout_strict_1a1')],
                       ' / '.join(str(off.get(k,0)) for k in ('negatif_verifie','tube','incertain'))])
    out.write_text('\n'.join(lines)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mesures',type=Path)
    parser.add_argument('sortie',type=Path)
    args=parser.parse_args()
    render(args.mesures,args.sortie)
