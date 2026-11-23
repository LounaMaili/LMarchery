# Reproduire R12

Les commandes partent de `vision-lab/`. Le runtime CPU de référence est CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, OpenCV 4.13.0, NumPy 2.5.4 et Pillow 13.0.0. Installer les roues Linux x86-64 avec les versions et empreintes de [R8](../r8/reproduire.md). Le rendu des SVG utilise la bibliothèque standard. Un seul processus d’inférence, quatre threads PyTorch, un thread OpenCV, CPU Ryzen 9 5950X. Le bilan transporte le seuil de chaque méthode depuis l’historique.

## Revue et rejeu sans poids ni images

Les six fichiers [predictions](predictions/) contiennent une entrée par photo. Le tableau `candidats` conserve l’ordre du générateur. Chaque ligne donne `[[x,y], A, B, O, indice_B, indice_O]` en pixels de travail. `diagnostic.reference_axes` suit l’ordre des annotations et conserve les résultats point par point agrégés sur 21 échantillons. Les axes détectés sont en pixels au repère de 704. Les temps muraux par photo sont en secondes. Les valeurs B et O sont des scores de classement sans étalonnage probabiliste.

Les six fichiers [mesures](mesures/) donnent le seuil historique, les sélections transportées et locales, les appariements tous candidats et le coût du premier vrai contact. Chaque sélection conserve les résultats par photo, provenance et strates. Les temps proviennent de l’exécution de référence. Chaque figure et tableau peut être reproduit sans nouveau passage ni accès au test.

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
OUT=$(mktemp -d)
python -m r12.mesurer r12/predictions "$OUT/mesures"
diff -r r12/mesures "$OUT/mesures"
python -m r12.publier r12 "$OUT"
python -m r12.tubes r12 "$OUT"
cmp "$OUT/tableaux.md" r12/tableaux.md
cmp "$OUT/tubes.md" r12/tubes.md
cmp "$OUT/diagnostic-tubes.json" r12/diagnostic-tubes.json
for file in "$OUT"/figures/*; do cmp "$file" "r12/figures/${file##*/}" || exit 1; done
python -m unittest -v test_r12
```

## Recalcul des cartes et des scores

Les [sources et partitions](nouvelles.json) identifient les dérivés de travail. Restaurer les 12 dérivés publics depuis la capsule ou leurs URL. Le [registre local](inventaire.json) fournit tailles, SHA-256 et emplacements des capsules, du manifeste et des poids. Les sources R8/R9 sont les mêmes que pour R11. La commande calcule uniquement les 17 photos de développement pour les six poids. La capsule sources-admises contient aussi les scripts et la lecture d’origine avant projection. La capsule de catalogues contient les réponses de découverte et les journaux. Le service distant peut faire évoluer ses dérivés.

```sh
export R8=../../vision-lab-r8/vision-lab R9=../../vision-lab-r9/vision-lab
OUT=$(mktemp -d)
python -m r12.restaurer "$OUT/media"
python -m r12.comparer "$R8" "$R9" "$OUT/media" "$OUT/predictions"
python -m r12.mesurer "$OUT/predictions" "$OUT/mesures"
```

Un rejeu des cartes doit reproduire les 72 empreintes historiques, les 102 listes de scores et les diagnostics de géométrie. Les durées mesurent la charge et le matériel du poste. Le premier lancement arrêté après une seule carte est documenté dans le [journal de correction](corrections.md). Le gel des coordonnées de développement et des partitions précède les deux lancements.
