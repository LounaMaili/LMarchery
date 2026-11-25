# Phase 1 · Inventaire physique et relecture des sorties R20

**Le périmètre est confirmé pour sept photos train.** La lecture assistée a porté sur l’image entière dans l’orientation exacte reçue par R19, y compris les bords, les supports voisins, les croisements et les zones exclues. Les corps et empennages visibles ont été rapprochés individuellement des identités R18 et des tâches réellement actives dans R19. Les drapeaux historiques sont conservés comme pièces de comparaison. La lecture porte sur les présences observables dans le cadre photographié. Elle n’a pas fait l’objet d’une adjudication humaine indépendante. [Registre des constatations, champs actifs et exclusions](inventaires.json) · [Annotations et validités antérieures](../r18/validites.json) · [Sources et droits](../r18/sources.json)

| Photo et provenance | Inventaire dans le champ | Présences physiques documentées / cibles actives / entrées admises | Politique de présence |
|---|---|---:|---|
| [13-01 · Crigges](../r18/figures/13-01.jpg) | Support 4. Un tube sombre, empennage bleu et centre perforé. | 1 / 1 / 1 | Exhaustive |
| [70-01 · Crigges](../r18/figures/70-01.jpg) | Même support. Une tige noire issue des ailettes bleues, attaches de papier également visibles au bord. | 1 / 1 / 1 | Exhaustive |
| [7245527662 · jrladia](../r18/figures/7245527662.jpg) | Quatre corps quittent séparément le cadre à droite. Le rectangle ignoré traverse le groupe. Les corps 1, 3 et 4 gardent masque et axe. Seule l’entrée 2 est admise. | 4 / 4 / 1 | Exhaustive |
| [5115614018 · Danielle Walquist Lynch](../r18/figures/5115614018.jpg) | Un fût isolé au-dessus du groupe inférieur de quatre. Les ombres se prolongent vers le bas de la butte. | 5 / 5 / 5 | Exhaustive |
| [18105652008 · zigazou76](../r18/figures/18105652008.jpg) | Deux sacs. Deux cibles actives à gauche, neuf identités documentaires à droite, dont les six identifiées lors de R18. | 11 / 2 / 2 | Partielle R19 |
| [24614689941 · renatomitra](../r18/figures/24614689941.jpg) | Trois fûts sur le blason central. La flèche 3 venant du bas reste positive avec masque et axe. Sa jonction est incertaine. | 3 / 3 / 2 | Exhaustive |
| [4169106197 · cristina.sanvito](../r18/figures/4169106197.jpg) | Une tige à ailettes vertes devant les trois spots. Une ombre longue descend vers la gauche. | 1 / 1 / 1 | Exhaustive |
| [wc71488242 · Bernard Gagnon](../r18/figures/wc71488242.jpg) | Onze identités documentées. Les identités 4 et 5 accolées au centre restent sans tâche active. L’entrée du fût 10 est incertaine. | 11 / 9 / 8 | Partielle R19 |
| [wc11001401 · 4028mdk09](../r18/figures/wc11001401.jpg) | Cinq fûts sur la butte, un isolé et quatre groupés. Le ruban à droite est fixé au balisage. | 5 / 5 / 5 | Exhaustive |

Sur les sept vues admissibles, les 20 présences observables ont toutes une cible de masque et d’axe, dont 16 ont aussi une entrée admise. Les quatre scènes multiflèches appartiennent à quatre provenances distinctes. Les deux vues Crigges partagent un support. Chaque inventaire tient dans les douze requêtes. Sur les deux autres vues, onze identités supplémentaires sont documentées sans champ actif. La capacité de douze emplacements n’autorise pas à effacer ces présences. Les incertitudes de contour, les entrées inconnues et les pixels ignorés demeurent dans les annotations originales. [Correspondance de chacune des 42 identités documentaires](inventaires.json)

## Lecture assistée des 336 sorties déjà archivées

Les colonnes de statut comptent des **requêtes**, par checkpoint et photo. « Coordonnée répétée » indique une autre requête au même centre exact, indépendamment de l’interprétation des champs. Pour revendiquer un fût à entrée connue, il faut au moins 25 % de couverture de son aire, 11 sondes d’axe sur 21 et un pic à 10 px au plus. Pour une entrée inconnue, il faut l’axe et 90 % du plafond de Dice. Une revendication multiple exige plusieurs requêtes ainsi étayées pour la même référence. Une confusion inter-flèches exige soit un axe et un recouvrement de 25 % sur au moins deux identités, soit un masque fort et un axe sur un fût à entrée connue et un pic compatible seulement avec une autre. La simple fusion de deux aires est conservée comme observation séparée. La sortie entièrement sur fond établi exige un masque non vide et un pic dont toutes les cellules concernées sont entièrement en fond R18 vérifié. Tout le reste conserve le statut non résolu. [Critères calculés](inventorier.py) · [Scores, requêtes, identités, coordonnées et statuts](lecture-sorties-R20.json)

| Archive | Requêtes | Avec coordonnée répétée | Revendication unique | Revendication multiple | Confusion inter-flèches | Fond établi | Non résolues |
|---|---:|---:|---:|---:|---:|---:|---:|
| A 9301 | 108 | 97 | 1 | 18 | 5 | 0 | 84 |
| A 9302 | 108 | 103 | 2 | 35 | 11 | 0 | 60 |
| A 9303 | 108 | 107 | 1 | 19 | 16 | 0 | 72 |
| B au pas 96 | 12 | 10 | 1 | 5 | 0 | 0 | 6 |

Sur renatomitra au contrôle B, q3 et q7 revendiquent le fût 1, q6, q9 et q11 le fût 2. La requête q8 représente le fût 3 pour ses deux champs admis. Son pic proche de l’entrée 1 reste ambigu pour sa propre jonction. Le masque de q2 couvre deux fûts, mais ses fragments ne permettent pas de lui attribuer les deux axes. Sur jrladia en A 9301, q9 (score 0,01908) présente un recouvrement et un axe compatibles à la fois avec les fûts 2 et 3. Aucun pic des 336 sorties ne tombe sur une cellule entièrement en fond vérifié. La règle candidate repose sur le décompte d’instances de la vue. Aucun statut spatial de ce tableau ne devient une étiquette permanente de requête. [Mesures originales du contrôle B](../r20/mesures/B-0096.json) · [Protocole R21](protocole.md)
