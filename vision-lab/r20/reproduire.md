# R20 · Rejouer les résultats publiés

Le rejeu utilise les 27 prédictions A, les deux états observés de B et les annotations train déjà dans GitHub. L’analyse des champs B utilise les 24 fichiers NPZ publics. Les photographies source et les poids locaux sont nécessaires uniquement à l’exécution originale décrite dans le protocole. [Gel antérieur](https://github.com/LounaMaili/LMarchery/blob/e96876c511747f99f89fc8151f409ca172bc84d2/vision-lab/r20/protocole.md) · [Paramètres et empreintes d’entrée](configuration.json) · [Inventaire des poids, états et capsule](inventaire-local.json)

## Mesures, tables et figures sans passage de modèle

Exécuter depuis `vision-lab/` avec CPython 3.15.2, NumPy 2.5.4 et OpenCV headless 4.13.0.92. Les planches utilisent Pillow 13.0.0 et DejaVu Sans. Le rejeu des pertes et du décodeur à partir des champs utilise PyTorch 2.14.1+cpu. Les dépendances et empreintes des roues suivent les fichiers du laboratoire. [Environnement minimal](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [Torchvision et Pillow](../requirements-r8-cpu.txt)

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
OUT=$(mktemp -d)
python -m r20.rejouer "$OUT"
python -m r20.tableaux "$OUT"
python -m r20.figurer "$OUT"
diff -r r20/mesures "$OUT/mesures"
cmp r20/photos-et-identites-A.md "$OUT/photos-et-identites-A.md"
cmp r20/sorties-A.md "$OUT/sorties-A.md"
cmp r20/identites-et-sorties-B.md "$OUT/identites-et-sorties-B.md"
for f in train.svg apprentissage.svg champs-entree.svg triplets.png donnees.json
do
    cmp "r20/figures/$f" "$OUT/figures/$f" || exit 1
done
python -m r20.verifier_champs
python -m unittest -v test_r20 test_r20_cpu test_r20_archives
```

Les comparaisons d’octets s’entendent avec les versions et polices indiquées. Le rejeu des 36 champs par état vérifie leur SHA-256 numérique, les métriques de similarité, les coûts, les pertes, les affectations, les coordonnées, les fragments et les masques binaires. Les valeurs issues des calculs flottants du décodeur et de la perte utilisent une tolérance absolue et relative de 2 × 10⁻⁶. Les plans binaires sont comparés bit à bit. Les tests d’archive recomptent aussi les arêtes admissibles à partir des masses entières et contrôlent leur cardinalité maximale avec l’algorithme indépendant de R14. [Vérification dense sans modèle](verifier_champs.py) · [Tests des preuves](../test_r20_archives.py)

## Lire les données

- **A.** Les prédictions décodées des [9301](archives/A/9301/predictions.json), [9302](archives/A/9302/predictions.json) et [9303](archives/A/9303/predictions.json) donnent douze scores, positions en pixels du repère 704, fragments et aires de grille par vue. Les termes de la perte et les logits de présence sont disponibles pour [9301](archives/A/9301/pertes.json), [9302](archives/A/9302/pertes.json) et [9303](archives/A/9303/pertes.json). Les PNG uint16 conservent le bit `1 << q` par cellule de chaque requête. Plusieurs requêtes peuvent occuper la même cellule. Les seuils sont hérités avec toute leur précision. [Par provenance et graine](mesures/provenances.json) · [Par photo et par flèche](photos-et-identites-A.md)
- **B.** Les deux dossiers contiennent chacun douze NPZ, un par requête avec trois tableaux `masque`, `axe`, `entree` de forme (256, 256) dans l’ordre ligne, colonne. Les valeurs sont les logits float32 natifs. Les registres de contrôle donnent la taille et le SHA-256 de chaque NPZ et de ses trois plans contigus little-endian, plus les normes par groupe. Les pertes documentent l’affectation d’apprentissage et ses coûts par requête et par identité. Le score décodé est le produit de la présence et du maximum du softmax spatial. [Champs au pas 0](archives/B/0000/champs/) · [Champs au pas 96](archives/B/0096/champs/) · [Contrôle initial](archives/B/0000/controle.json) · [Contrôle terminal](archives/B/0096/controle.json) · [Sorties par flèche et requête](identites-et-sorties-B.md)
- **Références et droits.** Les coordonnées de travail R18 sont converties en centres de pixels selon R19. Les masses du Dice et ses certificats utilisent la reprojection des cellules sur les pixels natifs évaluables. Les photos proviennent des sources train publiques de R18. La seule planche photographique dérive de la photo CC BY-SA 2.0 de renatomitra. [Plafonds exacts](plafonds.json) · [Sources et licences](../r18/sources.json) · [Inventaire local du corpus](../r18/inventaire-local.json) · [Crédits des figures](figures/README.md)

## Traçabilité du 25 novembre 2026

Le gel est publié sur la tête R19 `bdd5974a99df05495bda41b585a5e352fd2a3b17`. L’ordre réel est le choix de la photo par annotations et la publication des plafonds, les 27 lectures A, puis la trajectoire unique B. Les contrôles réalisés sont 0 et 96. Les contrôles 256, 512 et 1 024 sont au-delà de l’arrêt. Les pertes et gradients des 96 mises à jour et les durées sont conservés. La capsule locale de 20 042 884 octets contient aussi les deux poids, les états AdamW et les générateurs CPU. La préparation des tables, des figures et le redécodage des champs ont utilisé les sorties archivées. Aucun résultat R19 ou R20 n’a été remplacé. Les validations et le SHA effectivement testé par GitHub sont consignés dans la PR. [Historique R19](https://github.com/LounaMaili/LMarchery/commit/bdd5974a99df05495bda41b585a5e352fd2a3b17) · [Gel R20](https://github.com/LounaMaili/LMarchery/commit/e96876c511747f99f89fc8151f409ca172bc84d2) · [Registre A](archives/A/execution.json) · [Registre B](archives/B/execution.json) · [PR #86](https://github.com/LounaMaili/LMarchery/pull/86)
