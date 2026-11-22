# Reproduire R8

Depuis `vision-lab/`, utiliser un dossier distinct pour chaque reproduction. Le répertoire R7 doit contenir le checkpoint entraîné `artifacts/r7/imagenet.pt`, les prédictions `fit-imagenet-predictions.json` et le corpus décrit dans la [reproduction R7](../r7/reproduire.md). La base Git R7 est `33c12ba263bd2cd53be720045c533e54c7a4ee15`. Les commandes ci-dessous supposent deux worktrees voisins et les capsules locales R8 déjà présentes dans `artifacts/r8/`.

## Runtime

Linux x86-64, Ryzen 9 5950X, quatre threads PyTorch CPU. CPython 3.15.2, NumPy 2.5.4, OpenCV 4.13.0, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu et Pillow 13.0.0. Les cinq essais ont duré de 26,9 à 30,0 secondes chacun sur cette machine, évaluations intermédiaires incluses.

```sh
export R7=../../vision-lab-r7/vision-lab
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
python -m pip install --only-binary=:all: --require-hashes -r requirements-ci.txt
python -m pip install --only-binary=:all: --require-hashes --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-r3-cpu.txt
python -m pip install --only-binary=:all: --require-hashes --no-deps --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-r8-cpu.txt
python -m unittest -v
```

## Audit, droits et partitions

La même sélection issue des prédictions sauvegardées alimente le rejeu et la planche annotée. La capsule `corpus-annotations.zip` contient les 44 notices primaires, leurs empreintes, les dix admissions, les décisions d’exclusion, les lectures assistées et les partitions. La capsule `photos-publiques.zip` contient les 44 JPEG acquis et leurs notices associées. Les médias, les positions et les poids restent locaux.

```sh
mkdir -p artifacts/r8-rejeu data/r8-rejeu
python -m r8.audit "$R7" artifacts/r8-rejeu/audit
python -m r8.audit "$R7" artifacts/r8-rejeu/audit --replay
python -m r8.plates "$R7" artifacts/r8-rejeu/audit
unzip -q artifacts/r8/corpus-annotations.zip -d data/r8-rejeu
unzip -q artifacts/r8/photos-publiques.zip 'media/*' -d data/r8-rejeu
python -m r8.provenance data/r8-rejeu --all
python -m r8.control "$R7" data/r8-rejeu/control
python -m r8.corpus data/r8-rejeu "$R7"
python -m r8.provenance data/r8-rejeu --r7 "$R7"
sha256sum r8/protocole.md data/r8-rejeu/corpus.json > artifacts/r8-rejeu/before-learning.sha256
```

À partir des seules métadonnées, la commande `r8.provenance --all` télécharge les JPEG manquants et vérifie leur SHA-256. Une exécution sans `--all` restaure seulement les dix photos utilisées. Une notice gelée et une exposition précise sont requises pour chaque entrée. Pour une nouvelle campagne de découverte, `r8.acquire` lit les fiches DVIDS et `r8.flickr` prend en entrée une réponse JSON Openverse. Les recherches de cette campagne ont utilisé les termes « archery target arrows » et « archery targets », les licences BY/BY-SA/CC0 et la source Flickr. Les résultats de découverte peuvent changer.

Comparer les deux empreintes avant les essais. Le protocole donne `a64d7b04f6dabc090aa7377db3eccfff06b3b3543c29eff88bc75f4dfe6fa349` et le corpus donne `e6bf88fa2a465078d855493a66d667d739efc5d68081e166a2a4124fa81d877f`. Le masque des inconnues est appliqué en coordonnées de la photo de travail à grand côté de 1 408 px. La superposition des tuiles de 320 px vaut 64 px. Le décodeur conserve au plus 96 pics par photo.

## Apprentissage et développement

```sh
python -m r8.experiment data/r8-rejeu "$R7" artifacts/r8-rejeu/train
python -m r8.development data/r8-rejeu "$R7" artifacts/r8-rejeu/train
python -m r8.evidence data/r8-rejeu artifacts/r8-rejeu artifacts/r8-rejeu/mesures.json
```

Les cinq fichiers `.pt` correspondent à A, B et C, graine 8301, puis A, graines 8302 et 8303. Chaque JSON d’essai contient les pertes par pas, les empreintes du checkpoint initial et final, les tirages, les courbes sur photos entières, les occurrences positives, négatives et ignorées par tête, la masse de supervision et la grille des seuils de développement. Les pics externes sauvegardés par ces commandes concernent uniquement le développement. La capsule locale `apprentissage.zip` contient ces fichiers avec la sélection et l’audit. Un environnement de tracé avec Matplotlib 3.11.2 permet de régénérer la figure par `python -m r8.plot r8/mesures.json artifacts/r8/courbes.png`.

## Lecture et adjudication

Le dossier `lecture-test.zip` contient les quatre PNG orientés et un formulaire par lot. La correspondance avec les groupes reste dans `cle-test.json` hors du dossier aveugle. Les décomptes humains reçus peuvent être enregistrés même si les coordonnées manquent. Les options ci-dessous reprennent les décisions explicites du lecteur dans le retour du 22 novembre. Le script vérifie les quatre images, conserve les octets originaux, puis prépare l’annotateur R3 hors ligne pour les deux photos à compléter.

```sh
python -m r8.retour data/r8-rejeu "$LECTURE_RECUE" data/r8-rejeu/retour --empty photo-03 --invalid photo-04
python -m r8.complement data/r8-rejeu data/r8-rejeu/retour/reception.json data/r8-rejeu/comparaison "$EXPORT_01" "$EXPORT_02" --date "$DATE_LECTURE"
```

Pour un formulaire intégral reçu d’un seul tenant, utiliser `python -m r8.adjudicate data/r8-rejeu "$FORMULAIRE" "$SORTIE" --reader "$LECTEUR" --date "$DATE_LECTURE"`. Le dossier de comparaison conserve les deux lectures. L’arbitre examine les désaccords de décompte, de visibilité et de position sur les pixels originaux, puis date la décision avant l’ouverture des prédictions. La photo déclarée invalide figure dans le bilan des exclusions. Le retour humain et les identifiants des lecteurs restent locaux.

## Rejouer le test externe adjugé

La capsule locale `data/r8/lecture-humaine-20261122.zip` contient les exports originaux, les observations comparées et le verdict du 22 novembre. Extraire la capsule hors du dossier aveugle. Le test utilise la référence R7 et le premier checkpoint du bras retenu, avec les seuils déjà publiés dans les mesures de développement. Le script vérifie les empreintes du corpus, de la comparaison approuvée, des photos et des deux checkpoints. Les pics bruts restent locaux.

```sh
unzip -q data/r8/lecture-humaine-20261122.zip -d data/r8-verdict
python -m r8.test_final data/r8-rejeu "$R7" artifacts/r8-rejeu/train r8/mesures.json data/r8-verdict/comparaison/comparaison.json data/r8-verdict/comparaison/verdict.json artifacts/r8-rejeu/test-adjuge
cmp artifacts/r8-rejeu/test-adjuge/synthese.json r8/mesures-finales.json
```

## CI

Le job CPU installe les roues avec SHA-256, charge explicitement les trois méthodes de `test_r7.LearningTests` et vérifie le nombre exécuté et le nombre ignoré. L’absence de TorchVision doit faire échouer cette étape. Le job léger conserve ses exclusions conditionnelles. La validation générale du dépôt utilise `./scripts/dev-check.sh` à la racine. La compilation et le lint Android portent sur l’application existante.
