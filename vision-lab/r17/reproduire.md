# Reproduire la revue et l’inventaire R17

La [liste des 28 cas et le protocole](https://github.com/LounaMaili/LMarchery/commit/04ac53bd50c4ef46be5dae40caabb8e492142eb7) ont été publiés avant la nouvelle lecture. Les rangs en sortie et par longueur commencent à zéro. Sur le train on lit les quatre coordonnées de la matrice R15. Sur le développement on reprend les doubles archivés en R16 après contrôle de leur sérialisation à 12 chiffres significatifs contre la même matrice. La conversion du repère 704 vers les pixels orientés de travail divise séparément les coordonnées X et Y par les facteurs du gel. Les repères et identités des annotations restent ceux de R15.

## Vérifier depuis un clone

Partir de `vision-lab/`. Le runtime scientifique reprend CPython 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0. Les contrôles publics R17 utilisent uniquement les fichiers de GitHub, sans poids ni photographies originales. Les scripts recalculent les compteurs à partir des observations et des références, indépendamment de l’archive locale. [Dépendances de la CI](../requirements-ci.txt) · [Versions et empreintes scientifiques](../r8/reproduire.md)

```sh
OUT=$(mktemp -d)
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m r17.figer "$OUT/cas-geles.json"
cmp r17/cas-geles.json "$OUT/cas-geles.json"
python -m r17.mesurer "$OUT"
python -m r17.inventorier "$OUT"
for f in bilan.json tableaux.md acquisitions.json instances.json effectifs-instances.json; do
  cmp "r17/$f" "$OUT/$f" || exit 1
done
python -m unittest -v test_r17
```

Dans le [bilan de revue](bilan.json), les agrégats par photo et provenance comptent une fois chaque géométrie. Les compteurs `liens_…` portent sur les identités candidates et conservent les deux liens du cas 15. La masse d’un segment repris du fit est le poids R16. Les segments exclus et ceux du développement n’ont jamais de poids d’apprentissage. Les pertes, coefficients et seuils ne sont pas recalculés. La matière, l’identité, l’étendue du fragment, le rapport du bout à la jonction et la décision sur la zone ignorée sont des champs distincts du [registre de lecture](lectures.json).

## Lire les planches

La [galerie](figures/README.md) ouvre chaque JPEG directement sur GitHub. La [visionneuse](visionneuse.html) fonctionne sur disque et démarre sur les pixels seuls à chaque changement de cas. Placer `visionneuse.html` à côté du dossier `figures/` du dépôt, ou décompresser la capsule locale. Chaque référence, le segment, le point d’entrée et les régions ignorées disposent d’un calque commutable. Une entrée non localisée est désactivée. Les coordonnées des deux rectangles de pixels bruts dans le JPEG et leurs transformations affines permettent à la visionneuse de travailler hors ligne. [Index des planches](figures/index.json) · [Sources, changements et licences par image](figures/README.md) · [Inventaire de la capsule](inventaire-local.json)

Les planches utilisent le dérivé de travail R7/R15, le décodage OpenCV orienté et Pillow 13.0.0. La réduction, le choix du cadre autour de l’union des traits et de l’entrée, les couleurs et les libellés sont dans [planches.py](planches.py). Les douze contextes ont été examinés avant publication. Ils montrent les supports de tir, les objets qui y sont fichés et leurs abords, sans personne identifiable. Les six [loupes](figures/details.json) utilisent les pixels source disponibles dans l’acquisition autorisée et le rapport des dimensions orientées. Les deux loupes d’annotation montrent la version historique et la proposition R17. Les quatre autres montrent les cas de superposition ou de mauvais tube. Les planches principales conservent les références **initiales** jusque dans la superposition. Les douze sources photographiques et leurs droits sont répertoriés individuellement. Chaque dérivé porte un crédit, la même licence, la mention des changements et une notice source dans la galerie.

Avec le corpus public présent dans les worktrees historiques, les commandes suivantes régénèrent les planches. `WT` désigne le répertoire contenant les dossiers frères `vision-lab-r7` à `vision-lab-r15`. La police est DejaVu Sans. Le rendu graphique agrégé utilise CPython 3.12.14, NumPy 2.5.5 et Matplotlib 3.11.2 sur Linux x86-64. [Versions du rendu](../r13/reproduire.md) · [Script des loupes](details.py) · [Script du bilan graphique](figurer.py)

```sh
WT=../..
python -m r17.planches "$OUT/figures" --worktrees "$WT"
python -m r17.details "$OUT/figures" --worktrees "$WT"
python -m r17.indexer "$OUT/figures"
# Dans le runtime Matplotlib
MPLBACKEND=Agg python -m r17.figurer "$OUT/figures"
diff -r r17/figures "$OUT/figures"
```

L’archive de la visionneuse se construit depuis les JPEG et les index publics. Le manifeste local associe aussi douze sources archivées, les manifestes historiques, les capsules R14/R15 et le checkpoint documentaire ordinary-9301 à leur nom, taille en octets, SHA-256 et emplacement. Le réseau et les cartes n’interviennent dans aucun calcul. [Inventaire local](inventaire-local.json) · [Programme de création](archiver.py)

```sh
# Depuis le seul clone GitHub, Python standard suffit
python -m r17.archiver "$OUT/r17-lecture.zip"
# L’empreinte du ZIP est indépendante de son emplacement.
sha256sum "$OUT/r17-lecture.zip"
# Avec les douze acquisitions restaurées, on peut aussi établir le manifeste local
python -m r17.archiver "$OUT/r17-lecture.zip" "$OUT/inventaire-local.json" --worktrees "$WT"
```

## Supervision par instance

Le [registre des 195 acquisitions](acquisitions.json) conserve l’empreinte des pixels, les dimensions de travail, l’affectation d’origine et celle d’usage, les supports, les régions ignorées, l’exhaustivité, les droits et les identités annotées. Les [instances](instances.json) associent chaque identité à ses vues avec géométrie, aux autres vues qui la mentionnent et aux informations documentaires de chaque annotation. La face individuelle est indiquée quand la lecture la détermine. Une face inconnue garde la valeur `null`. Le support commun est alors consultable à l’acquisition. Les droits des mentions historiques se retrouvent par l’identifiant de leur acquisition. Les coordonnées des axes sont les bornes d’une partie visible, sans extrapolation de la flèche entière.

Le registre de polygones utilise deux noms d’origine. `R7` désigne le polygone LabelMe de la nouvelle flèche. Ses points sont projetés depuis l’original orienté par (x + 0,5) × échelle − 0,5, selon la convention R7. `R8` désigne la version sur la vue courante, qui peut être une reprise de la même annotation ou un polygone antérieur recalé et relu. Une occurrence conserve toutes ses versions disponibles. Le champ `masque_reference` donne la version la plus récente. Les 129 polygones R7 de première apparition et les 16 relevés R8 sur vues courantes représentent 137 occurrences avec polygone et 129 identités physiques. Le titre d’un corpus, une boîte, une proposition du réseau ou la seule mention d’une flèche précédente ne créent ni contour, ni axe, ni point d’entrée. Aucune boîte individuelle n’est enregistrée dans les annotations d’instances utilisées ici. [Polygones et repères](polygones.json) · [Historique des relations de séquence](historique-sequences.json)

En partant des deux manifestes locaux historiques dont l’empreinte est vérifiée, l’extraction est limitée aux 149 clichés Crigges admis au train. Les absences de géométrie des vues antérieures sont transmises explicitement. Une mention seule garde l’identité de séquence et reste hors des instances annotées. Les sept identifiants exclusivement documentaires ne reçoivent aucune coordonnée. [Extraction sans accès aux pixels](sequences.py)

```sh
python -m r17.sequences "$OUT/extraits" --worktrees "$WT"
cmp r17/polygones.json "$OUT/extraits/polygones.json"
cmp r17/historique-sequences.json "$OUT/extraits/historique-sequences.json"
```

Chaque intersection d’annotations exige leur coexistence dans **une même vue de la même instance**. « Axe et entrée hors zone ignorée » exige un trait entier sans intersection avec un rectangle fermé et un point visible hors de toutes ces régions dans cette vue. Pour un masque, on vérifie aussi le contour et l’inclusion éventuelle d’un rectangle dans le polygone. Ce décompte conservateur décrit des annotations disponibles à relire. Les statuts historiques `inconnu_ou_occulte` et similaires restent inconnus ou ambigus. Seule une occultation explicitement indiquée est comptée comme telle. Aucun masque ou point n’est inféré depuis LSD. Les onze acquisitions évaluées par R16 portent l’usage `dev_explore_R16`. [Effectifs](effectifs-instances.json) · [Code de comptage](inventorier.py)

## Historique des lectures

La [version de référence R16](https://github.com/LounaMaili/LMarchery/blob/a7b6b900e985eea7ad0de248892d24548547646b/vision-lab/r16.md) reste liée au corpus R15. Le gel R17 conserve la liste et les géométries antérieures. Les lectures R17 et les [deux propositions de correction](corrections-v2.json) sont des objets distincts. Aucun résultat historique n’a été remplacé. Le détail des validations et du checkout réellement exécuté figure dans la [PR #83](https://github.com/LounaMaili/LMarchery/pull/83).
