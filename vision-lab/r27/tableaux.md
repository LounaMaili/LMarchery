# Résultats des lectures autonomes sur le train

Les neuf photos occupées contiennent 31 identités évaluables. Les colonnes RPN et boîtes comptent les identités ayant au moins une proposition à IoU ≥ 0,5. Les masques admis et séparés sont appariés un à un. Le Dice médian est calculé sur les 31 identités après attribution de Dice total maximal. [Matrices par photo et identité](mesures/README.md) · [Valeurs des courbes](mesures/courbes.json)

## Contrôles du candidat

| Graine | Pas | RPN /31 | Boîtes /31 | Admis /31 | Séparés /31 | Dice médian | Fusions | Identités dédoublées | Sorties |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9301 | 0 | 10 | 0 | 0 | 0 | 0,158 | 18 | 28 | 108 |
| 9301 | 96 | — | — | — | — | — | — | — | — |
| 9301 | 256 | — | — | — | — | — | — | — | — |
| 9301 | 512 | — | — | — | — | — | — | — | — |
| 9301 | 768 | — | — | — | — | — | — | — | — |
| 9302 | 0 | 10 | 0 | 0 | 0 | 0,158 | 18 | 28 | 108 |
| 9302 | 96 | 27 | 19 | 13 | 13 | 0,854 | 13 | 19 | 108 |
| 9302 | 256 | 30 | 23 | 17 | 17 | 0,927 | 7 | 22 | 108 |
| 9302 | 512 | 30 | 21 | 17 | 17 | 0,948 | 8 | 20 | 108 |
| 9302 | 768 | 30 | 21 | 17 | 17 | 0,945 | 5 | 18 | 108 |
| 9303 | 0 | 10 | 0 | 0 | 0 | 0,158 | 18 | 28 | 108 |
| 9303 | 96 | 28 | 18 | 11 | 11 | 0,760 | 13 | 19 | 108 |
| 9303 | 256 | 29 | 22 | 15 | 15 | 0,891 | 5 | 20 | 108 |
| 9303 | 512 | 30 | 23 | 11 | 11 | 0,854 | 7 | 19 | 108 |
| 9303 | 768 | 30 | 21 | 17 | 17 | 0,903 | 6 | 17 | 108 |

Le tiret correspond à un contrôle indisponible après l’arrêt de 9301. Les trois pas SGD documentés pour cette graine précèdent le contrôle 96. [Incident](incident.md)

## Témoin Dice R22 archivé

| Graine | Pas | Admis /31 | Séparés /31 | Dice médian | Fusions | Identités dédoublées |
| --- | --- | --- | --- | --- | --- | --- |
| 9301 | 96 | 2 | 2 | 0,204 | 46 | 27 |
| 9301 | 768 | 6 | 6 | 0,434 | 51 | 30 |
| 9302 | 96 | 1 | 1 | 0,207 | 60 | 30 |
| 9302 | 768 | 5 | 5 | 0,419 | 52 | 31 |
| 9303 | 96 | 2 | 2 | 0,172 | 46 | 23 |
| 9303 | 768 | 6 | 6 | 0,412 | 54 | 30 |

Les sorties R22 sont lues dans les archives publiées. Les identités protégées pour la décision incluent également R24. [Sources R22 et valeurs par provenance](mesures/references-R22.json) · [Union protégée](../r26/temoins.json)

## Critères aux pas 512 et 768

| Graine | Protégés conservés | Nouveaux hors Crigges | Provenances de gain | Fusions / plafond | Dédoublés / plafond | Δ séparés | Jaccard | Δ Dice | Conforme |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9302 | 6/6 | 11 | 4 | 5 / 39 | 18 / 23 | 0 | 0,700 | -0,00288 | non |
| 9303 | 7/7 | 10 | 5 | 6 / 40 | 17 / 22 | 6 | 0,647 | 0,04896 | non |

Seuils R26 inclusifs. Au moins six nouvelles identités sur trois provenances, variation absolue des séparés ≤ 1, Jaccard ≥ 0,85 et variation absolue du Dice médian ≤ 0,02. La graine 9301 a un statut final indéterminé. [Décision calculée](mesures/decision.json) · [Gains et pertes par identité](mesures/transitions.json)

La graine 9302 conserve toutes ses identités protégées. Les nouveaux séparés hors Crigges sont 24614689941:1, 24614689941:2, 5115614018:1, 5115614018:2, 5115614018:4, 7245527662:1, 7245527662:2, 7245527662:3, 7245527662:4, wc11001401:1, wc11001401:2.

La graine 9303 conserve toutes ses identités protégées. Les nouveaux séparés hors Crigges sont 24614689941:1, 24614689941:2, 5115614018:1, 7245527662:3, 7245527662:4, wc11001401:1, wc11001401:2, wc11001401:4, wc11001401:5, wc71488242:3.

## Lecture finale par provenance

