"""Tables lisibles des deux états, des domaines, des provenances et des identités."""
import argparse
from pathlib import Path

from r19.donnees import SEEDS, read
from r20.tableaux import fmt, slot, table
from r21.inventorier import ROOT
from r21.plan import ARMS, STEPS


def render(out):
    groups=read(out/'mesures/groupes.json');changes=read(out/'mesures/contrastes.json')
    registry=read(ROOT/'inventaires.json');training=read(out/'pertes-et-expositions.json')
    g_rows=[];p_rows=[];id_rows=[];examples=[];loss=[]
    for arm in ARMS:
        for s in map(str,SEEDS):
            for step in STEPS:
                stats=training[arm][s]['1-96' if step==96 else '1-768']
                full=read(out/'mesures'/f'{arm}-{s}-{step}.json')
                for g,v in groups[arm][s][str(step)].items():
                    b=v['effectifs'];t=stats[g]
                    g_rows.append([arm,s,step,g,b['photos'],b['physiques_actives'],b['representees_p05'],b['manquees_p05'],
                                   b['supplementaires_p05'],b['emises_p05'],b['silencieuses_p05'],b['triplets'],
                                   b['contacts_couverts'],b['contacts_au_seuil'],b['faux_etablis_au_seuil'],b['non_resolus_au_seuil'],
                                   t['positifs'],t['negatifs']])
                for k,v in full['par_photo'].items():
                    b=v['bilan'];t=stats['photo/'+k];group='certifiée' if registry[k]['admissible'] else 'partielle'
                    p_rows.append([arm,s,step,k,group,b['physiques_actives'],b['representees_p05'],b['manquees_p05'],b['supplementaires_p05'],
                                   b['contacts_couverts'],b['contacts_au_seuil'],b['masques_apparies'],
                                   fmt(b['somme_dice_masques_R19']/b['masques_admis']),b['axes_apparies'],b['triplets'],
                                   b['coordonnees_distinctes'],b['fusions'],b['dedoublements'],t['positifs'],t['negatifs']])
                    for name,i in v['identites'].items():
                        pres=v['presence_05']['appariements'].get(name)
                        q=i['conjoint_q'] if pres is None else pres
                        d=v['paires_par_identite'][name][q] if q is not None else {}
                        entry=i['entree_q'];err=v['paires_par_identite'][name][entry]['erreur_entree_px704'] if entry is not None else None
                        id_rows.append([arm,s,step,k,name,'complète' if i['complete'] else 'M+A',slot(pres),
                                        slot(i['conjoint_q']),slot(q),fmt(d.get('dice')),d.get('sondes_axe','—'),
                                        fmt(d.get('erreur_entree_px704'),2),slot(entry),fmt(err,2),slot(i['entree_au_seuil_q'])])
            for period,tgroup in training[arm][s].items():
                for g in ('toutes','certifiees','partielles'):
                    t=tgroup[g]
                    loss.append([arm,s,period,g,t['presentations'],t['positifs'],t['negatifs'],t['gradients_ecretes'],
                                 *[fmt(t['moyennes'][n],4) for n in ('masque','axe','entree','presence','total')]])
    for s,phases in changes.items():
        for phase in ('exposition_temoin','effet_regle_final'):
            for metric,group in [('presence','certifiees'),('presence','partielles'),('triplets','toutes')]:
                delta=phases[phase][group][metric]
                examples.append([s,phase,group,metric,*[', '.join(delta[k]) or '—' for k in ('gagnees','perdues','preservees')]])
    def write(file,parts):
        (out/file).write_text('\n\n'.join(parts)+'\n')
    write('groupes.md',['# Résultats par domaine et par provenance',
        'Chaque ligne porte sur les photos nommées dans le registre. Les colonnes de présence utilisent le seuil 0,5. Les triplets R20 et les entrées couvertes utilisent les douze requêtes avant seuil. Les contacts sélectionnés et leurs faux établis ou non résolus utilisent le seuil hérité de la graine. Les comptes positifs et négatifs sont cumulés depuis l’initialisation. Sur les vues partielles, une sortie supplémentaire est une sortie sans appariement à une cible active.',
        table(['Bras','Graine','Pas','Domaine ou source','Photos','Cibles','Représentées','Manquées','Suppl.','Émises','Silences','Triplets','Entrées couvertes','Entrées au seuil','Faux au seuil','Non résolus au seuil','Pos. perte','Nég. perte'],g_rows),
        '[Mesures numériques de tous les groupes](mesures/groupes.json) · [Pertes et expositions](pertes-et-expositions.json)'])
    write('photos.md',['# Résultats par photo',
        'La mesure de présence utilise les champs admis et un seuil de 0,5. Le nombre de positions est compté parmi les douze requêtes. Les fusions comptent des masques couvrant plusieurs fûts à 25 % au moins, les dédoublements des fûts couverts par plusieurs masques. Le Dice moyen inclut toutes les références de masque. Les présences de perte sont cumulées jusqu’au pas indiqué.',
        table(['Bras','Graine','Pas','Photo','Domaine','Actives','Représentées','Manquées','Suppl.','Contacts avant seuil','Au seuil final','Masques','Dice moyen','Axes','Triplets','Positions','Fusions','Dédoubl.','Pos. perte','Nég. perte'],p_rows),
        '[Identités et erreurs individuelles](identites.md) · [Registre et sources](inventaires.md)'])
    write('identites.md',['# Identités reconnues, gains, pertes et conservation',
        'Chaque identité inclut sa photo. Une identité stable compte une fois dans son domaine. Le contraste d’exposition va du témoin 96 au témoin 768. Le contraste de règle va du témoin au candidat au pas 768.',
        table(['Graine','Contraste','Domaine','Mesure','Gagnées','Perdues','Préservées'],examples),
        '## Valeurs par identité et par état',
        'La requête mesurée est celle de présence à 0,5, à défaut celle du triplet avant seuil. Les trois mesures qui suivent appartiennent à cette requête. L’entrée indépendante R19 a son propre numéro et sa propre erreur dans le repère 704. M+A indique un fût à entrée inconnue supervisé par son masque et son axe.',
        table(['Bras','Graine','Pas','Photo','Identité','Cible','q présence','q triplet','q mesurée','Dice','Sondes /21','Erreur (px)','q entrée seule','Erreur seule (px)','q entrée au seuil final'],id_rows),
        '[Contrastes numériques](mesures/contrastes.json) · [Matrices complètes par état](mesures/README.md)'])
    write('pertes.md',['# Expositions et supervision effective',
        'Les tranches 1–96 et 97–768 sont disjointes. La ligne 1–768 comprend les deux. Une cible positive représente une identité affectée lors d’une présentation. Un négatif est compté seulement s’il est effectivement utilisé par la perte de présence. Les pertes sont des moyennes par mise à jour.',
        table(['Bras','Graine','Pas','Domaine','Présentations','Positifs','Négatifs','Écrêtés','Masque','Axe','Entrée','Présence','Totale'],loss),
        '[Effectifs par photo et provenance](pertes-et-expositions.json) · [Registre des trajectoires](reproduire.md)'])
    links=[]
    for s in SEEDS:
        links.append([s,*[f'[{arm} {step}]({arm}-{s}-{step}.json)' for arm in ARMS for step in STEPS]])
    write('mesures/README.md',['# Mesures par état',
        'Chaque fichier contient les neuf photos train, les erreurs et matrices par identité et requête, les candidats au seuil de présence, les scores et positions, les fusions et dédoublements ainsi que les résultats par provenance. Les deux points de contrôle ont le même décodage.',
        table(['Graine','Témoin 96','Témoin 768','Candidat 96','Candidat 768'],links),
        '[Dénominateurs et domaines](groupes.json) · [Contrastes appariés](contrastes.json) · [Décision](../decision.json)'])


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path,nargs='?',default=ROOT)
    render(p.parse_args().out)
