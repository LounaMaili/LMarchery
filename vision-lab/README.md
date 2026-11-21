# Laboratoire vision LMarchery

Prototypes Fedora pour proposer des points d’entrée sur une photo de blason. Les résultats sont des observations en pixels de l’image orientée. La photo, le score et les données de séance restent hors du laboratoire. Les propositions ambiguës conservent éventuellement un axe de tube, sans coordonnée d’entrée.

## Exécuter le pilote

Depuis `vision-lab/`, avec Python 3.15 sur Fedora x86-64.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install --only-binary=:all: -r requirements.txt
export OPENBLAS_NUM_THREADS=1
.venv/bin/python -m unittest -v
.venv/bin/python lab_data.py data/synthetic
.venv/bin/python benchmark.py data/synthetic --output artifacts/synthetic
```

Le générateur sépare les blasons par groupe avant de créer les variantes d’image. Le modèle d’orientation n’est ajusté que sur `train`. La commande évalue ensuite `dev` et `test` et écrit les prédictions, les associations, les mesures par condition et quelques images de démonstration. Utiliser un nouveau répertoire de sortie pour conserver une expérience précédente.

Deux essais complémentaires restent séparés du benchmark initial. L’étude des arcs utilise des repères identifiés et des points de contrôle séparés. Le lot de tubes blancs réutilise le modèle déjà entraîné, avec des entrées visibles ou masquées.

```sh
.venv/bin/python arc_geometry.py artifacts/arcs.json
.venv/bin/python stress_data.py data/stress-white
.venv/bin/python benchmark.py data/stress-white \
  --model artifacts/synthetic/model.json --output artifacts/stress-white
```

Le petit lot public est facultatif. Il nécessite les originaux épinglés par le [registre des sources](public-sources.json) et une annotation locale contrôlée indépendamment des prédictions. Les originaux doivent garder les noms du registre. Leur SHA-256 est vérifié avant copie dans le jeu d’évaluation.

```sh
.venv/bin/python lab_data.py data/public \
  --public-originals /chemin/vers/originaux-publics \
  --public-labels /chemin/vers/annotations-locales.json
.venv/bin/python benchmark.py data/public \
  --model artifacts/synthetic/model.json --output artifacts/public
```

`--public-labels` attend un objet JSON dont les clés correspondent aux six noms du registre et les valeurs à des listes de flèches. Une entrée bien visible porte une paire `entry_relative` entre 0 et 1 dans l’image orientée, `visibility: "visible"` et `uncertainty_fraction` rapportée au plus grand côté. `tail_relative` est facultatif. En cas d’occultation ou d’ambiguïté, renseigner le statut et laisser `entry_relative` à `null`. Une cible vide porte une liste vide. Aucune photo ni annotation réelle n’est nécessaire aux tests.

## Architecture

| Fichier | Rôle |
|---|---|
| `lab_data.py` | Génération, préparation locale du lot public, empreintes, contrôle des références et des splits |
| `detector.py` | Géométrie candidate, recalage de paire, propositions de tubes, orientation A/B |
| `benchmark.py` | Ajustement synthétique, appariement indépendant, mesure et superpositions |
| `arc_geometry.py` | Ajustement de cercles partiels identifiés et contrôle indépendant |
| `stress_data.py` | Génération du lot de test séparé à tubes blancs |
| `test_lab.py`, `test_partial.py` | Entrée, abstention, homographie, groupes étanches, arcs et occultation |

L’environnement Python, les photos, les annotations, les poids et les démonstrations sont ignorés par Git. Les versions de NumPy et du paquet OpenCV headless sont fixées. Les expériences s’exécutent hors ligne après installation des dépendances. [OpenCV et notices du paquet](https://pypi.org/project/opencv-python-headless/4.13.0.92/) · [NumPy](https://pypi.org/project/numpy/2.5.4/)

- [Jeu pilote, protocole et licences](donnees.md)
- [Résultats et recommandations de cadrage](resultats.md)
- [Étude de capture CameraX](capture-assistee.md)
