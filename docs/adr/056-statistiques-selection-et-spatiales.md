# ADR 056 — Statistiques multi-volées et répartition spatiale

Statut proposé. Le package JVM `com.lmarchery.core.scoring.grouping` étend
l’[ADR 051](051-groupements-et-dispersion.md) pour les sélections explicites
décrites dans l’ADR Desktop 006. Les deux fonctions obéissent au même périmètre
et réutilisent les décisions archivées et le moteur géométrique existant.

## Périmètre du calcul

- `calculateSelectionGrouping(contexts, missPolicy)` produit des mesures par
  volée, pour le nuage complet et en moyenne par volée.
- `calculateSpatialDistribution(contexts, grid, missPolicy)` classe les
  impacts sélectionnés et rend les effectifs d’une grille, des axes et des
  secteurs. Le calcul spatial peut être demandé seul.

Le consommateur transmet uniquement les `EndScoringContext` choisis. Chaque
appel accepte une configuration exacte et un seul statut archivé, même si
certaines volées sont vides. La validation du moteur mixte rejette les UUID
répétés d’une volée ou d’un impact, ainsi que les collisions d’identités.
Le résultat a un `scope` absent uniquement lorsque la liste d’entrée est vide.
La politique M figure dans le résultat, quelle que soit la taille de la sélection.

Le consommateur conserve les références d’archive et établit la provenance
avant le calcul. Des archives indépendantes s’analysent par appels séparés,
y compris lorsque deux UUID sont égaux. Un consommateur qui forme une
sélection commune en assume explicitement la provenance et le traitement des
recoupements. Le moteur voit les contextes et leurs identifiants, sans pouvoir
établir si deux identifiants différents représentent un même tir.

Les calculs utilisent les points courants de `End.activeImpacts`. Les M actifs
participent par défaut et `EXCLUDE_MISSES` s’appuie sur la décision finale
`isMiss`. `GroupingImpactCounts` indique les impacts retenus, les M inclus,
les M exclus et les annulations à part. Les coordonnées, profils, scores,
traces et corrections archivés restent des données de lecture. Le classement
spatial repose sur les coordonnées. Les scores proviennent des décisions
finales selon les API de score existantes.

## Mesures et pondération

| Résultat | Périmètre et dénominateur |
|---|---|
| `ends` | Une mesure `EndGrouping` par volée dans l’ordre fourni, en incluant les volées vides. |
| `pooledNormalized.center` | Poids égal pour chaque impact retenu. Les décalages signés et la distance du centre moyen à l’origine suivent l’ADR 051. |
| `pooledNormalized.dispersion` | Écarts-types de population par axe et rayon moyen autour du centre global. Le diamètre est la plus grande distance entre deux impacts retenus, y compris de volées différentes. |
| `endMeansNormalized.center` | Poids égal pour chaque volée ayant un centre. Les deux coordonnées sont moyennées séparément, ainsi que les distances des centres de volée à l’origine. |
| `endMeansNormalized.dispersion` | Poids égal pour chaque volée ayant une dispersion. Les écarts-types, rayons moyens et diamètres sont chacun moyennés. |

L’effectif du nuage est `counts.retainedImpactCount`. Avec zéro impact,
son centre et sa dispersion sont absents. Avec un impact, seul son centre
est disponible. Les moyennes par volée indiquent leur propre
`contributingEndCount`. Un bloc de moyennes absent correspond à zéro volée
contributrice. Une volée à un impact contribue aux moyennes de centrage.
Deux impacts coïncidents, même de volées différentes, donnent une dispersion
globale nulle. Deux volées à un impact donnent donc une dispersion du nuage
sans moyenne des dispersions par volée.

La moyenne des distances à l’origine porte sur les distances des centres de
volée. La distance du centroïde global porte sur les impacts retenus. Le
diamètre global est une mesure du nuage. La moyenne des diamètres porte sur
les volées qui disposent d’un diamètre. Les types et les dénominateurs permettent
de présenter ces indicateurs séparément.

