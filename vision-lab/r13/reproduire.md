# Reproduire R13

Les commandes partent de `vision-lab/`. Le runtime scientifique est celui de [R12](../r12/reproduire.md), sous Linux x86-64 avec CPython 3.15.2, PyTorch 2.14.1+cpu, TorchVision 0.29.1+cpu, OpenCV 4.13.0, NumPy 2.5.4 et Pillow 13.0.0. Les versions et empreintes des roues sont précisées dans la [procédure R8](../r8/reproduire.md). Pour Matplotlib, employer un environnement distinct avec CPython 3.12, NumPy 2.5.5 et Matplotlib 3.11.2. Les durées sont des temps muraux sur le Ryzen 9 5950X. Les médianes par méthode incluent le parcours instrumenté des composantes pour A et les associations et la sélection des segments pour B. Le réseau, le reclassement et la comparaison aux références sont hors de ces chronomètres.

## Revue publique sans poids ni photographies

Chaque [sortie](sorties/) donne l’empreinte de la carte, les axes A/B au repère 704, les diagnostics par trait, la durée de chaque extraction, les suppressions de traits et la liste ordonnée des candidats en pixels de travail. Les colonnes sont `[xy, H, A, B, O0, O20, O40, F20, F40]`. L’échelle XY enregistrée relie les deux repères. Les [mesures](mesures/) donnent l’ensemble, chaque photo et provenance, les groupes, supports usés et témoins. La clé « suite » somme les longueurs des plus longues suites contiguës parmi 21 sondes par trait. « multi » permet l’union de plusieurs segments alignés. Seul « strict » entre dans la décision géométrique. Une distance au bout est la plus petite parmi tous les bouts détectés. Sa médiane exclut les photos sans aucun segment. Le compteur « bout_entree » conserve toutes les entrées visibles au dénominateur.

La carte tube native est seuillée à 0,5 et décomposée en composantes 8-connexes. Pour chaque sonde du trait humain, le relevé conserve le maximum local de probabilité dans un disque de 3 px de référence. Chaque composante touchée conserve le nombre de sondes distinctes touchées et son motif de rejet, ou son admission historique. « sans_masque » signifie qu’aucune sonde ne rencontre de pixel du masque natif dans le disque. « sous_025 » est un descriptif supplémentaire. « fragmente » compte les traits approchés par au moins deux composantes sur au moins trois sondes chacune. « partage » compte les traits dont une telle composante approche aussi un autre trait sur trois sondes. Ces indicateurs sont cumulables. La couverture strictement identique à R12 est mesurée sur le masque redimensionné et dilaté comme en R12. Pour les ablations, les traits sans entrée connue restent entiers. La géométrie tire uniquement parti de la photo et du masque pour les deux méthodes évaluées.

```sh
OUT=$(mktemp -d)
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
python -m unittest -v test_r13
python -m r13.mesurer r13/sorties "$OUT/mesures"
diff -r r13/mesures "$OUT/mesures"
python -m r13.tableaux r13 "$OUT"
cmp r13/tableaux.md "$OUT/tableaux.md"
python -m r13.planches r13 "$OUT/planches"
diff -r r13/planches "$OUT/planches"
# Dans le runtime Matplotlib indiqué ci-dessus
MPLBACKEND=Agg python -m r13.figurer "$OUT/figures" "$OUT/figures"
for file in "$OUT"/figures/*; do cmp "$file" "r13/figures/${file##*/}" || exit 1; done
```

## Recalcul de référence

Le protocole a été publié au [premier gel](https://github.com/LounaMaili/LMarchery/commit/8a649b599075c1ba124a29c6a3dd178a175ae0dc). Le [second gel](https://github.com/LounaMaili/LMarchery/commit/c5ae895b7e83a92bac8e3be418df01db5f656e43) comprend les lectures pilotes, les entrées et le code de comparaison. La destination d’inférence doit être neuve. Le code vérifie l’inventaire et contrôle à chaque passage tous les scores R12 de H, A et O0, les coordonnées, les axes PCA et le SHA-256 des deux cartes fusionnées. Les temps muraux varient d’un rejeu à l’autre. Aucun résultat de comparaison antérieur n’a été remplacé.

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
export R8=../../vision-lab-r8/vision-lab R9=../../vision-lab-r9/vision-lab
export R12=../../vision-lab-r12/vision-lab
python -m r13.experience "$R8" "$R9" "$R12" artifacts/r13-rejeu
```

[Inventaire local avec tailles, rôles et SHA-256](inventaire.json) · [Empreintes des entrées publiques](entrees.json) · [Nouvelles sources, crédits, licences, positions et inconnues](collecte.json) · [Sources historiques](../r9/sources.json) · [Sources R12](../r12/nouvelles.json) · [Méthode LSD publiée](https://www.ipol.im/pub/art/2012/gjmr-lsd/)
