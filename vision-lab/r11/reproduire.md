# Reproduire et vérifier R11

## Revue du dossier public

Depuis `vision-lab/`, le test `test_r11` relit les coordonnées, scores et références de développement déjà versionnés. Il vérifie les empreintes R9 des six préfixes courts, les deux appariements de chacune des 72 listes, les voisins et affectations de chaque entrée visible, le sort des dix entrées prédéfinies et les deux conditions de décision. Les dépendances numériques de la CI Vision suffisent à ce contrôle. [Dépendances Python épinglées](../requirements-ci.txt) · [Test sur le dossier publié](../test_r11.py)

```sh
python -m unittest -v test_r11
```

Le répertoire [pics](pics/) contient six JSON. Chaque clé identifie une photo. La valeur est la liste ordonnée des paires `[[x, y], score]` dans les pixels de l’image de travail. Le préfixe court correspond aux 96 premières lignes de chaque photo. Les numéros d’entrée suivent les références R10, à partir de 1. [Références et provenance par photo](../r10/tableaux.md)

Dans chaque [relevé par checkpoint](mesures/), les clés `96` et `192` donnent les deux préfixes. `photos` conserve tailles d’image, effectifs, entrées admissibles et rang attribué. `strates` donne les mesures tous points admis, au seuil descriptif 0,50 et au seuil de décision historique. `false + unresolved = unmatched`. `silent` compte les photos sans proposition et `empty_with_points` les témoins vides qui déclenchent. `premier_contact_global` inclut seuil exact, nombre de propositions, appariements, faux et non résolues sur le lot au premier contact. Les cas indéterminés et les strates `worn` et `grouped` suivent R9. Le rang du meilleur voisin et celui du candidat attribué peuvent différer suite à l’appariement un à un.

## Tableaux et figures sans inférence

Le rendu de référence utilise CPython 3.12.14, Matplotlib 3.11.2 et NumPy 2.5.5. Le script lit uniquement les JSON publiés. Il écrit les deux graphiques, leurs valeurs numériques et les tableaux complets. Sur le même poste, les PNG se reproduisent octet pour octet.

```sh
OUT=$(mktemp -d)
MPLBACKEND=Agg python -m r11.publier r11/mesures "$OUT"
cmp r11/tableaux.md "$OUT/tableaux.md"
cmp r11/figures/valeurs.json "$OUT/figures/valeurs.json"
cmp r11/figures/couverture.png "$OUT/figures/couverture.png"
cmp r11/figures/provenances.png "$OUT/figures/provenances.png"
```

## Recalcul des mesures à partir des pics publiés

Le manifeste local de développement R9 sert au recalcul des faux et des non résolus. Les fonctions historiques de mesure et de sélection sont importées avec leur runtime CPU. Cette commande ne lit aucun poids ni aucun pixel et vérifie la parité des six fichiers de pics avant l’analyse.

```sh
export R9=../../vision-lab-r9/vision-lab
OUT_METRICS=$(mktemp -d)
python -m r11.analyser "$R9/data/r9/dev.json" r11 "$OUT_METRICS"
diff -r r11/mesures "$OUT_METRICS"
```

## Exécution de référence sur les photographies

R11 a utilisé CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, NumPy 2.5.4 et OpenCV 4.13.0. Le runtime, les poids, l’orientation, le décodage des pixels BGR, les tuiles, la fusion et le décodeur sont ceux de R9. Le paramètre optionnel `max_peaks` ne modifie que la dernière tranche de la liste triée. La valeur par défaut historique est 96. L’exécution R11 demande 192 et en extrait le préfixe court. [Runtime historique](../r9/reproduire.md) · [Poids, manifeste, photos et capsule avec tailles, emplacements et SHA-256](inventaire.json) · [Crédits et licences](../r9/sources.json)

La commande suivante décrit l’unique exécution de référence. Elle exige une destination neuve et réalise elle-même les 72 passages CPU séquentiels. L’option `--check-only` vérifie toutes les entrées sans effectuer de passage. Les cartes ont servi aux deux préfixes en mémoire. Leur empreinte est conservée par photo et checkpoint. Les six listes publiques permettent de rejouer toutes les mesures sans reproduire cette inférence.

```sh
export R8=../../vision-lab-r8/vision-lab R9=../../vision-lab-r9/vision-lab
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
python -m r11.comparer "$R8" "$R9" artifacts/r11/run
```

[Journal des passages et empreintes de parité](parite.json) · [Précisions d’exécution](protocole.md) · [Rapport](../r11.md)