`pooledMeasurementsIn` et `endMeansIn` emploient le rayon nominal du profil
exact comme facteur de conversion de l’unité normalisée vers les cm ou mm.
Ces mesures s’interprètent sur le blason nominal. Les calculs utilisent des
`Double` et une sommation compensée. L’ordre de présentation des volées suit
le consommateur. L’ordre de sommation numérique des moyennes suit les UUID
pour garder des résultats stables lors d’une permutation de la sélection.

## Répartition spatiale

`SpatialGrid` couvre `[-1, 1] × [-1, 1]` dans le repère du blason. Les indices
commencent à zéro. Les colonnes vont vers la droite et les lignes vers le bas.
Chaque dimension couvre de 1 à 256 cellules. La résolution proposée par défaut
est 32 × 32 et la surface d’une cellule est `4 / (colonnes × lignes)`, en
rayons de blason au carré.

Le cercle nominal de rayon 1, bord inclus, alimente la grille. Chaque
intervalle est fermé sur son bord gauche ou supérieur et ouvert sur son bord
droit ou inférieur. Le bord extrême +1 est inclus dans la dernière cellule.
Les points extérieurs au cercle, y compris dans les coins de la grille
carrée, sont recensés dans `outsideImpacts` avec leurs UUID de volée et
d’impact et leur `NormalizedPoint` archivé. L’ordre suit les volées et
impacts transmis. Aucune transformation ne déplace un impact vers une cellule.
La frontière spatiale est calculée à partir des coordonnées `Float` exactes.
Près du bord, une décision finale ou une distance de score arrondie peut donc
diverger du classement spatial affiché.

Pour une cellule, la fréquence entière est son nombre d’impacts. Le
pourcentage divise ce nombre par tous les impacts retenus, hors blason
compris. Les pourcentages des cellules peuvent donc totaliser moins de 100 %.
La densité divise la fréquence par la surface rectangulaire entière de la
cellule, y compris sur le bord du cercle. C’est un nombre d’impacts par rayon
de blason au carré. Une carte doit garder la même résolution et la même
convention de surface pour comparer ces densités. Avec zéro impact retenu, les
comptages valent zéro et les pourcentages et densités sont absents. Avec une
sélection non vide d’impacts retenus, une cellule vide a une densité nulle.
Le résultat est un histogramme spatial brut, sans lissage ni extrapolation.

Les axes `x` et `y` répartissent tous les impacts retenus par signe.
Les points sur l’axe ont une catégorie propre. Les secteurs sont huit
intervalles de 45 degrés orientés dans le sens horaire depuis la droite.
Chaque secteur inclut son rayon initial et exclut le suivant. Les axes,
secteurs et comptages intérieur/extérieur emploient le même dénominateur.
L’origine possède un comptage propre et complète la somme des huit secteurs.
Les zéros signés appartiennent à l’axe. Le découpage préserve les frontières
des cellules et du cercle même pour les plus petites coordonnées `Float`.

## Coût et intégration

Pour `n` impacts archivés en entrée et `v` volées, la validation d’identité
prend un temps et un espace linéaires en `n + v`. Le regroupement trie le
nuage retenu et chaque sous-nuage avant la même mesure d’enveloppe et
d’antipodes que l’ADR 051. Avec `p` impacts retenus, le calcul géométrique coûte
`O(n + p log p + v log v)` en temps et `O(n + v)` en mémoire, en comptant les
moyennes et la validation. La distribution spatiale coûte `O(n + v + c)` en
temps et `O(n + v + c)` en mémoire de travail pour `c` cellules. Les cellules
sont stockées dans un tableau d’entiers de 65 536 entrées au plus, soit
256 Kio pour ses compteurs. Le rapport spatial conserve aussi les références
aux positions hors blason et les effectifs par catégorie. Les dimensions de
grille et les indices sont vérifiés avant l’accès. Les effectifs de sélection
sont bornés par le type `Int` et leur addition est vérifiée.

Desktop D2f peut présenter la géométrie et la grille sur demande, hors du fil
d’affichage, et afficher séparément les séries de chaque archive et statut.
Les API restent des fonctions JVM pures. Elles n’ajoutent ni champ d’archive
ni schéma de stockage et ne sont déclenchées par aucun parcours Android de tir.
