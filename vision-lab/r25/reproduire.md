# Reproduire le diagnostic R25

## Revue depuis GitHub

Depuis `vision-lab/`, utiliser CPython 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0. Les tests de revue lisent les références annotées du train, les segments finaux R22/R24 et les cartes float32 publiées. Ils fonctionnent sans les photographies ni les checkpoints. L’environnement d’inférence d’origine emploie Torch 2.14.1+cpu sur x86-64. Les roues utilisées sont identifiées par version et SHA-256. [Dépendances de revue](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [TorchVision CPU](../requirements-r8-cpu.txt) · [Environnement réellement enregistré](execution.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m unittest -v test_r25 test_r25_archives
```

Le test d’archives recalcule les 54 matrices de géométrie à partir des neuf photos annotées et des sorties historiques. Il redérive les traces des 264 requêtes depuis les cartes publiques, vérifie les segments et les sondes, puis reproduit les tableaux et les figures. Les identifiants, décisions discrètes, couvertures et affectations doivent coïncider. Le code vérifie également les octets des 264 plans de masque contre les bits des PNG uint16 historiques, les SHA-256 des 528 plans float32 et ceux des archives. Pour les figures PNG encapsulées en SVG, la comparaison porte sur les pixels décodés et les éléments vectoriels. [Tests synthétiques](../test_r25.py) · [Rejeu du dossier publié](../test_r25_archives.py)

Pour obtenir une copie de travail des données dérivées dans un répertoire temporaire, les deux premières commandes écrivent la géométrie et les traces. Les trois appels de présentation produisent les tables et figures à partir des mesures publiées.

```bash
out_r25=$(mktemp -d)
python -m r25.geometrie "$out_r25"
python -m r25.analyser "$out_r25" --cartes r25 --geometrie "$out_r25" --rejeu-portable
python - "$out_r25" <<'PY'
import sys
from pathlib import Path
from r25 import planches, profils, tableaux
for module in (tableaux, planches, profils):
    module.render(Path('r25'), Path(sys.argv[1]))
PY
```

## Exécution d’origine

Le protocole et l’instrumentation ont été publiés au gel `642f3fcec0a6d6479b4ebd08b2d950e4273f6f82`. Toutes les étapes qui suivent `capturer` utilisent exclusivement les champs archivés. La commande de capture refuse une destination déjà créée et vérifie les photos transformées, les empreintes des poids, l’environnement CPU et le registre R23. Les observations ont été faites avec un seul processus d’inférence, quatre threads Torch, un thread interop et un thread OpenCV. [Protocole au commit d’origine](https://github.com/LounaMaili/LMarchery/blob/642f3fcec0a6d6479b4ebd08b2d950e4273f6f82/vision-lab/r25/protocole.md) · [Codes et entrées scellés](configuration.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
worktrees_r25=../..
python -m r25.capturer "$worktrees_r25" artifacts/r25/capture
python -m r25.parite artifacts/r25/capture r25/parite.json
python -m r25.exporter artifacts/r25/capture "$worktrees_r25"
python -m r25.analyser r25
python -m r25.tableaux
python -m r25.planches
python -m r25.profils
```

La première commande Python ci-dessus documente les **21** passages déjà exécutés. Le vingt-deuxième provient de la copie R23 Dice / 9302 / Bernard Gagnon. La relecture publique en première partie suffit à reproduire les constats sans aucun passage supplémentaire. Les numéros de cas, empreintes de l’état avant/après, dimensions de grille, poids, originaux, copie réemployée et captures complètes à trois champs figurent dans les registres. [Registre d’exécution](execution.json) · [Contrôles de parité](parite.json) · [Inventaire des 35 fichiers locaux](inventaire-local.json) · [Six sources et licences](sources.md) · [Transformations R18](../r18/sources.json)

Le décodage des captures et le décodage instrumenté sur le CPU d’origine ont été vérifiés à l’identique des sorties historiques pour les 22 cas. Sur un autre CPU, le rejeu des mesures applique les marges du protocole aux seuls champs descriptifs concernés. Les extrémités instrumentées doivent rester à 10⁻⁹ px au plus des extrémités archivées dans chaque coordonnée. Les valeurs aux centres de cellules conservent leur précision float32 exacte. [Vérification du décodage historique](parite.py) · [Vérification du décodage instrumenté](sondes.py)
