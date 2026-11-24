# Reproduire R16

Base R15 `2c446d5ee28d9e24272d6565d57e5262772cb956`. Le [protocole, les exemples et la fonction de fit](https://github.com/LounaMaili/LMarchery/commit/8e288bc1ce1a41e641af004edd1334ca36286464) ont été publiés avant l’ajustement. Les [deux portes de données](verification.json) ont été recalculées. La valeur `affcc38c…` est le SHA-256 de la liste canonique des empreintes des onze pièces du manifeste R15. Chaque pièce a aussi son empreinte et sa taille. [Scellement source](../r15/manifeste-scelle.json)

## Revue publique

Toutes les commandes partent de `vision-lab/`. Les tables, paramètres, mesures, prédictions et figures sont consultables dans GitHub. Recalculer la décision, les seuils R9 et les courbes depuis les listes de candidats publiées ne demande ni photographie ni checkpoint. Le runtime scientifique R13 fournit les dépendances importées par les fonctions historiques R8/R9. Python standard suffit pour reconstruire les tableaux.

```sh
OUT=$(mktemp -d)
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m unittest -v test_r16
python -m r16.mesurer r16/predictions "$OUT/mesures"
diff -r r16/mesures "$OUT/mesures"
python -m r16.tableaux "$OUT/mesures" "$OUT/tableaux.md"
cmp r16/tableaux.md "$OUT/tableaux.md"
# Dans le runtime graphique indiqué ci-dessous
MPLBACKEND=Agg python -m r16.figurer "$OUT/mesures" "$OUT/figures"
for p in r16/figures/*.png; do cmp "$p" "$OUT/figures/${p##*/}" || exit 1; done
```

Les fichiers de prédictions contiennent une entrée par photo. `segments` est ordonné par rang de sortie R13, à partir de zéro. Chaque ligne contient le rang documentaire par longueur, les deux bouts XY au repère 704, la probabilité du vérificateur et la décision. Dans `candidats`, chaque ligne contient la coordonnée XY dans l’image de travail orientée, le score thermique H, le score final B et le score final V. Le champ `echelle` relie ce repère au repère 704. Le score H et la position sont partagés entre les bras. La conversion des axes à 12 chiffres significatifs retrouve le CSV R15. Les appariements et les bonus utilisent les coordonnées en double précision publiées dans les prédictions. [Listes des six poids](predictions/) · [Index des matrices R15](../r15/caracteristiques/index.json)

Dans les mesures par checkpoint, `references` donne les voisins stricts et les voisins dont le bout touche l’entrée à 10 px. Une liste vide indique zéro voisin et `null` une entrée inconnue. Les compteurs `strict_1a1` et `bout_strict_1a1` appliquent séparément la capacité un selon R14. `bras` compte les segments opérationnels. Le champ `non_resolus_occupes` regroupe les statuts documentaires non lus et incertains. Le sous-compteur `dont_non_resolus_avec_voisin_strict` isole ceux qui ont une compatibilité géométrique avec un trait. Chaque prélèvement hors sortie est publié avec sa lecture et son rang dans `documentaires_hors_sortie`. Les strates par condition peuvent se recouper. [Fonctions R14](../r14/mesurer.py) · [Mesures détaillées](mesures/)

Dans les résultats contacts, `known`, `matched`, `unmatched`, `false`, `unresolved`, `silent` et `empty_with_points` dénombrent respectivement les entrées visibles, les entrées appariées, les propositions non appariées, les faux établis, les non résolues, les photos sans proposition et les témoins avec proposition. Les points `historique`, `local_R9` et `couverture` correspondent au seuil B R13, au seuil choisi sur ces onze photos et au seuil nul. Les valeurs entières et le seuil exact figurent aussi dans le CSV, pour chaque provenance, pour les groupes, les supports usés et les vides. Le seuil 2 signifie abstention. [Code d’évaluation](mesurer.py) · [Règle R9](../r9/experiment.py) · [Courbes numériques](mesures/courbes.csv)

## Ajustement de référence et environnements

L’ajustement de référence a utilisé Linux x86-64 et CPython 3.12.14. Ses cinq roues avec empreintes sont scellées pour NumPy 2.2.6, SciPy 1.15.3, scikit-learn 1.7.2, joblib 1.5.2 et threadpoolctl 3.6.0. Le JSON du résultat suffit à appliquer le vérificateur avec NumPy. Les tests publics recalculent les statistiques du train, contrôlent les masses et le gradient des coefficients publiés et vérifient les décisions des six checkpoints. La commande du fit unique est consignée ici pour la reproduction dans une destination neuve. Elle refuse d’écraser un modèle existant.

```sh
# Dans un environnement CPython 3.12 x86-64 distinct
python -m pip install --only-binary=:all: --require-hashes -r r16/requirements-fit.txt
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m r16.ajuster 8e288bc1ce1a41e641af004edd1334ca36286464 "$OUT/verificateur.json"
cmp r16/verificateur.json "$OUT/verificateur.json"
```

Le runtime d’inférence et de mesures est CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, OpenCV 4.13.0 et NumPy 2.5.4. Le rendu des PNG utilise CPython 3.12.14, Matplotlib 3.11.2 et NumPy 2.5.5. La machine de mesure est un AMD Ryzen 9 5950X. Le CPU additionnel inclut imputation, standardisation, produit scalaire, sigmoïde et comparaison au seuil. Il exclut extraction, lecture, assemblage du tableau de caractéristiques et reclassement des contacts. Le coût dépend de la machine. [Roues du fit](requirements-fit.txt) · [Versions et empreintes scientifiques](../r8/reproduire.md) · [Versions du rendu](../r13/reproduire.md)

## Cartes et archives locales

L’inventaire inclut les six checkpoints, les deux capsules R15, la capsule documentaire R14, le ZIP des cartes R16 et les roues du fit. Il donne taille en octets, emplacement et SHA-256. La capsule R16 contient les 66 NPZ par photo–checkpoint et leur index. Leurs empreintes ainsi que celles de chaque canal sont publiées. Les originaux et les dérivés photographiques sont référencés dans les registres par source et licence. R16 publie des tracés numériques et des agrégats. [Inventaire local](inventaire-local.json) · [Index individuel des cartes](inventaire-cartes.json) · [Sources et droits R15](../r15/sources.md) · [185 acquisitions antérieures](../r14/inventaire-pixels.json) · [Dix acquisitions supplémentaires](../r15/inventaire-local.json)

Avec les acquisitions publiques et les poids restaurés aux emplacements archivés, la préparation peut être contrôlée sans inférence. Depuis une autre arborescence, la restitution des cartes et le reclassement acceptent `--worktrees /chemin/vers/les/worktrees` en conservant les sous-dossiers historiques. La reproduction des mesures ci-dessus utilise uniquement le dépôt.

```sh
python -m r16.preparer "$OUT/preparation"
for f in verification effectifs-fit candidats-fit exemples-fit; do
  cmp "r16/$f.json" "$OUT/preparation/$f.json" || exit 1
done
# Reprendre le cache clos, sans relancer le réseau
unzip -q artifacts/r16/cartes-developpement.zip -d "$OUT/cartes"
python -m r16.cartes "$OUT/cartes"
python -m r16.comparer "$OUT/cartes" "$OUT/predictions"
```

Si la capsule manque dans une reproduction séparée, `python -m r16.cartes "$OUT/cartes"` sur un dossier neuf reconstruit les onze couples de chaque poids, en série. Il réserve un marqueur avant chaque passage, refuse de répéter une inférence interrompue après réservation et vérifie la parité des cartes tube R15. Un cache scellé est vérifié en lecture. La restitution exécutée en R16 a produit 66 passages et zéro passage sur test. Les temps de calcul peuvent varier lors d’un reclassement. Pour vérifier le résultat scientifique d’un rejeu, comparer les prédictions en retirant uniquement la clé `cpu_mediane_ns`. Le recalcul des mesures depuis les prédictions publiées conserve également le chronométrage original. [Restitution](cartes.py) · [Application des deux bras](comparer.py) · [Création de la capsule](inventorier.py)

## Historique de publication

Le gel du 24 novembre 2026 précède le fit et la lecture des résultats. La comparaison porte sur la première et unique régression ajustée. Aucun résultat R14 ou R15 n’a été remplacé. Les annotations, la règle de sélection, le seuil du vérificateur et la règle de décision conservent la version du gel. La PR consigne le SHA publié, le checkout de CI et les contrôles exécutés. [Gel R16](https://github.com/LounaMaili/LMarchery/commit/8e288bc1ce1a41e641af004edd1334ca36286464) · [PR en brouillon](https://github.com/LounaMaili/LMarchery/pull/81)
