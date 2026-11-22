# Laboratoire vision LMarchery

Prototypes Fedora pour proposer des points d’entrée sur une photo de blason. Les résultats sont des observations en pixels de l’image orientée. La photo, le score et les données de séance restent hors du laboratoire. Les propositions ambiguës conservent éventuellement un axe de tube, sans coordonnée d’entrée.

## Index des étapes

Chaque référence ouvre le dossier tel qu’il a été publié au commit indiqué. Les PR R1 à R9 sont en brouillon au 22 novembre 2026. Les règles de livraison applicables dès R9 figurent dans les [instructions du laboratoire](AGENTS.md).

| Étape et question | Verdict et statut scientifique | PR et référence | Pièces de revue |
|---|---|---|---|
| **R1** · Une paire ou une photo seule permet-elle de localiser les entrées ? | Pilote terminé. En photo seule, 0/228 sur le test synthétique et 0/3 sur le petit lot public. | [#55](https://github.com/LounaMaili/LMarchery/pull/55) · [5c8cb59](https://github.com/LounaMaili/LMarchery/commit/5c8cb59c89483a657fe88df29a57549c4620dbef) | [Rapport et mesures](https://github.com/LounaMaili/LMarchery/blob/5c8cb59c89483a657fe88df29a57549c4620dbef/vision-lab/resultats.md) · [Protocole](https://github.com/LounaMaili/LMarchery/blob/5c8cb59c89483a657fe88df29a57549c4620dbef/vision-lab/donnees.md) |
| **R2** · Le contraste local et un recalage plus robuste améliorent-ils la lecture réelle ? | Comparaison terminée. En photo seule, 0/25 contacts sur 11 images réelles. | [#56](https://github.com/LounaMaili/LMarchery/pull/56) · [bd7a5c4](https://github.com/LounaMaili/LMarchery/commit/bd7a5c4e62727e2445c70ae881a26dbc8845162e) | [Rapport, méthode et mesures](https://github.com/LounaMaili/LMarchery/blob/bd7a5c4e62727e2445c70ae881a26dbc8845162e/vision-lab/r2.md) · [Corpus](https://github.com/LounaMaili/LMarchery/blob/bd7a5c4e62727e2445c70ae881a26dbc8845162e/vision-lab/r2-sources.json) |
| **R3** · Un réseau de segmentation et de contact bénéficie-t-il des fenêtres en photo seule ? | Pilote terminé. Les fenêtres retrouvent 39/100 contacts synthétiques et 1/4 sur le nouveau lot réel. | [#57](https://github.com/LounaMaili/LMarchery/pull/57) · [2a130ee](https://github.com/LounaMaili/LMarchery/commit/2a130ee7939fc2ccac1e6e566c81b765bbde781e) | [Rapport, protocole et mesures](https://github.com/LounaMaili/LMarchery/blob/2a130ee7939fc2ccac1e6e566c81b765bbde781e/vision-lab/r3.md) · [Sources](https://github.com/LounaMaili/LMarchery/blob/2a130ee7939fc2ccac1e6e566c81b765bbde781e/vision-lab/r3/real-sources.json) |
| **R4** · Un filtre réduit-il les propositions sur papier usé tout en conservant les contacts ? | Comparaison terminée. Sur le nouveau lot public, 30 à 8 propositions en fenêtres, 0/4 contacts dans les deux cas. Seconde lecture en attente à cette étape. | [#58](https://github.com/LounaMaili/LMarchery/pull/58) · [a4d247d](https://github.com/LounaMaili/LMarchery/commit/a4d247d55b773102407168cda584f54b5e6cf689) | [Rapport et mesures](https://github.com/LounaMaili/LMarchery/blob/a4d247d55b773102407168cda584f54b5e6cf689/vision-lab/r4-apprentissage.md) · [Protocole de corpus](https://github.com/LounaMaili/LMarchery/blob/a4d247d55b773102407168cda584f54b5e6cf689/vision-lab/r4-corpus.md) |
| **R5** · Les huit originaux candidats permettent-ils un apprentissage réel ? | Audit terminé. Deux groupes nouveaux, aucun groupe admis au train à cette étape. | [#60](https://github.com/LounaMaili/LMarchery/pull/60) · [fc44f96](https://github.com/LounaMaili/LMarchery/commit/fc44f968c8fe54771bc76aac8afefecb9cd3a73b) | [Rapport et procédure](https://github.com/LounaMaili/LMarchery/blob/fc44f968c8fe54771bc76aac8afefecb9cd3a73b/vision-lab/r5.md) · [Registre et décisions](https://github.com/LounaMaili/LMarchery/blob/fc44f968c8fe54771bc76aac8afefecb9cd3a73b/vision-lab/r5/public-candidates.json) |
| **R6** · Le décodage ou le transfert améliore-t-il la lecture des groupes ? | Comparaison terminée. Abstention totale des quatre entraînements courts au seuil choisi. NO-GO Android. | [#69](https://github.com/LounaMaili/LMarchery/pull/69) · [63e8d3d](https://github.com/LounaMaili/LMarchery/commit/63e8d3d6d03643348c8fd27846ddb8cb12abbd8a) | [Rapport](https://github.com/LounaMaili/LMarchery/blob/63e8d3d6d03643348c8fd27846ddb8cb12abbd8a/vision-lab/r6.md) · [Protocole](https://github.com/LounaMaili/LMarchery/blob/63e8d3d6d03643348c8fd27846ddb8cb12abbd8a/vision-lab/r6/protocole.md) · [Mesures](https://github.com/LounaMaili/LMarchery/blob/63e8d3d6d03643348c8fd27846ddb8cb12abbd8a/vision-lab/r6/mesures.json) |
| **R7** · Le premier apprentissage réel se transfère-t-il à d’autres acquisitions ? | Test externe terminé. Les trois modèles restent silencieux sur les deux photos au budget fixé. NO-GO Android. | [#70](https://github.com/LounaMaili/LMarchery/pull/70) · [33c12ba](https://github.com/LounaMaili/LMarchery/commit/33c12ba263bd2cd53be720045c533e54c7a4ee15) | [Rapport](https://github.com/LounaMaili/LMarchery/blob/33c12ba263bd2cd53be720045c533e54c7a4ee15/vision-lab/r7.md) · [Protocole](https://github.com/LounaMaili/LMarchery/blob/33c12ba263bd2cd53be720045c533e54c7a4ee15/vision-lab/r7/protocole.md) · [Mesures](https://github.com/LounaMaili/LMarchery/blob/33c12ba263bd2cd53be720045c533e54c7a4ee15/vision-lab/r7/mesures.json) |
| **R8** · La supervision contrôlée améliore-t-elle la discrimination sur un test adjugé ? | Test adjugé. Référence et candidat à 0/12 sur trois photos exploitables, quatrième photo invalide. NO-GO Android. | [#71](https://github.com/LounaMaili/LMarchery/pull/71) · [f70a5bc](https://github.com/LounaMaili/LMarchery/commit/f70a5bce9248dc675f94fd3a9dc71877e7fb1810) | [Rapport](https://github.com/LounaMaili/LMarchery/blob/f70a5bce9248dc675f94fd3a9dc71877e7fb1810/vision-lab/r8.md) · [Protocole](https://github.com/LounaMaili/LMarchery/blob/f70a5bce9248dc675f94fd3a9dc71877e7fb1810/vision-lab/r8/protocole.md) · [Développement](https://github.com/LounaMaili/LMarchery/blob/f70a5bce9248dc675f94fd3a9dc71877e7fb1810/vision-lab/r8/mesures.json) · [Test adjugé](https://github.com/LounaMaili/LMarchery/blob/f70a5bce9248dc675f94fd3a9dc71877e7fb1810/vision-lab/r8/mesures-finales.json) · [Script de tracé](https://github.com/LounaMaili/LMarchery/blob/f70a5bce9248dc675f94fd3a9dc71877e7fb1810/vision-lab/r8/plot.py) |
| **R9** · Le tirage ciblé de négatifs difficiles améliore-t-il la discrimination sur développement ? | Arrêt au développement. Les six essais obtiennent 0/35, test futur fermé, NO-GO Android. | [#73](https://github.com/LounaMaili/LMarchery/pull/73) · [4f44a74](https://github.com/LounaMaili/LMarchery/commit/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5) | [Rapport](https://github.com/LounaMaili/LMarchery/blob/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5/vision-lab/r9.md) · [Protocole](https://github.com/LounaMaili/LMarchery/blob/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5/vision-lab/r9/protocole.md) · [Mesures](https://github.com/LounaMaili/LMarchery/blob/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5/vision-lab/r9/mesures.json) · [Seuils](https://github.com/LounaMaili/LMarchery/blob/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5/vision-lab/r9/selection.json) · [Figures et six essais](https://github.com/LounaMaili/LMarchery/blob/4f44a74f6dd8057c86b2a2f3be2d6d2155b320a5/vision-lab/r9/figures/README.md) |

Pour R1 à R4, les tableaux chiffrés se trouvent dans les rapports. R5 est un audit de données. Les révisions R1 à R8 ne comportent pas de figures PNG ou SVG versionnées dans le laboratoire. Pour R9, la comparaison a d’abord été publiée au [commit des expériences](https://github.com/LounaMaili/LMarchery/commit/5274dd2d27bbd0177ddcd81a7da4a9d8ca7c03fd). La référence du tableau inclut les relevés et les figures ajoutés pour la revue.

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

## Rejouer R2

Le rapport [R2](r2.md) décrit les sources, les résultats et le mode de seconde lecture. La CI Python utilise les roues épinglées par SHA-256 pour CPython 3.15.2 sur Linux x86-64. Elle génère deux fois le corpus R1 et compare les empreintes des images sur le même runner. Entre environnements, elle compare les annotations, la géométrie et le modèle avec une précision de six décimales, puis contrôle les décomptes exacts. Le contrôle `--fedora-bytes` vérifie en plus les empreintes originales de Fedora. Le Quality gate Gradle reste distinct.

```sh
.venv/bin/python -m pip install --only-binary=:all: --require-hashes -r requirements-ci.txt
.venv/bin/python -m unittest -v
.venv/bin/python verify_r1.py artifacts/r1-verify
.venv/bin/python r2_synthetic.py artifacts/r1-verify/white \
  --model artifacts/r1-verify/synthetic-results/model.json --output artifacts/r2-white.json
```

Les JPEG `htk-N-C.jpg` du registre correspondent aux originaux `N-C.jpg` du commit cité. Produire `field-31.png` avec `ffmpeg -ss 31 -i field.webm -frames:v 1 field-31.png`, puis vérifier son empreinte avec le registre. Les empreintes portent sur les octets, y compris l’encodage PNG qui peut varier entre versions de FFmpeg. Le relevé local `labels.json` contient `annotated_by` et `frames`, indexé par `id` de l’inventaire. Chaque enregistrement contient le booléen `complete` et le tableau `arrows`. Les champs des flèches sont `id`, `entry`, `tail`, `axis` (deux points visibles), `visibility`, `opposite_kind` (`visible`, `clipped`, `unavailable`), `uncertainty_px`, `near_boundary`, `group` et `state`. Les points sont en pixels dans l’original orienté. Utiliser `null` pour une valeur inconnue.

```sh
.venv/bin/python r2_prepare.py data/r2-sources data/labels.json data/r2-pilot
.venv/bin/python r2_evaluate.py data/r2-pilot \
  --model artifacts/r1-verify/synthetic-results/model.json --output artifacts/r2-public
.venv/bin/python r2_corpus.py data/r2-pilot data/r2-second-reading
```

## Rejouer R3

Le [rapport R3](r3.md) décrit les corpus, la méthode de validation et l’annotateur hors ligne. Utiliser le runtime PyTorch R3 dans un venv distinct de R1/R2. Sur CPU Linux x86-64 et CPython 3.15.2, installer les [dépendances de base](requirements-ci.txt), puis les [roues CPU R3](requirements-r3-cpu.txt) avec `pip install --only-binary=:all: --require-hashes --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-r3-cpu.txt`. Utiliser `--device cpu` avec ce runtime. Pour la RTX 3090 sous Fedora, partir d’un autre venv, installer les mêmes dépendances de base et utiliser `pip install --only-binary=:all: --extra-index-url https://download.pytorch.org/whl/cu130 -r requirements-r3-cuda.txt`. Les [versions CUDA](requirements-r3-cuda.txt) sont celles de l’expérience. La CI sépare les tests sans PyTorch des trois tests de calcul CPU. Les chemins ci-dessous supposent un shell dans `vision-lab/`. Conserver tous les répertoires de sortie sous `artifacts/` ou `data/`.

```sh
export OPENBLAS_NUM_THREADS=1
.venv/bin/python -m r3.synth data/r3-synthetic
.venv/bin/python -m r3.train data/r3-synthetic artifacts/r3-model --device cuda --epochs 12 --batch-size 16
.venv/bin/python verify_r1.py artifacts/r3-r1-reference --fedora-bytes

r1_model=artifacts/r3-r1-reference/synthetic-results/model.json
.venv/bin/python -m r3.evaluate data/r3-synthetic artifacts/r3-model/model.pt "$r1_model" \
  artifacts/r3-dev --split dev --select
.venv/bin/python -m r3.evaluate data/r3-synthetic artifacts/r3-model/model.pt "$r1_model" \
  artifacts/r3-test --split test --policy artifacts/r3-dev/policy.json
```

Le test réel relit le manifeste des annotations locales au moyen de `--split real`, avec la politique gelée. Pour préparer le dossier d’annotation à l’aveugle et qualifier la séparation de groupes avant tout entraînement réel, utiliser les commandes ci-dessous. Le clic s’effectue dans l’original orienté et l’export d’annotation reste local.

```sh
.venv/bin/python -m r3.review audit r3/real-sources.json data/r3-public-sources
.venv/bin/python -m r3.review blind r3/real-sources.json data/r3-public-sources data/r3-reader-packet
.venv/bin/python -m r3.review compare data/releve-a.json data/releve-b.json
.venv/bin/python -m r3.review prepare r3/real-sources.json data/r3-public-sources \
  data/r3-real-test data/r3-readings/*-annotation.json
```

## Rejouer R4

Le [rapport de corpus](r4-corpus.md) documente les originaux publics, les droits et la seconde lecture en attente. Le [rapport d’apprentissage](r4-apprentissage.md) présente la comparaison R3/R4, y compris les contacts perdus par le filtre. Les inventaires exploratoires personnels et leurs superpositions sont conservés uniquement dans un dossier local privé.

Utiliser le runtime R3 CPU ci-dessus et un shell dans `vision-lab/`. Les deux variables ci-dessous pointent vers le modèle et la politique gelés de R3, dont R4 vérifie les empreintes. Réserver un nouveau dossier de sortie par exécution.

```bash
export MODELE_R3=artifacts/model-r3-cuda/model.pt
export POLITIQUE_R3=artifacts/r3-calibration/policy.json

python -m r4.wear data/r4-worn-stress
python -m r4.compare data/r4-worn-stress "$MODELE_R3" "$POLITIQUE_R3" artifacts/r4-synth
```

Télécharger chaque original public depuis la fiche de la [source cataloguée](r4/public-sources.json) sous le nom prévu dans le catalogue local. Préparer le premier dossier de lecture avant de lancer la comparaison. Pour chaque PNG, ouvrir son fichier `review-…json` et l’image associée avec l’[annotateur R3](r3/annotate.html), selon ses instructions. Exporter les lectures du premier et du deuxième lecteur dans des dossiers différents.

```bash
python -m r4.corpus audit r4/public-sources.json data/r4-public-sources
python -m r4.corpus blind r4/public-sources.json data/r4-public-sources data/r4-blind
python -m r4.corpus prepare r4/public-sources.json data/r4-public-sources \
  data/r4-public-test data/r4-blind data/r4-readings/*-annotation.json
python -m r4.compare data/r4-public-test "$MODELE_R3" "$POLITIQUE_R3" \
  artifacts/r4-public --original-native
python -m r3.review compare data/r4-readings/review-001-annotation.json \
  data/r4-second-readings/review-001-annotation.json
```

Pour une épreuve personnelle autorisée, `python -m r4.trial ORIGINALS "$MODELE_R3" "$POLITIQUE_R3" OUTPUT` requiert ExifTool. Employer un dossier de sortie hors originaux, sous `artifacts/` ou un emplacement privé. La commande enregistre les empreintes, les superpositions et les cartes locales. Elle n’ajuste ni le modèle ni les seuils. L’option `--original-native` appartient au comparatif de corpus R4 et n’est pas exécutée sur les photos personnelles.

Le test réel exige des références séparées. Le corpus pilote actuel est admissible à l’évaluation exploratoire. La commande d’entraînement R3 conserve son contrat synthétique. Aucun poids entraîné sur les données réelles n’est livré.

## Préparer les lectures R5

Le [bilan R5](r5.md) regroupe l’audit des huit originaux, la procédure pour les sept premières lectures et le plan d’évaluation sur de nouvelles séances. Les commandes `python -m r5.review` vérifient les fiches et préparent le lot prioritaire. Les médias, premières lectures et retours humains sont conservés localement.

## Rejouer R6

Le [bilan et la fiche modèle](r6.md) présentent les plafonds du décodage, la comparaison avec et sans préentraînement, et la parité mobile. Le [protocole](r6/protocole.md), les [commandes de reproduction](r6/rejouer.md) et le [registre des sources](r6/sources.md) accompagnent les mesures.

## Pilote réel R7

Le premier apprentissage sur photographies publiques compare MobileNetV3-Small initialisé sur ImageNet, le même réseau aléatoire et R3 figé. Le bilan distingue l’ajustement sur le support Crigges du test externe après sélection des seuils. [Résultats R7](r7.md) · [Reproduction](r7/reproduire.md)

## Discrimination des contacts R8

L’audit des pics sauvegardés, les essais à supervision contrôlée et les acquisitions publiques supplémentaires sont décrits dans le [bilan R8](r8.md). Les [commandes de reproduction](r8/reproduire.md) couvrent les données, les checkpoints et le retour du lecteur.

## Échantillonnage des négatifs R9

La [comparaison appariée R9](r9.md) mesure les effets du tirage ciblé sur trois graines et douze photos de développement. Le [protocole](r9/protocole.md) fixe les seuils et les conditions d’ouverture du futur test.
