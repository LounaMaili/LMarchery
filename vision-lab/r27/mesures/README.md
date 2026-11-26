# Mesures R27 sur le train

Chaque fichier de contrôle contient les neuf photos, leur provenance et leur support. Pour chaque identité, il donne son statut d’entrée, le Dice et l’IoU avec chaque sortie, ses arêtes admissibles et séparées, ses affectations et sa meilleure boîte à chaque étage. Pour chaque sortie, il donne le score, la boîte, la surface native, la part évaluable, les pixels de fond et d’inconnu, ainsi que le statut d’appariement. [Tableaux de synthèse](../tableaux.md) · [Conventions et seuils](../protocole.md) · [Capsules de toutes les lectures](../sorties/index.json)

| Graine | Pas 0 | Pas 96 | Pas 256 | Pas 512 | Pas 768 |
|---|---|---|---|---|---|
| 9301 | [Mesures](9301-0.json) | Indisponible | Indisponible | Indisponible | Indisponible |
| 9302 | [Mesures](9302-0.json) | [Mesures](9302-96.json) | [Mesures](9302-256.json) | [Mesures](9302-512.json) | [Mesures](9302-768.json) |
| 9303 | [Mesures](9303-0.json) | [Mesures](9303-96.json) | [Mesures](9303-256.json) | [Mesures](9303-512.json) | [Mesures](9303-768.json) |

Les mesures de 9301 après le pas 0 manquent à la suite de l’arrêt avant sa quatrième mise à jour. Le journal contient ses trois pas effectivement appliqués. [Incident documenté](../incident.md)

| Lecture | Données |
|---|---|
| Évolution de la couverture, du masque et des erreurs | [Valeurs par contrôle](courbes.json) · [Valeurs par provenance, condition et inventaire](groupes.json) |
| Changements d’identité et règle R26 | [Gains et pertes](transitions.json) · [Témoin Dice R22 archivé](references-R22.json) · [Verdict par graine](decision.json) |
| Boîtes, séparation et collage | [Diagnostic par identité et sortie](diagnostic.json) · [Code de la mesure native](../mesurer.py) |
| Parité de collage et états locaux | [Masques vérifiés bit à bit, poids, momentum et RNG](relecture-collage.json) |
| Optimisation | [Moyennes par cycle et provenance](optimisation.json) · [Pas 9301](pas-9301.jsonl) · [Pas 9302](pas-9302.jsonl) · [Pas 9303](pas-9303.jsonl) |
| Journaux complets avec indices d’ancres, de RoI et masses positives/négatives | [9301](echantillonnage-9301.jsonl.gz) · [9302](echantillonnage-9302.jsonl.gz) · [9303](echantillonnage-9303.jsonl.gz) |

Les statuts « fusion » et « dédoublement » des sorties non appariées ont un ordre de priorité défini avant l’essai. Les compteurs de décision portent sur toutes les sorties. Le rappel des boîtes indique l’existence d’au moins une boîte à IoU ≥ 0,5 par référence. Les identités peuvent partager la même proposition. Les taux sur témoins vides, les points d’entrée et les triplets sont hors du dénominateur R27.
