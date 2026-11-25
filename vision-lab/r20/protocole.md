# R20 · Capacité d’apprentissage du système R19

**Question.** Le système R19 peut-il produire plusieurs flèches distinctes dont le fût visible, l’axe et l’entrée sont reconnus par la même requête sur une photo d’apprentissage ? Deux opérations, une seule trajectoire ajustée, aucune sélection sur le développement. [Preuves R19](https://github.com/LounaMaili/LMarchery/blob/f10b63fdaa0902e20547afe0d37d626bf2808d9b/vision-lab/r19.md) · [Protocole original](https://github.com/LounaMaili/LMarchery/blob/c8154f931d01a78957547b06c91c026f4ff3ee53/vision-lab/r19/protocole.md) · [Configuration, empreintes et partitions](configuration.json)

## Photo B choisie avant les passages de A

Sur les neuf photos train de R18, exiger au moins deux identités à masque, axe et entrée admis, avec des centres d’entrée distincts sur la grille R19. Pour départager les vues éligibles, calculer pour chaque fût complet sa surface admise ramenée à la grille, divisée par la longueur de ses seuls tronçons admis dans cette grille. Choisir la vue qui maximise le minimum de cette largeur moyenne. En cas d’égalité, préférer le plus grand écart minimal des entrées en cellules, puis l’identifiant de photo par ordre lexical. Cette mesure favorise les fûts qui occupent plusieurs cellules en largeur. Elle utilise les annotations et les dimensions. Les modèles, leurs classements et leurs sorties n’interviennent pas dans le choix. [Classement des cinq vues éligibles](configuration.json) · [Calcul](corpus.py)

La photo retenue est **24614689941, renatomitra**. Les deux cibles complètes sont **24614689941:1** et **24614689941:2**. Leurs largeurs estimées sont de 3,35 et 3,49 cellules, leurs entrées distantes de 10,40 cellules. La photo de travail a 1 024 × 1 024 pixels, l’image réseau 512 × 512 et la grille 256 × 256. Une cellule représente 4 × 4 pixels de travail, soit 2,75 × 2,75 pixels dans le repère 704. Le fût 3, de largeur moyenne 3,68 cellules, conserve son masque et son axe. La jonction voisine de deux perforations est incertaine. Aucune coordonnée de cette entrée ne contribue à la perte ou à l’arrêt. Les trois identités restent dans l’appariement d’apprentissage. Les 7 325 pixels de fût et 49 293 pixels de fond vérifié sont évaluables. Les 991 958 autres pixels gardent la valeur 255. Il n’y a pas de polygone d’exclusion additionnel sur cette vue. [Annotation R18](../r18/annotations/24614689941.json) · [Vue créditée et contours](../r18/figures/24614689941.jpg) · [Notice de renatomitra](https://www.flickr.com/photos/groovenite/24614689941) · [CC BY-SA 2.0](https://creativecommons.org/licenses/by-sa/2.0/)

## Plafond exact et témoin de représentation

Pour chaque identité, noter T son aire native admise. Dans chaque cellule c, compter p(c) pixels de cette identité et n(c) pixels évaluables, autres fûts et fond vérifié inclus. Reprendre exactement l’affectation du centre natif à la grille de R19. Une sortie binaire qui sélectionne des cellules S a pour Dice **2 Σp / (T + Σn)**. Pour un optimum de numérateur avant doublement I et d’aire évaluée N, l’ajout d’une cellule augmente la fraction exactement si p × (T+N) > I × n. L’optimum se trouve donc parmi les préfixes triés par p/n décroissant. Grouper les rapports égaux avec des fractions exactes et comparer les préfixes par produits entiers. En cas d’égalité, garder la plus petite aire. Les cellules sans pixel évaluable ont un effet nul. Vérifier par le certificat entier que le choix optimal strict redonne exactement I et N. Les histogrammes (p, n, effectif) des cellules à p positif, les dénominateurs, les aires et les plafonds des 31 masques sont publiés. [Plafonds bruts et certificats par identité](plafonds.json)

| Flèche sur la vue B | T, pixels natifs | Intersection optimale | Aire de sortie évaluée | Cellules retenues | Dice maximal | Dice exigé pour le triplet |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 2 650 | 2 650 | 2 650 | 204 | 1,000 | 0,900 |
| 2 | 1 984 | 1 984 | 1 984 | 160 | 1,000 | 0,900 |
| 3 | 2 691 | 2 691 | 2 691 | 211 | 1,000 | Entrée exclue |

Un plafond de 1 est possible ici parce que les pixels incertains n’entrent pas dans le Dice. Il ne garantit pas un contour exact sur la totalité de la photo. Pour contrôler la faisabilité des trois conditions ensemble, construire une sortie par identité au niveau de la grille. Dessiner chaque tronçon annoté séparément sur les centres arrondis, l’ajouter au masque optimal, appliquer le décodeur de fragments R19 au tracé et placer le pic sur le centre de grille le plus proche de l’entrée. Sur les identités 1 et 2, ce témoin atteint un Dice de 1 pour chacune, **21 et 20 sondes d’axe sur 21**, et des erreurs d’entrée de **1,24 et 1,15 px**. Il démontre la faisabilité du format de sortie sous les exclusions R18. Il ne mesure pas la capacité de la tête à produire ces champs.

## Opération A · Ce que les checkpoints R19 ont appris

Charger en lecture stricte chacun des trois poids finaux R19. Effectuer un passage sur chacune des neuf photos train dans son orientation de travail R18, soit **27 passages maximum**, douze requêtes par passage. Appeler le réseau sur la photo seule. Le prétraitement, le tronc et les projections, les douze requêtes, le score, les masques binaires, les fragments et les coordonnées proviennent du code R19. Examiner ensuite les sorties avec les annotations. Chaque instance est physique et locale à sa photo. Les 31 masques, 31 axes et 26 entrées admis forment 26 triplets complets. Les onze identités documentaires restent exclues des cibles. Les neuf vues sont occupées. Le taux de déclenchement sur témoins vides n’a pas de dénominateur dans R20. [Sources et droits](../r18/sources.json) · [Architecture R19](../r19/configuration.json) · [Exécution A et B](operations.py)

Publier les douze candidats et mesurer tous les contacts, puis les contacts conservés aux seuils **0,3804555184458067**, **2** (abstention) et **0,8823924768829947** des graines 9301, 9302 et 9303. Conserver les ex æquo. Aucun réglage de seuil. Apparier à 10 px en repère 704 selon R19. Faute d’inventaire complet des positions d’entrée, ou dans une zone ignorée pour l’entrée, compter l’excédent comme non résolu. Pour le train, une position incertaine laisse donc non certifiées les propositions non appariées de la vue même quand l’inventaire physique de fûts est complet. L’état binaire des masques suit les pixels évaluables R18.

Calculer séparément le Dice avec l’affectation maximale de R19, les axes un à un sur toutes les sorties par le critère 11/21, et l’appariement des entrées. Publier leurs trois numéros de requête pour chaque référence. Calculer aussi l’appariement conjoint défini ci-dessous sur la liste entière, puis sur les seules requêtes passant le seuil hérité. Pour chaque requête, conserver score, position, présence, nombre d’axes fragmentés, affectations indépendantes et conjointes, fûts recouverts et éventuelle affectation par le coût d’apprentissage. Une sortie hors triplet peut correspondre à un fût partiel. Elle n’est pas automatiquement un faux. Compter les fusions et les dédoublements avec la règle R19 de 25 % de chaque aire de référence, les coordonnées identiques dans la photo et l’excédent de coordonnées (12 moins le nombre de positions distinctes). La règle de négatif de présence peut être observée sur A, sans mise à jour.

## Opération B · Une trajectoire de mémorisation

Repartir du checkpoint R9 `ordinary-9301` d’empreinte SHA-256 `14fb7ec245d31274b0481096b7fe878523ae99670bb33236c9201d78ec0dded1`. Initialiser la nouvelle tête par la procédure R19 à la graine **9301**. Garder les 205 212 paramètres totaux, les 13 828 entraînables, les BatchNorm figées et les trois champs. Une mise à jour lit la photo renatomitra entière avec toutes ses cibles actives. Même appariement hongrois partiel, mêmes coûts, même normalisation et même présence surnuméraire strictement établie que dans R19. La perte vaut masque + axe + entrée + 0,25 × présence. AdamW à 0,001, β = (0,9, 0,999), ε = 1e−8, décroissance 0,01, écrêtage de la norme à 5, lot une photo, aucune augmentation, aucun autre poids ou graine. Un seul processus lourd, CPU à quatre threads Torch, un thread interop, un OpenCV et un BLAS. [Pertes originales](../r19/supervision.py) · [Décomposition vérifiée par requête](instrumenter.py)

Contrôler les états après **0, 96, 256, 512, 1 024** mises à jour. Un passage avant fournit les trois champs utilisés par le contrôle et par l’éventuelle mise à jour suivante. Le réseau est le même en lecture et en ajustement pour cette photo, sans dropout et avec les BatchNorm figées. À un état terminal, la norme de gradient est mesurée mais l’optimiseur ne fait aucun pas supplémentaire. L’arrêt intervient **au premier contrôle** qui réussit pour les deux flèches complètes, sinon après 1 024 mises à jour. Aucun choix rétrospectif.

### Condition conjointe identique pour A et B

Une arête identité–requête existe uniquement si **la même requête** satisfait les trois conditions suivantes.

1. Son Dice sur les pixels évaluables atteint au moins 90 % du plafond de cette identité. Pour une intersection observée i et une aire de sortie a, tester exactement 10 × i × (T+N) ≥ 9 × I × (T+a).
2. Au moins 11 des 21 sondes R19 sont compatibles avec les fragments de cette requête. Les sondes sautent les intervalles exclus. Distance au segment fini ≤6 px et angle non orienté ≤20°.
3. Le centre du maximum du champ d’entrée de cette requête est à ≤10 px de l’entrée admise dans le repère R19.

Maximiser la cardinalité du graphe biparti sur les triplets complets, avec une seule attribution par requête et par flèche. À cardinalité égale, minimiser la somme des distances d’entrée puis utiliser l’ordre déterministe de R19. **B réussit uniquement si les deux références complètes ont chacune une requête distincte admissible avant seuil.** La mesure au seuil hérité 9301 est descriptive sur B. L’entrée exclue de la flèche 3, les requêtes hors appariement conjoint et le nombre de doublons n’interviennent pas dans l’arrêt. [Appariements et matrice des douze requêtes par identité](mesurer.py)

### Archives et séparation des observations

À chaque contrôle réalisé, conserver le poids exact, l’état AdamW, l’état du générateur CPU et leurs empreintes. Archiver les **36 cartes de logits float32** à pleine résolution (trois par requête), les douze logits de présence, les coordonnées, les scores et les masques binaires non exclusifs en plan uint16. Publier pour chaque identité la perte par tâche admise, sa requête d’apprentissage, le coût de l’affectation et les matrices d’évaluation distinctes. Pour les présences surnuméraires, lister les requêtes explicitement négatives à l’état observé. Compter séparément les négatifs réellement utilisés sur les mises à jour. Un gradient moyen bas ou un logit élevé ne renseigne pas sur un négatif absent.

Conserver toutes les pertes et les normes globales avant écrêtage à chaque mise à jour, ainsi que les normes par famille de paramètres aux contrôles. Pour chacune des 66 paires de requêtes, documenter sur chaque champ l’écart quadratique moyen en racine, l’écart absolu maximal, la corrélation spatiale centrée et l’égalité de la cellule de maximum. Pour l’entrée, ajouter la distance de variation totale des softmax spatiaux. La corrélation est indéfinie si l’un des deux champs est constant. Deux distributions peuvent partager un maximum tout en ayant une forme différente. Ces statistiques descriptives n’ajoutent aucun seuil de succès. [Statistiques des champs](champs.py) · [Rejeu des mesures sans modèle](rejouer.py)

Les contrôles préalables vérifient l’optimum de Dice contre une énumération exhaustive de grilles mixtes, la concurrence entre requêtes, le refus d’un triplet assemblé à partir de trois requêtes, les maxima identiques avec des champs différents et la décomposition des pertes R19. Les dénominateurs et la construction géométrique sont établis avant le premier passage A ou B. [Tests de mesure](../test_r20.py) · [Test des pertes](../test_r20_cpu.py)

Exécution d’origine depuis `vision-lab/`, dans l’environnement CPU déjà épinglé. Le dossier de chaque opération doit être neuf.

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m unittest -v test_r20 test_r20_cpu
python -m r20.operations A ../.. artifacts/r20/essai
python -m r20.operations B ../.. artifacts/r20/essai
python -m r20.rejouer data/r20/rejeu --archives artifacts/r20/essai
```

Le contrôle s’arrête au bilan et à une recommandation. Les performances sur les neuf photos train et sur une seule photo mémorisée auront des portées distinctes. Aucune nouvelle inférence sur le développement ou le test réservé, aucune nouvelle collecte et aucune qualification Android. Le NO-GO Android et les contrats Desktop D3 restent en vigueur.
