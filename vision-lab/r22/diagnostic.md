# Ce que les JSON R21 établissent avant R22

Les six archives au pas 768 décrivent les mêmes neuf photos d’apprentissage et douze requêtes par photo. Pour une flèche donnée, un champ est disponible si une requête franchit son critère. Deux flèches peuvent alors revendiquer la même requête. L’appariement un à un impose des requêtes distinctes. La mesure conjointe impose en plus que tous les champs admis d’une flèche coexistent sur une seule requête. La colonne « tous séparément » accepte des requêtes différentes selon le champ. [Calcul](diagnostic.py) · [Six relevés par identité](diagnostic) · [Effectifs et quantiles](diagnostic/synthese.json)

| Bras R21 | Graine | Masques disponibles / un à un sur 31 | Axes disponibles / un à un sur 31 | Entrées disponibles / un à un sur 26 | Tous séparément / même requête / un à un sur 31 | Triplets sur 26 | Pixels retrouvés / ajoutés / manqués |
|---|---:|---:|---:|---:|---:|---:|---:|
| Témoin | 9301 | 4 / 4 | 23 / 22 | 26 / 26 | 3 / 2 / 2 | 2 | 59 592 / 118 234 / 35 958 |
| Témoin | 9302 | 2 / 2 | 21 / 20 | 25 / 25 | 2 / 2 / 2 | 2 | 75 738 / 220 949 / 19 812 |
| Témoin | 9303 | 5 / 5 | 24 / 24 | 26 / 26 | 5 / 4 / 4 | 3 | 73 228 / 139 001 / 22 322 |
| Présence exhaustive R21 | 9301 | 4 / 4 | 15 / 14 | 26 / 26 | 3 / 1 / 1 | 1 | 33 001 / 49 105 / 62 549 |
| Présence exhaustive R21 | 9302 | 4 / 4 | 19 / 18 | 26 / 26 | 3 / 3 / 3 | 2 | 70 212 / 163 571 / 25 338 |
| Présence exhaustive R21 | 9303 | 2 / 2 | 25 / 24 | 26 / 26 | 2 / 1 / 1 | 1 | 78 299 / 194 683 / 17 251 |

Les comptes de pixels utilisent le masque attribué par l’appariement R19 maximisant la somme des Dice. Pour chaque identité, VP est l’intersection, FP l’aire évaluable de la sortie moins VP et FN l’aire de référence moins VP. Les étiquettes 255 sont exclues des trois comptes. Un pixel peut contribuer aux erreurs de deux attributions distinctes. Sur ces vues, les masques couvrent au total 95 550 pixels de référence. [Plafonds et masses natives](../r20/plafonds.json) · [Attribution et domaine évaluable](../r19/mesures.py)

## Champs bloquants des identités perdues par la règle de présence R21

| Graine | Provenance, identité | Lecture des douze requêtes candidates |
|---|---|---|
| 9301 | Crigges, 70-01 | q8 atteint 90,9 % du plafond et 16 sondes, avec une erreur d’entrée de 261,1 px. q2 place l’entrée à 1,38 px et couvre 19 sondes, avec un masque à 87,4 %. Les trois champs existent séparément. |
| 9302 | Crigges, 70-01 | Aucune requête ne passe le critère de masque. q10 couvre les 21 sondes et place l’entrée à 1,62 px. Son masque atteint 61,9 % du plafond avec 845 VP, 1 023 FP et 19 FN. |
| 9303 | Crigges, 13-01 | Les requêtes 0, 5, 7, 9 et 10 satisfont masque et entrée. Les seuls axes admissibles sont ceux de q4, q8 et q11. q0 retrouve les 907 pixels de référence, ajoute 35 pixels et couvre 7 sondes sur les 11 requises. |
| 9303 | zigazou76, 18105652008:1 | Le témoin a le triplet sur q7. Chez le candidat, seule q10 place l’entrée à 1,13 px. Elle couvre les 21 sondes et retrouve les 262 pixels, mais en ajoute 144. La fraction de plafond est 78,4 %. Aucun masque n’atteint 90 %. |
| 9303 | renatomitra, 24614689941:3 | L’entrée de référence est inconnue. Le témoin représente masque et axe sur q5. Chez le candidat, aucun masque n’est admissible. q5 couvre 21 sondes, retrouve 2 589 pixels, en ajoute 933 et en manque 102. |

Dans ces cinq pertes de représentation à présence ≥ 0,5, la géométrie suffit déjà à empêcher l’appariement. Quatre sont également des pertes de triplet. Les scores de présence, les mesures de chaque champ et les trois comptes de pixels des douze requêtes, des deux côtés, permettent de vérifier chaque ligne. [Toutes les requêtes des identités perdues](diagnostic/blocages.json) · [Contrastes historiques R21](../r21/identites.md)

**Hypothèse examinée en R22.** La rareté des masques admissibles et la présence de pixels ajoutés motivent l’essai d’un terme de recouvrement par instance. Ces observations ne permettent pas d’attribuer tous les échecs au masque. À 9303 sur 13-01, améliorer un masque déjà admissible laisserait encore l’axe à résoudre. [Protocole R22](protocole.md)
