# Vérifier R10

## Lecture depuis GitHub

Le [bilan](bilan.json) donne les six plafonds de couverture et les comptages visuels. Les [tableaux](tableaux.md) donnent les 72 lignes par provenance, leurs [mesures](mesures.json), leurs [planches](planches/README.md) et les relevés de tous les pics. Dans chaque relevé JSON, la clé `colonnes` décrit les lignes de `essais[nom].propositions`. Les coordonnées sont les pixels de l’image de travail orientée.

- `avant_premier` marque les scores strictement supérieurs au premier candidat admissible. Il vaut vrai pour toute la liste si aucun point n’est admissible. `examen` est l’union de cet ensemble et des trois premiers rangs.
- `position_lue` indexe la position dans la photo, triée par y puis x. `aspect` indique l’objet ou la texture visuellement lisible au pixel central. Une position relue dans un autre essai conserve son code. La valeur nulle indique une position hors lecture. Les 2 660 codes de la [lecture assistée](lectures.json) sont alignés sur ces numéros, à partir de 1.
- `entrees_admissibles` contient tous les identifiants de contacts visibles à portée. `entree_appariee` est la correspondance finale un à un sur les 96 candidats. Une liste d’admissibilité non vide avec un appariement nul signale un pic redondant. Pour retrouver les autres pics autour de la même entrée, chercher son identifiant dans les listes d’admissibilité de l’essai. La même opération au seuil de 0,50 utilise `entree_appariee_050` et `reference_050`.
- `essais[nom].entrees` donne pour chaque référence son identifiant, sa coordonnée ou son statut d’entrée inconnue (`entry: null`), le nombre de voisins, le meilleur score et le rang correspondant, ainsi que le point attribué. Le meilleur voisin peut différer du point attribué. Un meilleur score nul indique l’absence de score disponible (valeur JSON `null`).
- Les mesures `seuil_050` et `seuil_decision_2` donnent les dénominateurs, propositions, faux établis, cas non résolus, abstentions et déclenchements sur témoins vides par photo. Les références et les seuils sont ceux de [R9](../r9/protocole.md).

Les aspects sont une lecture de l’assistant sur les pixels avec le contexte de la photo, sans second lecteur indépendant. « Motif » inclut le marquage peint ou imprimé, les zones colorées de la face et les inscriptions manuscrites. « Trou » inclut les déchirures visibles. Le fond inclut la texture non marquée, les fixations et les éléments de scène. Le code I conserve un aspect indéterminé. La taille du voisinage utilisé pour l’appariement permet qu’un pic appariable ait son centre sur une surface adjacente à la jonction. Les observations visuelles n’ont modifié aucune référence R9.

## Rejeu à partir des sorties archivées

Depuis `vision-lab/` dans le worktree R10, utiliser les dépendances numériques de R9 (CPython 3.15.2, NumPy 2.5.4, OpenCV 4.13.0). Pillow 12.3.0 et DejaVu Sans ont servi aux planches. Le graphique utilise CPython 3.12.14, Matplotlib 3.11.2 et NumPy 2.5.5. Le script numérique ne construit aucun réseau. Il lit uniquement le manifeste du développement et les six membres explicitement nommés de la capsule d’expériences. Les pixels des neuf acquisitions R9 et des trois acquisitions héritées de R8 sont nécessaires uniquement aux planches. [Entrées, tailles et SHA-256](entrees.json) · [Corpus, poids et emplacements de conservation R9](../r9/inventaire-local.json) · [Empreinte de chaque photo](../r9/sources.json) · [Sources et licences des dérivés](droits.json)

```sh
export R9=../../vision-lab-r9/vision-lab R8=../../vision-lab-r8/vision-lab
export OUT=$(mktemp -d)
# Dans le runtime numérique R9
python -m r10.diagnostic "$R9" "$OUT/numeric"
python -m r10.tableaux "$OUT/numeric" "$OUT/revue"
diff -r r10/releves "$OUT/revue/releves"
cmp r10/mesures.json "$OUT/revue/mesures.json"
cmp r10/bilan.json "$OUT/revue/bilan.json"
cmp r10/tableaux.md "$OUT/revue/tableaux.md"

# Dans l’environnement Pillow. Les tracés lisent les relevés publiés.
python -m r10.planches "$R9" "$R8" "$OUT/planches"
python -m r10.loupes "$R9" "$R8" "$OUT/planches/loupes"
cmp r10/planches/points.json "$OUT/planches/points.json"
diff -r r10/planches/loupes "$OUT/planches/loupes"

# Dans l’environnement Matplotlib
MPLBACKEND=Agg python -m r10.couverture "$OUT/revue/mesures.json" "$OUT/planches"
cmp r10/planches/couverture.json "$OUT/planches/couverture.json"
for fichier in "$OUT"/planches/*.png; do
    cmp "$fichier" "r10/planches/${fichier##*/}" || exit 1
done
```

Les deux ensembles de PNG et les loupes ont été reproduits sur le même poste. Les polices et les bibliothèques d’encodage peuvent modifier leurs octets ailleurs. Les données numériques et l’identification des points sont indépendantes du rendu. Le registre des sources et celui des droits se limitent au développement dans R10. La trace d’exposition d’une acquisition réservée est conservée localement dans le worktree R9, hors Git.
