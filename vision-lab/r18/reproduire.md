# Reproduire les annotations raster, les tableaux et les planches R18

Le [gel préalable](https://github.com/LounaMaili/LMarchery/commit/96aab9befe7038269002a7048478ce0ad1ab000e) fixe les identités et les conventions. Exécuter les commandes depuis `vision-lab/`. Les vingt JSON du dossier [annotations](annotations/) sont la lecture versionnée. Chaque fichier contient les deux bords sous la forme d’un polygone fermé implicitement, les tronçons médians observés, les identités, la référence de départ, la note de lecture du contact, les plages de fond et les exclusions par tête. Un polygone parcourt un premier bord puis revient le long du second. Plusieurs polygones sous un même identifiant sont des composantes de la même flèche dans la vue.

## Rejeu du dossier public

CPython 3.15.2, NumPy 2.5.4, OpenCV 4.13.0.92. Le rendu SVG et la construction du ZIP emploient la bibliothèque standard. Les photographies originales et les poids sont inutiles pour ce premier bloc. [Versions et empreintes CI](../requirements-ci.txt) · [Contrôles spécifiques](../test_r18.py)

```sh
OUT=$(mktemp -d)
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m r18.supervision "$OUT"
python -m r18.bilan "$OUT"
python -m r18.figurer "$OUT"
for f in effectifs.json validites.json tableaux.md compatibilite.json champs-exclus.json champs-exclus.md figures/bilan.svg; do
  cmp "r18/$f" "$OUT/$f" || exit 1
done
diff -r r18/masques "$OUT/masques"
python -m unittest -v test_r18
python -m r18.archiver "$OUT/r18-annotations-revue.zip"
sha256sum "$OUT/r18-annotations-revue.zip"
```

La [carte 8 bits de chaque photo](masques/) est en niveaux de gris. 0 est un fond vérifié, 1 à 11 sont les indices locaux des instances et 255 est ignoré. L’identifiant global et l’empreinte du plan brut, en ordre ligne–colonne sans compression, se trouvent dans les [validités](validites.json). La plupart des pixels occupés sont **sans supervision** en dehors des portions attribuées et des plages de fond vérifiées. Sur les quatre témoins, la région d’évaluation couvre le champ entier. Les aires publiées comptent des centres de pixels, sans redimensionnement ni anticrénelage.

La rasterisation utilise la parité des intersections ligne par ligne et inclut les arêtes et sommets. Les exclusions historiques et R18 applicables au masque, puis le conflit d’appartenance à deux identités, rendent le pixel ignoré. Le masque d’une instance rassemble ses composantes. Les tronçons d’axe sont coupés exactement aux bords des cellules exclues pour cette tâche. Une médiane construite entre deux bords lus exige une cellule du fût observé, y compris au dernier pixel du tronçon. Une coordonnée de contact dépend de la crédibilité de la jonction et des exclusions continues propres à l’entrée. Elle ne dépend pas de la rasterisation du masque. [Code de rasterisation et d’admission](supervision.py) · [Comparaison Crigges et champs exclus](bilan.py)

## Pixels, orientation et figures

Les vingt [sources et transformations](sources.json) indiquent URL des octets ou du film, notice, crédit, licence, tailles, empreintes et matrice affine dans les deux sens. La colonne `sha256_octets_source` concerne la photographie ou la trame effectivement lue. La section `film` précise l’empreinte du WebM qui a fourni la trame 550 à 22 s. Le [périmètre](perimetre.json) rattache chaque document aux droits et à la partition R17. Les notices et les JPEG d’archives R8 conservent leurs empreintes. Un dérivé public Flickr représente la résolution obtenue du diffuseur. Les écrits décrivent les pixels effectivement disponibles, sans présumer la résolution du capteur.

Pour régénérer les planches photographiques, utiliser les fichiers publics archivés sous leurs chemins relatifs au répertoire des worktrees historiques, ou les mêmes octets rétablis à ces emplacements. Les URL d’acquisition et les notices sont indiquées individuellement. Pour Crigges et les deux anciennes photos Flickr, la référence PNG est l’encodage OpenCV du dérivé R7, compression 1, avec le grand côté borné à 1408. Pour la vidéo, la [commande d’extraction R15](../r15/restaurer.py) donne le décodage exact. Les sources de ces opérations figurent dans [l’inventaire de conservation](inventaire-local.json). Le rendu photographique utilise Pillow 13.0.0 et DejaVu Sans. [Empreintes du logiciel scientifique](../r8/reproduire.md)

```sh
WT=../..
python -m r18.perimetre "$OUT/perimetre.json" --worktrees "$WT"
cmp r18/perimetre.json "$OUT/perimetre.json"
python -m r18.sources "$OUT" --worktrees "$WT"
cmp r18/sources.json "$OUT/sources.json"
python -m r18.planches "$OUT" --worktrees "$WT"
diff -r r18/figures "$OUT/figures"
```

L’audit décode le fichier stocké en désactivant l’orientation automatique, applique séparément la rotation déclarée, puis compare le tableau complet au décodage orienté d’OpenCV. Il contrôle également tous les pixels de travail contre la référence R17. Archery Scoring est le seul EXIF 6. Les centres suivent la formule du [protocole](protocole.md). Les vues de détail sont recadrées et redressées à partir de l’original orienté. La matrice par panneau permet de reprojeter les coordonnées. Pour Moo.The.Cow, les pixels hors du cadrage de diffusion sont masqués **avant** tout redressement. Aucune photo personnelle n’est incluse.

## Lecture et limites

Le lecteur assisté a examiné les champs entiers, puis des loupes orientées et des coupes transversales. Les bornes longitudinales, chaque interruption et les deux bords de chaque fragment ont été choisis d’après les pixels. Une lecture locale du contraste raffinait séparément la position de chaque bord pointé, dans une fenêtre d’au plus 1,05 px de travail. Les contours ont été reprojetés et superposés à la photo. Les deux coupes dépassant le champ de surtr ont été raccourcies jusqu’au premier bord d’image. Les coordonnées publiées sont les lectures finales. Les commandes régénèrent leurs rendus déterministes. Une nouvelle lecture humaine pourrait aboutir à d’autres bords ou se prononcer différemment sur les zones ambiguës.

Les [88 planches](figures/README.md) présentent les pixels et le calque dans deux panneaux. Pour la lecture interactive, ouvrir la [visionneuse](visionneuse.html) depuis un clone ou le ZIP inventorié, avec le dossier `figures/` voisin. Choisir une acquisition et une instance, puis activer séparément contours lus, masque admis, axes, entrée, historique, fond et exclusions. « Pixels seuls » revient au panneau d’origine. Les plages de pixels admis embarquées dans la visionneuse redonnent exactement les cartes 8 bits. [Index et projections des panneaux](figures/index.json) · [Rendu des planches](planches.py)

La [PR R18](https://github.com/LounaMaili/LMarchery/pull/84) consigne les contrôles locaux, les SHA du protocole, des preuves, de la tête publiée et du checkout effectivement exécuté par les jobs CI.
