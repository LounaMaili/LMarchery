# Reproduire le diagnostic R28

Le périmètre est le train R18 aux quatre états indiqués dans le gel. Depuis un checkout de la PR #94, placer le répertoire courant dans `vision-lab/`. La branche contient les NPZ R27, les cartes catégorielles R18, les 36 rasters de supports pleins et toutes les mesures utiles à la revue. Le calcul lit les probabilités et les coordonnées finales archivées. [Plan publié avant calcul](https://github.com/LounaMaili/LMarchery/blob/78a9c6cec6268ff9e92d6fbeec7ae6eb3838b4f6/vision-lab/r28/protocole.md) · [Entrées, tailles et SHA-256](sources.json) · [Sorties R27](../r27/sorties/index.json)

## Rejeu léger depuis GitHub

Utiliser CPython 3.15.2 et les roues épinglées dans le dépôt. Les mesures se recalculent directement depuis les NPZ et les supports pleins publiés. Le répertoire temporaire reçoit les sorties de reproduction. Le test de revue compare le contenu des JSON, des tables et du SVG, ainsi que les pixels de chaque planche. Il vérifie aussi la cardinalité de la borne par énumération indépendante. [Verrou NumPy et OpenCV](../requirements-ci.txt) · [Tests de calcul](../test_r28.py)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
python3.15 -m venv /tmp/lmarchery-r28-lab
PYTHON_LAB=/tmp/lmarchery-r28-lab/bin/python
"$PYTHON_LAB" -m pip install --only-binary=:all: --require-hashes -r requirements-ci.txt
sortie_r28=$(mktemp -d)
"$PYTHON_LAB" -m r28.mesurer "$sortie_r28/mesures"
"$PYTHON_LAB" -m r28.publier "$sortie_r28" --mesures "$sortie_r28/mesures"
"$PYTHON_LAB" -m unittest -v test_r28
```

## Rejeu du collage dans l’environnement historique

Restaurer l’interpréteur CPython 3.9.19 et les roues verrouillées R27, ou réutiliser le venv R27 conservé. Sous la glibc 2.44 du poste, la roue Torch installée porte l’adaptation ELF historique. L’opérateur de collage provient de Detectron2 0.6, avec Torch 1.10.1+cpu et TorchVision 0.11.2+cpu. Sa source installée a pour SHA-256 `50984828affc7669b0d501519fe34cfd1a86b0387c5cb8ea82dada588f5b928a`. Le répertoire des poids, les checkpoints et les images originales sont dispensables pour les deux relectures. [Inventaire de l’environnement](../r27/environnement.json) · [Verrou complet](../r27/requirements.lock) · [Adaptation de la roue installée](../r27/pile-elf.json) · [Collage R28](coller.py)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
# Sur le poste de conservation, depuis vision-lab/ du worktree R28
PYTHON_R27=../../vision-lab-r27/vision-lab/data/r27/env/bin/python
"$PYTHON_R27" -m r28.verifier_collage -v
collage_r28=$(mktemp -d)
"$PYTHON_R27" -m r28.coller "$collage_r28"
diff -qr r28/supports "$collage_r28"
```

Le test synthétique contrôle la décision au seuil exact de 0,5 et les pixels à un coin flottant. Sur le train, le programme colle à la fois les probabilités natives et une grille pleine dans chaque boîte archivée. Il compare les premières aux bits R27, teste leur inclusion dans les seconds et relit chacun des PNG `uint16` écrits. Le bit `1 << q` matérialise Ωq. L’index donne deux SHA-256 par raster, l’un sur le PNG et l’autre sur les pixels natifs `uint16` petit-boutistes dans l’ordre des lignes. [Journal numérique des 432 sorties](supports/index.json)

## Provenance, licences et conservation

Les deux vues Crigges (CC BY 4.0) partagent un support. Les vues de jrladia, Danielle Walquist Lynch, zigazou76 et cristina.sanvito sont sous CC BY 2.0, celle de renatomitra sous CC BY-SA 2.0, celle de Bernard Gagnon sous CC BY-SA 4.0 et celle de 4028mdk09 sous CC BY-SA 3.0. Le registre donne pour chacune son titre, son auteur, sa notice, sa licence et son empreinte. Les planches sont des dérivés des cartes d’annotation et des sorties numériques avec recadrage ou réduction d’affichage, superposition et ajout de légendes. Chaque planche conserve la licence de la vue correspondante. Les photographies originales et privées restent hors de ce dossier. [Crédits et licences individuels](../r26/inventaire.json) · [Provenances et dérivés R18](../r18/sources.json)

Les archives proviennent de Mask R-CNN R50-FPN 3x de Detectron2 v0.6 (Facebook AI Research), ajusté dans R27. Le code de l’éditeur est sous Apache 2.0. Les poids COCO nº 137849600 et leurs dérivés sont documentés sous CC BY-SA 3.0. R28 crée uniquement les supports de contrôle et les pièces de revue. Les inventaires R26 et R27 gardent le nom, le rôle, les tailles, les SHA-256 et l’emplacement des poids, corpus et capsules historiques. Les logs temporaires locaux doublent les vérifications reproductibles. [Inventaire des capsules R27](../r27/inventaire-local.json) · [Versions et licences de l’éditeur](../r27/reproduire.md) · [Inventaire des entrées R28](sources.json)

La CI Vision généraliste exécute les tests R28 depuis les fichiers GitHub dans l’environnement léger. Le job CPU du laboratoire exécute des tests de modèles historiques. Le contrôle Gradle porte sur le projet historique. Le collage Detectron2 et sa parité bit à bit sont relus localement dans l’environnement R27. Les contrôles locaux Mask R-CNN de l’essai initial restent consignés dans leur rapport historique. [Workflow du laboratoire](../../.github/workflows/vision-lab.yml) · [Contrôles Mask R-CNN historiques](../r27/barriere.json)
