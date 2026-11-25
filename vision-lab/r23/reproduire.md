# Reproduire le diagnostic R23

## Depuis GitHub, avec les cartes publiées

Les valeurs détaillées de chaque paire sont directement lisibles dans les JSON et les tableaux Markdown. Le rejeu et les figures nécessitent uniquement la distribution minimale du laboratoire, CPython 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0. Les PNG inclus dans les SVG ne représentent que les probabilités calculées. Les cartes natives d’entrée sont publiées sous forme de matrices float32 avec leur SHA-256. [Versions et roues vérifiées](../requirements-ci.txt) · [Index des cartes](cartes/README.md)

Depuis `vision-lab/` à la révision des preuves, avec un répertoire temporaire neuf :

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
out_r23=$(mktemp -d)
python -m r23.historique "$out_r23/historique.json"
python -m r23.mesures "$out_r23" --source r23
python -m r23.presenter "$out_r23" --source r23
cmp r23/historique.json "$out_r23/historique.json"
cmp r23/mesures/effectifs.json "$out_r23/mesures/effectifs.json"
diff -r r23/tableaux "$out_r23/tableaux"
diff -r --exclude=README.md r23/figures "$out_r23/figures"
for f in tableaux.md concurrence.md supports.md
do
    cmp "r23/$f" "$out_r23/$f" || exit 1
done
python -m unittest -v test_r23 test_r23_archives
```

Le test vérifie les JSON de mesure en entier. Lors d’un rejeu sur un autre CPU, seule la distance géométrique recalculée en repère 704 accepte l’écart R22 de deux ULP et son côté du seuil doit rester identique. Tous les autres champs et les figures sont comparés exactement. Ces commandes utilisent les probabilités exportées dans leur précision historique. Les probabilités de cellule, les deux bornes des rangs et les poids du support peuvent être consultés dans les six cent vingt-quatre lignes de mesure. L’axe et le masque se rapportent à la même requête locale et au même état. [Tables lisibles par état](tableaux.md) · [Mesures JSON](mesures) · [Support des trois entrées détaillées](supports.md)

## Exécution originale et rejeu des champs conservés

Le répertoire de capture contient huit fichiers de champs, un par appel du modèle. Les quatre états utilisent la même architecture R19 et le même environnement numérique que R21/R22. Les transformations vérifient le fichier source de travail, le tampon BGR orienté et le tenseur RGB par leurs empreintes. Le registre donne la taille et l’empreinte SHA-256 des deux photos publiques, des quatre checkpoints et des huit captures locales. [Configuration et chemins des poids](configuration.json) · [Inventaire](inventaire-local.json) · [Registre d’exécution](execution.json) · [Parité](parite.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
worktrees=../..
capture=artifacts/r23/capture
# Unique commande d’origine impliquant les huit passages réseau, dans une destination neuve
python -m r23.capturer "$worktrees" "$capture"
# Étapes suivantes à partir des champs conservés, indépendantes du modèle
python -m r23.parite "$capture" "$worktrees" "$out_r23/parite.json"
python -m r23.extraire "$capture" "$worktrees" "$out_r23"
python -m r23.mesures "$out_r23" --source "$out_r23"
python -m r23.presenter "$out_r23" --source "$out_r23"
python -m r23.inventorier "$capture" "$worktrees" "$out_r23"
```

L’extraction initiale utilise Torch 2.14.1+cpu, quatre threads Torch, un thread interop, un thread OpenCV et un thread BLAS. L’inventaire des sorties numériques conserve aussi la forme et le SHA-256 des trois champs float32 avant compression. La machine d’exécution a reproduit toutes les distances géométriques à l’identique. Le test de parité conserve l’exception documentée de deux ULP pour les seuls champs de distance, avec conservation de la décision à 10 px. Les sorties et les coûts historiques sont comparés exactement. [Roues CPU](../requirements-r3-cpu.txt) · [Dépendances du tronc](../requirements-r8-cpu.txt) · [Règle de parité](parite.py)

## Provenance

Bernard Gagnon, *Changlimithang Archery Ground, Thimphu 08*, CC BY-SA 4.0. Danielle Walquist Lynch, *Corey's archery target*, CC BY 2.0. Les deux séries de cartes sont des sorties du modèle sur ces photos publiques d’apprentissage. [Notice de Bernard Gagnon](https://commons.wikimedia.org/wiki/File:Changlimithang_Archery_Ground,_Thimphu_08.jpg) · [Notice de Danielle Walquist Lynch](https://www.flickr.com/photos/25195310@N02/5115614018) · [Licences, orientations et empreintes historiques](../r18/sources.json)
