"""Tables lisibles au même niveau de précision que les JSON de revue."""
from r27.bilan import ROOT, NUM, SEEDS, STEPS, read


def table(headers, rows):
    def row(cells):
        return '| '+' | '.join(map(str,cells))+' |'
    return '\n'.join([row(headers),row(['---']*len(headers)),*[row(cells) for cells in rows]])+'\n'


def fmt(value, places=3):
    return f'{value:.{places}f}'.replace('.',',')


def tables(dest=ROOT/'tableaux.md'):
    curves=read(NUM/'courbes.json')
    refs=read(NUM/'references-R22.json')
    groups=read(NUM/'groupes.json')
    diag=read(NUM/'diagnostic.json')['ensemble']
    verdict=read(NUM/'decision.json')['graines']
    lines=['# Résultats des lectures autonomes sur le train',
        'Les neuf photos occupées contiennent 31 identités évaluables. Les colonnes RPN et boîtes comptent les identités ayant au moins une proposition à IoU ≥ 0,5. Les masques admis et séparés sont appariés un à un. Le Dice médian est calculé sur les 31 identités après attribution de Dice total maximal. [Matrices par photo et identité](mesures/README.md) · [Valeurs des courbes](mesures/courbes.json)',
        '## Contrôles du candidat']
    rows=[]
    for seed in SEEDS:
        for step in STEPS:
            r=curves[str(seed)].get(str(step))
            if r:
                rows.append([seed,step,r['rpn_50'],r['boites_50'],r['admis'],r['separes'],fmt(r['dice_median']),
                             r['sorties_fusionnees'],r['identites_dedoublees'],r['sorties']])
            else:
                rows.append([seed,step,*['—']*8])
    lines.append(table(['Graine','Pas','RPN /31','Boîtes /31','Admis /31','Séparés /31','Dice médian','Fusions','Identités dédoublées','Sorties'],rows))
    lines.append('Le tiret correspond à un contrôle indisponible après l’arrêt de 9301. Les trois pas SGD documentés pour cette graine précèdent le contrôle 96. [Incident](incident.md)')
    lines.append('## Témoin Dice R22 archivé')
    rows=[]
    for seed in SEEDS:
        for step in (96,768):
            r=refs[f'{seed}-{step}']['ensemble']
            rows.append([seed,step,r['admis'],r['separes'],fmt(r['dice_median']),r['sorties_fusionnees'],r['identites_dedoublees']])
    lines.append(table(['Graine','Pas','Admis /31','Séparés /31','Dice médian','Fusions','Identités dédoublées'],rows))
    lines.append('Les sorties R22 sont lues dans les archives publiées. Les identités protégées pour la décision incluent également R24. [Sources R22 et valeurs par provenance](mesures/references-R22.json) · [Union protégée](../r26/temoins.json)')
    lines.append('## Critères aux pas 512 et 768')
    rows=[]
    complete=[str(s) for s in SEEDS if verdict[str(s)]['statut']=='complet']
    for seed in complete:
        v=verdict[seed]
        rows.append([seed,f"{len(v['conserves'])}/{len(v['proteges'])}",len(v['nouveaux_hors_Crigges']),len(v['provenances']),
                     f"{v['fusions_R27']} / {v['maximum_fusions']}",f"{v['dedoublements_R27']} / {v['maximum_dedoublements']}",
                     v['delta_separes'],fmt(v['jaccard']),fmt(v['delta_dice_median'],5),'oui' if v['conforme'] else 'non'])
    lines.append(table(['Graine','Protégés conservés','Nouveaux hors Crigges','Provenances de gain','Fusions / plafond','Dédoublés / plafond','Δ séparés','Jaccard','Δ Dice','Conforme'],rows))
    lines.append('Seuils R26 inclusifs. Au moins six nouvelles identités sur trois provenances, variation absolue des séparés ≤ 1, Jaccard ≥ 0,85 et variation absolue du Dice médian ≤ 0,02. La graine 9301 a un statut final indéterminé. [Décision calculée](mesures/decision.json) · [Gains et pertes par identité](mesures/transitions.json)')
    for seed in complete:
        v=verdict[seed]
        kept=(f"La graine {seed} perd " + ', '.join(v['perdus']) + " parmi les protégés."
              if v['perdus'] else f"La graine {seed} conserve toutes ses identités protégées.")
        gained=', '.join(v['nouveaux_hors_Crigges'])
        lines.append(kept + (' Les nouveaux séparés hors Crigges sont '+gained+'.'
                             if gained else ' Aucun nouveau séparé hors Crigges.'))
    lines.append('## Lecture finale par provenance')
    rows=[]
    for seed in complete:
        for k,r in groups[seed]['768'].items():
            if k.startswith('provenance/'):
                rows.append([seed,k.split('/')[1],r['photos'],r['identites'],r['rpn_50'],r['boites_50'],r['admis'],r['separes'],
                             r['sorties_fusionnees'],r['identites_dedoublees'],fmt(r['dice_median'])])
    lines.append(table(['Graine','Provenance','Photos','Identités','RPN','Boîtes','Admis','Séparés','Fusions','Dédoublés','Dice médian'],rows))
    lines.append('## Groupes et références partielles au pas 768')
    rows=[]
    for seed in complete:
        for k,c in [('condition/grouped','Fûts groupés'),('condition/worn','Supports usés'),
                    ('condition/occlusion','Occultation'),('inventaire/partiel','Inventaire partiel')]:
            r=groups[seed]['768'][k]
            rows.append([seed,c,r['photos'],r['identites'],r['admis'],r['separes'],r['sorties_fusionnees'],r['identites_dedoublees']])
    lines.append(table(['Graine','Groupe','Photos','Identités','Admis','Séparés','Fusions','Dédoublés'],rows))
    lines.append('Les groupes se recouvrent. Les deux supports de zigazou76 appartiennent à une acquisition et les deux vues Crigges partagent un support. [Groupes pour chaque contrôle](mesures/groupes.json)')
    lines.append('## Sorties non appariées et recouvrement du fond')
    rows=[]
    for seed in complete:
        r=curves[seed]['768']
        rows.append([seed,r['non_apparies'],r['non_apparies_faux_fond_verifie'],r['non_apparies_non_resolu'],
                     r['non_apparies_fusion'],r['non_apparies_dedoublement'],r['non_apparies_vide'],
                     f"{r['fond_pixels_union']} / {r['fond_disponible']}",r['fond_pixels_incidence'],r['photos_silencieuses']])
    lines.append(table(['Graine','Non appariées','Faux établis','Non résolues','Fusions','Doublons','Vides','Fond touché en union / vérifié','Incidences fond','Photos silencieuses'],rows))
    lines.append('Les cinq catégories exclusives couvrent toutes les sorties sans attribution admissible. Un pixel de fond peut compter plusieurs fois dans les incidences. Les neuf photos sont occupées et le taux sur témoins vides est indéterminé.')
    lines.append('## Identités non séparées et grille de masque')
    rows=[]
    for seed in complete:
        r=diag[seed]['768']
        rows.append([seed,*[r.get(k,0) for k in ('identites_non_separees','sans_proposition_RPN_50','sans_boite_finale_50',
                    'boite_50_sans_masque_admissible','admissible_mais_recouvre_autrui','arete_separee_non_attribuee',
                    'vide_apres_collage_avec_case_05','separe_entree_inconnue')]])
    lines.append(table(['Graine','Non séparées','Sans RPN à 0,5','Sans boîte finale à 0,5','Boîte à 0,5 sans masque adm.','Admis recouvrant autrui','Arête séparée non attribuée','Sorties vides avec case ≥ 0,5','Séparées parmi 5 sans entrée connue'],rows))
    lines.append('Les colonnes décrivant les identités non séparées peuvent se recouper. La dernière colonne reste une mesure de masque. Les grilles et les masques collés proviennent des mêmes sorties autonomes. [Diagnostic par sortie et identité](mesures/diagnostic.json) · [Plafonds individuels natifs](../r20/plafonds.json)')
    dest.write_text('\n\n'.join(s.rstrip() for s in lines)+'\n')


if __name__=='__main__':
    tables()