| Graine | Provenance | Photos | Identités | RPN | Boîtes | Admis | Séparés | Fusions | Dédoublés | Dice médian |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9302 | Crigges | 2 | 2 | 2 | 2 | 2 | 2 | 0 | 2 | 0,955 |
| 9302 | jrladia | 1 | 4 | 4 | 4 | 4 | 4 | 0 | 2 | 0,998 |
| 9302 | Danielle Walquist Lynch | 1 | 5 | 4 | 3 | 3 | 3 | 1 | 3 | 0,945 |
| 9302 | zigazou76 | 1 | 2 | 2 | 2 | 2 | 2 | 0 | 2 | 0,981 |
| 9302 | renatomitra | 1 | 3 | 3 | 3 | 3 | 3 | 3 | 3 | 0,988 |
| 9302 | cristina.sanvito | 1 | 1 | 1 | 1 | 1 | 1 | 0 | 1 | 1,000 |
| 9302 | Bernard Gagnon | 1 | 9 | 9 | 2 | 0 | 0 | 0 | 2 | 0,440 |
| 9302 | 4028mdk09 | 1 | 5 | 5 | 4 | 2 | 2 | 1 | 3 | 0,898 |
| 9303 | Crigges | 2 | 2 | 2 | 2 | 2 | 2 | 0 | 2 | 0,966 |
| 9303 | jrladia | 1 | 4 | 4 | 4 | 3 | 3 | 0 | 2 | 0,997 |
| 9303 | Danielle Walquist Lynch | 1 | 5 | 4 | 2 | 1 | 1 | 1 | 3 | 0,790 |
| 9303 | zigazou76 | 1 | 2 | 2 | 2 | 2 | 2 | 0 | 2 | 1,000 |
| 9303 | renatomitra | 1 | 3 | 3 | 3 | 3 | 3 | 5 | 3 | 0,946 |
| 9303 | cristina.sanvito | 1 | 1 | 1 | 1 | 1 | 1 | 0 | 1 | 1,000 |
| 9303 | Bernard Gagnon | 1 | 9 | 9 | 2 | 1 | 1 | 0 | 2 | 0,301 |
| 9303 | 4028mdk09 | 1 | 5 | 5 | 5 | 4 | 4 | 0 | 2 | 0,903 |

## Groupes et références partielles au pas 768

| Graine | Groupe | Photos | Identités | Admis | Séparés | Fusions | Dédoublés |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 9302 | Fûts groupés | 5 | 24 | 10 | 10 | 5 | 13 |
| 9302 | Supports usés | 7 | 28 | 14 | 14 | 5 | 15 |
| 9302 | Occultation | 2 | 11 | 2 | 2 | 0 | 4 |
| 9302 | Inventaire partiel | 2 | 11 | 2 | 2 | 0 | 4 |
| 9303 | Fûts groupés | 5 | 24 | 11 | 11 | 6 | 12 |
| 9303 | Supports usés | 7 | 28 | 14 | 14 | 6 | 14 |
| 9303 | Occultation | 2 | 11 | 3 | 3 | 0 | 4 |
| 9303 | Inventaire partiel | 2 | 11 | 3 | 3 | 0 | 4 |

Les groupes se recouvrent. Les deux supports de zigazou76 appartiennent à une acquisition et les deux vues Crigges partagent un support. [Groupes pour chaque contrôle](mesures/groupes.json)

## Sorties non appariées et recouvrement du fond

| Graine | Non appariées | Faux établis | Non résolues | Fusions | Doublons | Vides | Fond touché en union / vérifié | Incidences fond | Photos silencieuses |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9302 | 91 | 0 | 43 | 5 | 43 | 0 | 0 / 417635 | 0 | 0 |
| 9303 | 91 | 0 | 49 | 6 | 36 | 0 | 5718 / 417635 | 5718 | 0 |

Les cinq catégories exclusives couvrent toutes les sorties sans attribution admissible. Un pixel de fond peut compter plusieurs fois dans les incidences. Les neuf photos sont occupées et le taux sur témoins vides est indéterminé.

## Identités non séparées et grille de masque

| Graine | Non séparées | Sans RPN à 0,5 | Sans boîte finale à 0,5 | Boîte à 0,5 sans masque adm. | Admis recouvrant autrui | Arête séparée non attribuée | Sorties vides avec case ≥ 0,5 | Séparées parmi 5 sans entrée connue |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9302 | 14 | 1 | 10 | 4 | 0 | 0 | 0 | 4 |
| 9303 | 14 | 1 | 10 | 4 | 0 | 0 | 0 | 4 |

Les colonnes décrivant les identités non séparées peuvent se recouper. La dernière colonne reste une mesure de masque. Les grilles et les masques collés proviennent des mêmes sorties autonomes. [Diagnostic par sortie et identité](mesures/diagnostic.json) · [Plafonds individuels natifs](../r20/plafonds.json)
