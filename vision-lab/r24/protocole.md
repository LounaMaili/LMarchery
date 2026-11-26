# Protocole R24 · Adaptation des convolutions de features[8]

**Question unique.** Sur les neuf photos d’apprentissage R18, l’adaptation des convolutions de `features[8]` améliore-t-elle les triplets tout en conservant les triplets et les entrées des références appariées ? Le motif a été publié séparément avant la préparation des trajectoires. [Recomptage initial au commit 52165a7](https://github.com/LounaMaili/LMarchery/blob/52165a77b4ea386444e6e327d54507df2e0f5fec/vision-lab/r24/reorientation.md) · [Référence R23](https://github.com/LounaMaili/LMarchery/blob/c1044ea17e3c24fd83fff3ce1266582279f5eca9/vision-lab/r23.md)

## Deux bras appariés et une référence secondaire

Le témoin reprend les trois trajectoires **Dice R22, tronc gelé**, déjà exécutées. R21 désigne ici exclusivement le témoin R21 à politique de présence historique. Il sert à définir l’union des entrées à conserver. R22 a été écarté à sa propre évaluation. Le candidat R24 repart, pour chacune des graines **9301, 9302, 9303**, de l’initialisation originale de R22. Le tronc R9 initial est `ordinary-9301.pt`, SHA-256 `14fb7ec245d31274b0481096b7fe878523ae99670bb33236c9201d78ec0dded1`. Les empreintes canoniques des trois réseaux complets initiaux, des deux groupes et des 171 tenseurs gelés figurent dans le contrôle. [Configuration](configuration.json) · [Comparabilité par graine et état](comparabilite.json) · [Poids et sorties R22](../r22/inventaire-local.json)

Les neuf photos publiques du train représentent huit provenances, neuf supports, 31 masques et 31 axes admis, 26 entrées connues et cinq inconnues. Sept photos ont un inventaire exhaustif. Crigges comporte deux vues du même support et zigazou76 deux supports dans une photo. L’évaluation garde les mêmes unités et validités R18. Développement et test réservé sont fermés. Les images privées restent locales. [Sources, auteurs et licences](../r18/sources.json) · [Validités](../r18/validites.json) · [Inventaires](../r21/inventaires.json)

## Paramètres de l’intervention

Les sept paramètres ci-dessous appartiennent aux cinq modules `Conv2d` du bloc. Les convolutions 1 × 1 de réduction et d’expansion du module squeeze-and-excitation apportent leurs poids et leurs biais. Le taux AdamW du groupe est **0,0001**. La tête d’origine garde **0,001** et ses 13 828 paramètres. Le candidat entraîne 42 956 valeurs sur 205 212. Les autres blocs et les projections sont gelés. Tous les modules du tronc et des projections restent en mode évaluation, y compris les BatchNorm avec leurs paramètres affines, moyennes, variances et compteurs. Les autres buffers historiques sont gelés.

| Nom complet | Dimensions | Valeurs |
|---|---|---:|
| `features.8.block.0.0.weight` | 144 × 48 × 1 × 1 | 6 912 |
| `features.8.block.1.0.weight` | 144 × 1 × 5 × 5 | 3 600 |
| `features.8.block.2.fc1.weight` | 40 × 144 × 1 × 1 | 5 760 |
| `features.8.block.2.fc1.bias` | 40 | 40 |
| `features.8.block.2.fc2.weight` | 144 × 40 × 1 × 1 | 5 760 |
| `features.8.block.2.fc2.bias` | 144 | 144 |
| `features.8.block.3.0.weight` | 48 × 144 × 1 × 1 | 6 912 |
| **Total du bloc adapté** | | **29 128** |

AdamW garde β₁ = 0,9, β₂ = 0,999, ε = 10⁻⁸, décroissance = 0,01, `foreach=False`. Une seule norme globale est écrêtée à 5 pour les deux groupes réunis. Architecture R19, douze requêtes, transformations de l’image entière et décodeur historiques. La présence garde les négatifs de fond vérifié R19 et son poids de 0,25. Masque BCE, axe, entrée et Dice ont un coefficient de 1. L’affectation hongroise est recalculée sur les sorties courantes et son coût historique précède le terme Dice. Les régions ignorées et les masses natives restent celles de R22. Une entrée inconnue conserve masque et axe, et sa cible de coordonnée a une masse nulle. [Liste exécutable](adaptation.py) · [Perte et présence héritées](../r22/regle.py)

## Budget et instrumentation

Trois ajustements successifs de 768 mises à jour, dans l’ordre des graines, lot d’une image, avec le [plan exact de R22](../r21/tirages.json). À chaque contrôle **après** 96 et 768 mises à jour, enregistrer réseau, AdamW, RNG, sorties et coûts de neuf photos train. Cela donne 2 304 mises à jour et 54 lectures d’évaluation. La décision utilise le seul pas 768. Un verrou exclut des ajustements simultanés. PyTorch CPU déterministe, quatre threads de calcul, un thread interop, un thread OpenCV et un thread pour OMP et OpenBLAS. Aucun choix de bloc, taux, durée, seuil ou graine après observation.

Avant les ajustements, vérifier les photos, annotations, masques, réseau initial, plans, sorties, journaux, optimiseurs et poids des deux états des témoins, les mesures réémises depuis les sorties et les versions numériques. Contrôler les empreintes du RGB et des cibles réutilisés à l’apprentissage. Le premier contrôle d’environnement a rejeté une installation Torch CUDA avant tout passage expérimental. La vérification et les ajustements utilisent les roues CPU historiques. [Code du contrôle](verifier.py) · [Résultats des vérifications](comparabilite.json) · [Tests des modes, gradients et buffers](../test_r24_cpu.py)

Avant chaque écrêtage, enregistrer la norme globale historique, puis les normes L2 de gradient par groupe avant et après écrêtage. Pour chaque groupe, mesurer le déplacement AdamW réellement appliqué (norme L2 et maximum absolu, composantes converties en double avant soustraction). Calculer également l’écart aux poids initiaux aux deux contrôles. Comparer octet par octet les tenseurs gelés après **chaque** mise à jour, puis vérifier leur empreinte canonique à chaque état. Consigner pertes par tâche, fréquences d’écrêtage, affectations, temps CPU et registre des 54 lectures. Les normes de groupe seules ne mesurent pas l’accord directionnel entre tâches. [Exécution](executer.py) · [Conditionnement des sorties](conditionner.py)

## Mesures et règle de décision

Le seuil de masque binaire reste le logit zéro. Le masque doit atteindre au moins 90 % de son plafond individuel R20 sur les pixels évaluables, l’axe doit couvrir au moins 11 des 21 sondes et l’entrée doit être à 10 px au plus en repère 704. Le triplet R20 impose les trois sur une même requête et l’appariement un à un de cardinalité maximale, puis distance d’entrée minimale. La couverture d’entrée utilise l’appariement R19 un à un avant seuil. Les seuils de score par graine et la présence descriptive à 0,5 sont hérités. Les deux ULP documentées sont réservées au rejeu des distances géométriques sur un autre CPU avec le même côté du seuil de 10 px.

Pour chaque graine, la poursuite exige simultanément au pas 768 :

1. Au moins deux nouveaux triplets par rapport à R22 sur au moins deux provenances distinctes hors Crigges.
2. Conservation de chacun des triplets R22, Crigges inclus.
3. Couverture de chaque entrée de l’union R21/R22 de la graine. Les 26 identités connues en font partie, dont Gagnon nº 11 et Lynch nº 4 à 9302.

Au moins **deux graines conformes sur trois** autorisent une poursuite scientifique. Publier aussi tous les résultats de la troisième. Les différences sont calculées sur les identités stables. Publier les deux contrôles, les champs séparés, les intersections de requêtes, les appariements un à un, les cinq entrées inconnues, les VP/FP/FN natifs, les fusions/dédoublements, les sorties supplémentaires et les statuts avant et après seuil par provenance et photo. Comme les neuf photos sont occupées, le taux de déclenchement sur témoin vide est sans dénominateur. [Critère et mesures](mesures.py) · [Contrôles sur l’union](../test_r24.py) · [Tableaux et figures](presenter.py)

La conclusion porte sur cette politique et ce budget sur le train. R24 s’arrête au bilan et à une recommandation. Le NO-GO Android et les contrats Desktop D3 sont préservés.
