# Bornes et densités recalculées sur les archives

**Nature de la preuve.** Les six maxima, les huit cas PCA et les quatre effectifs d’occupation avaient été observés lors de la revue R25. R26 les a recalculés rétrospectivement. Aucune des quantités ci-dessous ne provient d’un nouvel apprentissage ou d’une inférence. [Calcul](../archives.py) · [Bilan par état et provenance](bilan.json) · [Figure](../figures/bornes.svg) · [Empreintes des entrées](sources.json)

| Bras, graine et détail par photo | Triplets / 26 | Axe parfait / 26 | Masque seul / 26 | Masques admissibles / 31 |
|---|---:|---:|---:|---:|
| [Dice 9301](dice-9301.json) | 4 | 5 | 5 | 6 |
| [Dice 9302](dice-9302.json) | 5 | 5 | 5 | 5 |
| [Dice 9303](dice-9303.json) | 4 | 5 | 5 | 6 |
| [Bloc 8 9301](bloc8-9301.json) | 4 | 6 | 6 | 8 |
| [Bloc 8 9302](bloc8-9302.json) | 4 | 5 | 5 | 6 |
| [Bloc 8 9303](bloc8-9303.json) | 5 | 5 | 5 | 7 |

Sur les neuf photos train, on conserve chacune des douze sorties du pas 768 avant seuil de score. Pour chaque identité, le test de masque utilise les masses natives et les entiers de R20 à 90 % de son plafond individuel. Le triplet exige le masque, au moins 11 sondes d’axe sur 21 et une erreur d’entrée ≤ 10 px en repère 704 pour **le même numéro de requête**. L’oracle d’axe parfait supprime l’exigence sur les sondes. La borne masque seul supprime aussi l’exigence sur l’entrée, en gardant **le même dénominateur de 26**. Les cinq entrées inconnues restent hors de ces trois colonnes. La dernière colonne donne séparément le résultat du masque pour les 31 identités. [Matrices historiques R22](../../r22/mesures/README.md) · [Matrices historiques R24](../../r24/mesures/README.md) · [Plafonds de référence](../../r20/plafonds.json)

Pour chaque photo et chaque graphe, l’appariement maximise d’abord le nombre d’identités attribuées à des requêtes distinctes. Les ex æquo minimisent la distance d’entrée totale pour les deux premières colonnes, puis sur les graphes de masque seul maximisent la somme des fractions de plafond plafonnées à 1. Un parcours ordonné résout les égalités restantes. Les fichiers par état publient toutes les arêtes, les attributions et les absences par différence avec la liste des identités du dénominateur. Le test de revue recalcule indépendamment les cardinalités par programmation dynamique. L’égalité des maxima de deux graphes ne garantit pas l’identité de leurs arêtes ni de toutes leurs solutions. [Affectation historique](../../r19/affecter.py) · [Test reproductible](../../test_r26.py)

## Huit observations « ligne PCA »

Le bras en défaut est R24 pour les six pertes, R22 pour les deux gains. Sur les 96 paires identité–requête, le nombre de masques admissibles est zéro. Le meilleur masque de chaque cas atteint entre **17,45 % et 62,86 %** de son plafond, pour 90 % exigés. Sept des huit cas ont une entrée connue. jrladia nº 3 / 9301 a un masque évaluable et une entrée inconnue. Une meilleure ligne de PCA seule ne rendrait donc possible aucun nouveau triplet sur ces huit observations avec les masques de ce bras. [Les douze fractions de chaque cas](ligne-pca.json) · [Diagnostics R25 par transition](../../r25/transitions.md)

## Cartes d’axe au seuil historique

| Sur 264 requêtes des 22 captures ciblées de R25 | Effectif |
|---|---:|
| Axe positif sur toute la grille | 63 |
| Axe positif sur au moins 99 % des cellules | 170 |
| Axe positif sur au moins 95 % des cellules | 215 |
| Intersection axe–masque identique au masque | 103 |

Les trois premiers ensembles sont emboîtés. On compte les cellules de la **grille entière** de chaque requête, chaque requête ayant le même poids. Un logit nul est positif. Les comparaisons de pourcentages utilisent les effectifs entiers sans arrondi. L’égalité pixel à pixel se vérifie par l’absence de toute cellule de masque positive dont l’axe est négatif. Ces 22 captures portent sur six photos ciblées. Les densités décrivent leurs cartes binaires et ne mesurent ni l’information des logits continus ni la fréquence sur d’autres acquisitions. [Numérateurs et dénominateurs de chaque requête](cartes.json) · [Registre des cartes float32](../../r25/cartes/inventaire.json)

La figure peut être régénérée depuis `bilan.json`. Les taux sur témoins vides et les faux sur un développement indépendant n’ont pas de dénominateur dans cette analyse. [Code de la figure](../figurer.py)
