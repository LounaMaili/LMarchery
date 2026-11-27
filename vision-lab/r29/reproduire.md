# Reproduire R29

Les commandes partent de `vision-lab/`. Le [gel publié avant le premier passage](https://github.com/LounaMaili/LMarchery/blob/710971e44f7ac8e059a8a917777b1e191eb7d267/vision-lab/r29/protocole.md) contient la procédure, les réglages et la convention de mesure. Il existe deux chemins de vérification.

## Depuis GitHub, sans modèle

Les 18 NPZ contiennent les observations d’un passage chacune. Les intersections natives contiennent les nombres de pixels positifs de chaque identité pour chacun des 1 000 ancêtres et des trois géométries. Ils permettent de recalculer les 108 graphes d’appariement et les valeurs de tous les couples. Le train R18, les plafonds et les 18 archives finales R27 sont déjà présents dans le dépôt. [Traces exactes et empreintes](traces/index.json) · [Intersections et empreintes](intersections/index.json) · [Réglages et versions des sources](configuration.json)

Utiliser CPython 3.15.2 avec les versions et empreintes du laboratoire. Le script de mesure vérifie le tri, les suppressions et les rangs avec les coordonnées modèle, les parités aux archives, les seuils entiers, les affectations, les certificats et les témoins R28 finaux. Le test indépendant de cardinalité utilise des chemins augmentants. Le test de publication régénère les JSON, les tableaux et les SVG dans un répertoire temporaire. [Verrou Python](../requirements-ci.txt) · [Tests de rejeu](../test_r29.py)

```bash
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
python3.15 -m venv /tmp/lmarchery-r29-lab
PYTHON_LAB=/tmp/lmarchery-r29-lab/bin/python
"$PYTHON_LAB" -m pip install --only-binary=:all: --require-hashes -r requirements-ci.txt
sortie_r29=$(mktemp -d)
"$PYTHON_LAB" -m r29.mesurer "$sortie_r29/mesures"
"$PYTHON_LAB" -m r29.publier "$sortie_r29" --mesures "$sortie_r29/mesures"
"$PYTHON_LAB" -m unittest -v test_r29
```

Chaque JSON de propositions présente ses noms de colonnes puis au plus 250 lignes en ordre RPN. Les quatre pages par photo donnent les coordonnées modèle et natives, les logits RPN et RoI, les deltas, la probabilité originale de premier plan et la décision observée. Les rangs commencent à zéro. Le score de classe archivé est différent du logit RPN et du rang monotone utilisé pour départager les scores à la NMS. Le seuil de suppression porte sur l’IoU des boîtes bornées dans le repère modèle. Les 62 matrices par identité donnent T, I et le Dice idéal calculé pour chaque j. [Index des fichiers lisibles](mesures/README.md)

## Collage local sans passage dans le réseau

L’environnement CPU R27 est CPython 3.9.19, Torch 1.10.1+cpu, TorchVision 0.11.2+cpu, Detectron2 0.6, NumPy 1.23.5 et OpenCV 4.8.1. R27 documente les roues et l’adaptation ELF du poste. Le module `mask_ops.py` a pour SHA-256 `50984828affc7669b0d501519fe34cfd1a86b0387c5cb8ea82dada588f5b928a`. [Environnement et origine des roues](../r27/environnement.json) · [Verrou complet](../r27/requirements.lock) · [Adaptation ELF](../r27/pile-elf.json)

```bash
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
PYTHON_R27=../../vision-lab-r27/vision-lab/data/r27/env/bin/python
"$PYTHON_R27" -m r29.controle_local -v
raster_r29=$(mktemp -d)
"$PYTHON_R27" -m r29.supports "$raster_r29"
diff -qr r29/intersections "$raster_r29"
"$PYTHON_R27" -m r29.verifier_supports "$raster_r29/controle-collage.json"
diff r29/controle-collage.json "$raster_r29/controle-collage.json"
```

Le calcul conserve une seule fenêtre de collage de boîte à la fois. La comparaison aux 216 cartes finales Ω de R28 porte sur tous les pixels. Un contrôle complémentaire utilise l’API publique `paste_masks_in_image` au lieu d’appeler directement la primitive de fenêtre CPU et relit les trois géométries aux origines 0, 499 et 999 de chaque photo–graine. Les 162 boîtes ainsi contrôlées donnent les mêmes pixels et les mêmes intersections. [Contrôle complémentaire local](controle-collage.json) · [Empreintes du calcul exhaustif](intersections/index.json)

## Passage instrumenté déjà consommé

Le processus a utilisé strictement les deux états R27 au pas 768, la méthode autonome historique et les neuf entrées préparées. Aucun entraînement et aucune lecture du développement ou du test réservé. Le journal public conserve le SHA du gel, les 18 numéros de passage, l’ordre des appels, les empreintes des huit champs pour chaque état, le dtype et les dimensions. Un premier démarrage a rencontré un refus de lecture de la configuration SSH par le bac à sable lors de la vérification de la branche distante, avant la création du registre et avant toute photo. La vérification de branche a ensuite réussi et les 18 passages comptabilisés sont allés à leur terme, chacun à sa première lecture. [Registre de parité](traces/index.json)

Le pilote est conservé pour un rejeu du modèle au gel dans un espace neuf disposant d’un budget distinct. Le budget R29 est clos. Dans l’environnement historique et depuis le worktree du gel publié, `python -m r29.capturer --conservation /chemin/vers/le/vision-lab-R27` lit les checkpoints et les rasters BGR du poste de conservation. La commande vérifie que la branche distante pointe sur le gel lancé. Pour reproduire cette condition historique après la publication du bilan, utiliser un clone et un distant de reproduction dont la branche `codex/vision-lab-r29` pointe sur le gel. `python -m r29.archiver` transfère les observations enregistrées vers les NPZ publics. Les commandes des deux sections précédentes suffisent à vérifier le résultat présent.

Les checkpoints pèsent chacun 244 700 133 octets. Leur taille, rôle, emplacement R27 et SHA-256 figurent dans la configuration et dans l’inventaire local. Celui-ci documente aussi les 54 capsules intermédiaires R29. Le registre R27 fournit les sources et licences par photo, ainsi que les références Mask R-CNN R50-FPN 3x de Detectron2 sous Apache 2.0 et des poids COCO dérivés sous CC BY-SA 3.0. Les figures R29 représentent uniquement des décomptes. [Inventaire R29](inventaire-local.json) · [Rasters BGR et empreintes](../r27/entrees.json) · [Crédits et droits par photo](../r26/inventaire.json) · [Source du modèle et licences](../r27/reproduire.md)

Le job Vision de GitHub exécute le rejeu léger et les tests sur archives. Le job CPU du laboratoire exécute ses tests de modèles historiques. Le contrôle Gradle porte sur le dépôt Android. L’exécution instrumentée, la NMS de TorchVision et le collage de Detectron2 ont été contrôlés sur la pile CPU locale. [Workflow Vision](../../.github/workflows/vision-lab.yml) · [Workflow du dépôt](../../.github/workflows/quality-gate.yml)
