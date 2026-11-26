# Reproduire la chaîne R27 sur Linux x86-64

Les commandes partent de `vision-lab/`. Les photos publiques du train gardent leur arborescence historique sous `build/worktrees`. Les neuf entrées permises, les crédits individuels, les notices et les licences sont consignés en R26. Les annotations R18 proviennent d’une lecture visuelle assistée. [Inventaire des photos et droits](../r26/inventaire.json) · [Manifeste R18](../r18/sources.json) · [Origine des poids et références](../r26/references.md)

Le code Detectron2 v0.6 de Yuxin Wu et al., Facebook AI Research, est sous Apache 2.0. L’architecture Mask R-CNN est de Kaiming He et al. Les poids COCO nº 137849600 publiés par Facebook sont sous CC BY-SA 3.0. Les adaptations locales documentées sont la conversion vers une catégorie et l’ajustement partiel décrit dans le protocole. Les checkpoints dérivés conservent l’attribution et les conditions CC BY-SA 3.0. [Licence du code](https://github.com/facebookresearch/detectron2/blob/d1e04565d3bec8719335b88be9e9b961bf3ec464/LICENSE) · [Registre et licence des modèles](https://github.com/facebookresearch/detectron2/blob/d1e04565d3bec8719335b88be9e9b961bf3ec464/MODEL_ZOO.md) · [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/)

### Préparation

Dans l’environnement historique R19, Python 3.15.2, NumPy 2.5.4 et OpenCV headless 4.13.0.92, lancer la commande suivante. Les octets du fichier de référence, ses pixels après orientation, l’annotation, la carte indexée, le train et ses tirages sont vérifiés. [Dépendances R19](../requirements-ci.txt) · [Empreintes de transfert](entrees.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
python -m r27.preparer
```

L’interpréteur portable CPython 3.9.19 provient de la livraison 20240726 de python-build-standalone. Son SHA-256 a été contrôlé avec le SHA256SUMS de l’éditeur. Le registre indique la taille, le chemin, l’empreinte et l’origine de chaque roue installée. Deux roues Python locales ont été construites avec pip wheel à partir des distributions sources identifiées, avec setuptools 70.3.0 et wheel 0.45.1. L’environnement d’exécution utilise setuptools 59.5.0 pour l’interface pkg_resources requise par Torch 1.10.1. Les roues CPU de Torch et TorchVision viennent de PyTorch et celle de Detectron2 du dépôt officiel CPU torch1.10. [Environnement et URLs](environnement.json) · [Verrou complet](requirements.lock) · [Sommes publiées pour CPython](https://github.com/astral-sh/python-build-standalone/releases/download/20240726/SHA256SUMS)

Dans un espace de reproduction neuf, restaurer l’interpréteur et le dossier de roues identifiés dans l’inventaire. Placer le fichier COCO officiel dans `data/r27/model_final_f10217.pkl`. Son empreinte est vérifiée avant désérialisation. [Poids et SHA-256](../r26/poids-editeurs.json)

```bash
tar xzf data/r27/cpython.tar.gz -C data/r27
data/r27/python/bin/python3 -m venv data/r27/env
data/r27/env/bin/pip install --no-index --find-links data/r27/wheels --require-hashes -r r27/requirements.lock
data/r27/env/bin/pip check
data/r27/env/bin/python -m r27.pile_elf
export MPLCONFIGDIR=/tmp/lmarchery-r27-mpl
data/r27/env/bin/python -m r27.barriere
```

La glibc 2.44 du poste rejette la demande de pile exécutable de la roue Torch 1.10.1. Sur une installation neuve, la commande pile_elf retire le seul bit d’exécution de PT_GNU_STACK dans la copie installée de libtorch_cpu.so (octet 180, de 7 à 6). Les instructions machine et la roue d’origine gardent leurs octets. Les deux binaires sont identifiés par leurs SHA-256. Le test de collage évalue le bord interpolé sur un carré à 0,75 et l’inclusion du seuil exact sur l’intérieur constant à 0,5. Le tri stable NumPy est utilisé car argsort de Torch 1.10.1 ne prend pas l’option stable. Ces précisions ont été vérifiées sur synthétique avant les mises à jour réelles. [Modification ELF](pile-elf.json) · [Contrôles exécutés](barriere.json) · [Inventaire tenseur par tenseur](tenseurs.json)

### Exécution du protocole publié

Dans un checkout du gel, une fois la branche distante récupérée et les entrées préparées, la commande ci-dessous démarre l’unique essai. Elle exige un répertoire de sortie neuf et un commit déjà publié. Le pas zéro et les quatre autres contrôles conservent les poids, l’optimiseur et les trois états aléatoires. Chaque NPZ contient toutes les propositions RPN et leurs indices, ainsi que les probabilités de masque, les scores, les boîtes et les masques natifs de la même lecture. Les douze bits du raster uint16 identifient les sorties superposées. [Pilote](essai.py) · [Méthode de lecture des sorties](mesurer.py)

```bash
git fetch origin codex/vision-lab-r27
data/r27/env/bin/python -m r27.essai
```

Pour reproduire la chronologie observée, le premier gel conserve la version initiale. La poursuite sur le dossier authentique interrompu utilise `python -m r27.essai --apres-arret-9301`. Le pilote vérifie le SHA initial, les neuf lectures, les trois pas et l’absence de lancement des graines suivantes. [Incident et correction](incident.md)

### Revue des résultats depuis un checkout GitHub

L’environnement léger du laboratoire (Python 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0) suffit pour relire les NPZ publics et les cartes indexées de référence. Le rejeu utilise les sorties autonomes déjà enregistrées. Les photographies et les checkpoints sont nécessaires seulement pour reproduire le calcul du modèle. Les commandes suivantes réécrivent les mesures dans un répertoire temporaire et contrôlent les fichiers publiés, les figures et les journaux. [Index des lectures](sorties/index.json) · [Test de revue](../test_r27.py)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
out_r27=$(mktemp -d)
python -m r27.bilan "$out_r27"
python - "$out_r27" <<'PY'
from pathlib import Path
import sys
from r27.diagnostic import diagnose
from r27.figurer import figures
from r27.tableaux import tables
out = Path(sys.argv[1])
diagnose(out)
figures(out/'figures')
tables(out/'tableaux.md')
PY
python -m unittest -v test_r27
```

Sur le poste qui conserve les capsules, `python -m r27.archiver` relit les vrais journaux, inventorie les checkpoints et transfère les sorties NPZ en conservant leurs octets. Les NPZ identiques partagent une seule copie GitHub et gardent une entrée pour chaque lecture effectivement réalisée. Dans l’environnement CPU 3.9.19, `data/r27/env/bin/python -m r27.relire` recolle les probabilités des sorties archivées et vérifie bit à bit les masques. Il contrôle aussi les tenseurs gelés persistants, les états de momentum et les RNG de chaque checkpoint local. [Inventaire de conservation](inventaire-local.json) · [Relecture native et checkpoints](mesures/relecture-collage.json)
