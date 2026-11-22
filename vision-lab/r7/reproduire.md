# Reproduire le pilote

Utiliser les scripts depuis `vision-lab` et un répertoire par expérience. La reproduction exacte utilise les décisions de lecture livrées dans l’archive locale `artifacts/r7/corpus-et-lectures.zip`. Elle contient les empreintes, les partitions et les annotations en pixels orientés. Les photographies publiques sont retrouvables auprès des diffuseurs. Les paquets aveugles contenant les pixels sont disponibles sur le poste.

## Environnement CPU

Les essais ont utilisé Linux x86-64, CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, OpenCV 4.13.0.92 et NumPy 2.5.4. Installer les deux listes de dépendances historiques puis les ajouts R7 dans un environnement isolé. La distribution TorchVision et le poids ImageNet proviennent de PyTorch. L’entrée du réseau est un tenseur RGB uint8 NCHW. La normalisation ImageNet est dans le graphe.

```sh
python -m pip install --only-binary=:all: --require-hashes -r requirements-ci.txt
python -m pip install --only-binary=:all: --require-hashes --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-r3-cpu.txt
python -m pip install --only-binary=:all: --no-deps --extra-index-url https://download.pytorch.org/whl/cpu torchvision==0.29.1+cpu Pillow==13.0.0
mkdir -p artifacts/r7 data/r7
curl -fL https://download.pytorch.org/models/mobilenet_v3_small-047dcff4.pth -o artifacts/r7/imagenet.pth
```

## Corpus et supervision

Les scripts de téléchargement reprennent les originaux déjà présents. Garder le répertoire des éléments téléchargés et celui de l’expérience à part. Extraire une copie du paquet de métadonnées dans un répertoire temporaire, puis utiliser les deux lectures et le catalogue Commons gelés. La nouvelle génération des autres fichiers permet de les comparer au manifeste livré.

```sh
unzip -q artifacts/r7/corpus-et-lectures.zip -d /tmp/lmarchery-r7-lecture
mkdir -p data/r7/dvids
cp /tmp/lmarchery-r7-lecture/dvids/*.html data/r7/dvids/
cp /tmp/lmarchery-r7-lecture/commons-source.json /tmp/lmarchery-r7-lecture/external-reading.json data/r7/
python -m r7.acquire data/r7/crigges
python -m r7.prepare data/r7/crigges data/r7/pilot
cp /tmp/lmarchery-r7-lecture/pilot/reading.json data/r7/pilot/
python -m r7.freeze data/r7/pilot data/r7/pilot/reading.json
python -m r7.commons data/r7 --manifest /tmp/lmarchery-r7-lecture/manifest.json
python -m r7.dvids data/r7
python -m r7.commons data/r7 --reading data/r7/external-reading.json
python -m r7.inventory data/r7 --historical ../../vision-lab-r3/vision-lab/data/r3-public-sources ../../vision-lab-r4/vision-lab/data/r4-public-sources ../../vision-lab-r5/vision-lab/data/r5-source-audit ../../vision-lab-r6/vision-lab/data/crigges-audit
```

Si un serveur limite temporairement les requêtes, reprendre après le délai affiché. Pour reproduire les lectures scellées, les fichiers admis doivent correspondre aux empreintes livrées. Les chemins `--historical` désignent les quatre archives **publiques** du poste de référence. Une copie indépendante peut placer ces archives ailleurs et indiquer leurs emplacements. Les catalogues R1–R5 sont aussi vérifiés par SHA-256. Une lecture nouvelle doit être sauvegardée sous une identité d’expérience distincte.

## Apprentissage et épreuve

```sh
export OPENBLAS_NUM_THREADS=1
export OMP_NUM_THREADS=4
python -m unittest -v
python -m r7.train data/r7/pilot artifacts/r7 artifacts/r7/imagenet.pth
R3_CHECKPOINT=../../vision-lab-r3/vision-lab/artifacts/model-r3-cuda/model.pt
python -m r7.evaluate data/r7 artifacts/r7 "$R3_CHECKPOINT" dev
python -m r7.evaluate data/r7 artifacts/r7 "$R3_CHECKPOINT" test
python -m r7.evaluate data/r7 artifacts/r7 "$R3_CHECKPOINT" fit
```

Le seuil `2.0` dans un JSON signifie abstention totale. La sélection enregistre les empreintes des trois checkpoints et des inventaires. L’évaluation les revérifie. Les journaux détaillés locaux contiennent les propositions, les axes, les mesures par photo et la durée du traitement de chaque original. Les résultats agrégés sont reproductibles indépendamment de la durée machine.

La sonde de graphe optionnelle utilise les bibliothèques d’export de R6 et son interprète ONNX Runtime 1.23.2. Elle évalue la faisabilité de conversion du réseau initial sur une tuile artificielle. Exécuter cette sonde avant l’apprentissage pour reproduire la chronologie.

```sh
python -m r7.graph artifacts/r7/graph artifacts/r7/imagenet.pth "$ORT_PYTHON"
python -m r7.report data/r7 artifacts/r7 artifacts/r7/agregats.json
```
