# Rejouer l’expérience R9

Utiliser Linux x86-64 et le runtime CPU de R8. Les essais de référence utilisent CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, NumPy 2.5.4 et OpenCV 4.13.0. Le tracé utilise Matplotlib 3.11.2 dans un environnement distinct. Les commandes partent de `vision-lab/`. Le répertoire R7 fournit le modèle et les pixels Crigges. Le répertoire R8 fournit les lectures assistées du train et du premier développement. [Installation du runtime](../r8/reproduire.md)

```sh
export R7=../../vision-lab-r7/vision-lab
export R8=../../vision-lab-r8/vision-lab
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
python -m unittest -v
```

## Retracer les figures depuis GitHub

Un checkout de cette révision suffit pour cette lecture. Les JSON publiés sont les six sorties d’essai d’origine. Depuis `vision-lab/`, exécuter les commandes suivantes dans un environnement de tracé CPython 3.12 avec Matplotlib 3.11.2 et NumPy 2.5.5. Les polices et le moteur de rendu peuvent influer sur les octets des PNG entre machines.

```sh
OUT_PLOTS=$(mktemp -d)
cp r9/mesures.json "$OUT_PLOTS/"
cp -r r9/train "$OUT_PLOTS/"
MPLBACKEND=Agg python -m r9.plot "$OUT_PLOTS"
cmp "$OUT_PLOTS/apprentissage.png" r9/figures/apprentissage.png
for file in "$OUT_PLOTS"/courbes/*.png; do
    cmp "$file" "r9/figures/${file##*/}" || exit 1
done
```

Le [guide des figures](figures/README.md) relie chaque tracé à ses valeurs. Les compteurs à chaque seuil sont entiers. Pour vérifier la décision dans [selection.json](selection.json), retenir les lignes de `frontiere` avec `unmatched ≤ 3` et maximiser successivement `matched`, puis `-unmatched`, puis `threshold`. La dernière ligne est le premier dépassement. En abaissant le seuil, ajouter des propositions ne peut augmenter le nombre d’appariements de plus que le nombre de propositions ajoutées. Les non-appariées ne peuvent donc plus repasser sous le budget. Le premier contact est également donné, même lorsqu’il est hors budget. La fonction de sélection et le calcul du premier contact sont versionnés. [Sélection](experiment.py) · [Premier contact](report.py)

Le [registre des sources](sources.json) donne, sur les seules partitions utilisées, les crédits, licences, supports, conditions, effectifs et empreintes des images de travail. La clé `source.sha256` (ou `source.source_sha256` pour Crigges) identifie les octets fournis par la source. Les catégories sous `banque` comptent les exemples vérifiés par photo. Les relevés assistés restent locaux. Les capsules contenant aussi des éléments réservés sont identifiées dans l’[inventaire des fichiers locaux](inventaire-local.json). Pour contrôler une capsule, calculer `sha256sum` et `wc -c` sur le fichier fermé.

## Rejeu historique à partir des capsules locales

La capsule locale `artifacts/r9/corpus-banque.zip` contient les 108 négatifs vérifiés, les images d’origine identifiées par empreinte, les catégories, les fenêtres valides, les annotations de segmentation disponibles, les décisions sur chaque photo admise et les trois plans. La capsule `sources-publiques.zip` contient les 206 dérivés et les 206 notices Flickr vérifiés. Utiliser un nouveau dossier pour chaque rejeu.

```sh
export DATA=data/r9-rejeu
export OUT=artifacts/r9-rejeu
mkdir -p "$DATA" "$OUT"
unzip -q artifacts/r9/corpus-banque.zip -d "$DATA"
cp "$DATA"/discovery/*.json "$DATA/"
unzip -q artifacts/r9/sources-publiques.zip 'media/*' -d "$DATA"
python -m r9.acquire "$DATA"
python -m r9.bank "$R7" "$R8" "$DATA"/bank-rejeu
cmp "$DATA"/bank/candidates.json "$DATA"/bank-rejeu/candidates.json
python -m r9.illustrate "$DATA" "$R7" "$R8"
python -m r9.experiment plans "$DATA" "$R7" "$R8" "$DATA"/plans-rejoues
cmp "$DATA"/plans/9301.json "$DATA"/plans-rejoues/9301.json
cmp "$DATA"/plans/9302.json "$DATA"/plans-rejoues/9302.json
cmp "$DATA"/plans/9303.json "$DATA"/plans-rejoues/9303.json
cp "$DATA"/seal.json "$OUT"/scellement-livre.json
python -m r9.corpus "$DATA" "$R7" "$R8"
cmp "$DATA"/seal.json "$OUT"/scellement-livre.json
```

Les entrées de banque proviennent de la lecture visuelle des planches de candidats. Le protocole, les choix visuels et le registre des acquisitions réservées font partie du scellement. Dans un autre dossier, `python -m r9.acquire DOSSIER --discover "$R8"` répète les 22 requêtes de découverte. Les résultats de recherche et les pages en ligne peuvent évoluer. La reproduction exacte vérifie les notices archivées. Chaque fichier liste le crédit de sa photo et la licence de l’exposition correspondante.

## Comparer les six essais

```sh
python -m r9.experiment train "$DATA" "$R7" "$R8" "$OUT"/train > "$OUT"/training.log
python -m r9.report "$DATA" "$OUT" "$R8"
python -m r9.plot "$OUT"
```

La première commande exige une destination d’entraînement neuve. Les checkpoints portent le nom du bras et de la graine. Le SHA-256 du plan et celui du contenu des fenêtres positives (pixels, cibles et masques) accompagnent chaque résultat. Les six JSON d’essai contiennent les sorties par photo et par support, les courbes à seuil variable par provenance, les seuils de développement, les pertes et les courbes d’apprentissage. Les positions des pics restent dans la capsule locale `experiences.zip`. Seules les photos de train et de développement sont chargées par le programme d’inférence. Les notices et les pixels réservés sont conservés pour une qualification ultérieure.

Les images de la banque Crigges et leurs annotations LabelMe d’origine relèvent de CC BY 4.0. Les lectures ajoutées par le laboratoire restent des annotations locales de recherche. Les droits du code et des poids TorchVision sont documentés dans la [fiche R7](../r7.md). Les scripts ne modifient aucun contrat photo ou calibration.
