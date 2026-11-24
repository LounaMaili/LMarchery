# Reproduire R14

Le [protocole](https://github.com/LounaMaili/LMarchery/blob/228edad3a5e97c5b3dc24097650ba1a5387721c8/vision-lab/r14/protocole.md) a précédé la lecture. Le [contrôle secondaire](https://github.com/LounaMaili/LMarchery/blob/4b0dea55456f233d917ed3732e702d20a15fa315/vision-lab/r14/complement.md) a été ajouté après identification des familles et lecture des rangs, puis publié avant le calcul des conditions individuelles. La révision R13 est [20064d6](https://github.com/LounaMaili/LMarchery/commit/20064d667f15b787ebf5a8e606a823340ef36b6f). Aucun résultat R13 ni résultat R14 publié précédemment n’a été remplacé.

## Revue depuis GitHub

Depuis `vision-lab/`, Python standard suffit pour régénérer à l’octet les six fichiers de mesures et la synthèse à partir des sorties R13, du registre de familles et des lectures R14. Le JSON de chaque poids contient les résultats par provenance, les sous-groupes, les seuils et toutes les quantités de contact R13. Dans les mesures, `nouveau` désigne toujours le développement déjà exploré R12. La nouvelle partition documentaire est `dev_indep` dans le corpus.

```sh
OUT=$(mktemp -d)
python -m r14.mesurer "$OUT/mesures"
for f in "$OUT"/mesures/*.json; do cmp "$f" "r14/mesures/${f##*/}" || exit 1; done
python -m unittest -v test_r14
```

Pour les deux figures, utiliser CPython 3.12, NumPy 2.5.5 et Matplotlib 3.11.2. Les [données des barres](figures/valeurs.json) sont directement lisibles.

```sh
MPLBACKEND=Agg python -m r14.figurer "$OUT/figures"
diff -r r14/figures "$OUT/figures"
```

## Recalcul sur les dérivés locaux

Les commandes ci-dessous partent aussi de `vision-lab/`. `WT` désigne le dossier des worktrees frères `vision-lab-r7`, `vision-lab-r8`, `vision-lab-r9`, `vision-lab-r12`, `vision-lab-r13` et `vision-lab-r14`. Les [185 photos autorisées](inventaire-pixels.json) et les [manifestes, capsule locale et poids de référence](inventaire.json) ont taille, emplacement et SHA-256. Les sources et licences figurent dans les [partitions](partitions.json) et le [registre de collecte](collecte.json). Les notices et dérivés nouveaux sont conservés dans la capsule R14. Les photos personnelles de l’utilisateur sont hors du corpus.

Le runtime Linux x86-64 reprend CPython 3.15.2, OpenCV 4.13.0, NumPy 2.5.4 et Pillow 13.0.0. Les [versions des dépendances R13](../r13/reproduire.md) et les [empreintes des roues](../r8/reproduire.md) sont conservées. Les poids ordinary/targeted des graines 9301–9303 sont référencés pour identifier les prédictions. Les programmes R14 lisent les sorties archivées sans charger les poids. Un seul processus de calcul à la fois.

```sh
WT=../..
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
python -m r14.lire "$WT" "$OUT/familles" "$OUT/planches"
cmp r14/mesures/familles.json "$OUT/familles/familles.json"
python -m r14.admission "$WT" "$OUT/admission.json"
cmp r14/mesures/admission.json "$OUT/admission.json"
python -m r14.prelever "$WT" "$OUT/banque"
python -m r14.corpus "$WT" "$OUT"
for n in partitions effectifs portes; do cmp "r14/$n.json" "$OUT/$n.json" || exit 1; done
```

La collecte utilise les résultats mis en cache sous `data/r14/`. La commande `python -m r14.collecter data/r14 "$WT"` complète les fichiers manquants depuis le service public. Une recherche en ligne ultérieure peut renvoyer d’autres notices. Pour retrouver la récolte examinée, extraire plutôt les octets de la capsule sous `data/r14/`. La banque comprend les rangs 0, quart, moitié, trois quarts et dernier dans la liste de candidats avant admission, triée par longueur décroissante. Ces rangs et leurs coordonnées sont reproductibles. Les classes et les tracés manuels restent des observations consignées. Les dossiers de lecture montrent les pixels bruts à gauche et le segment en surimpression à droite. Les planches photographiques restent locales. Les figures publiques ne contiennent aucun pixel photographique.

L’[outil d’annotation](annoter.html) se lance depuis le disque avec ses scripts voisins. Une vérification fonctionnelle sous Chrome 158.0.8127.36 a chargé un JPEG, tracé un tube visible, un tube occulté, un parasite et une zone incertaine, annulé puis recréé cette zone et relu le JSON téléchargé avec son empreinte.
