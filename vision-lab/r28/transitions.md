# Changements d’attribution entre 512 et 768

Chaque section suit une identité R18. Les boîtes sont en xyxy, en pixels natifs. Les nombres sont arrondis pour la lecture, la décision utilise les produits entiers. La meilleure proposition RPN est cherchée sur toutes les propositions RPN archivées. Elle peut différer de l’ancêtre de la sortie finale. [Trajectoires sans arrondi](mesures/trajectoires.json) · [Toutes les identités](identites.md)

## 5115614018:2 · 9302 · gain

Danielle Walquist Lynch. Couverture de boîte. [Planche](figures/transition-9302-5115614018-2.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-5115614018.json) | 0,813 | 0,092 | 0,48718 | 0,90000 | -0,41282 | ∅ | ∅ | — / — / q8 |
| [768](mesures/couples/9302-768-5115614018.json) | 0,806 | 0,866 | 0,98276 | 0,90000 | 0,08276 | q11 | q11 | q11 / q11 / q11 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q46 | 309,96 · 374,77 · 454,78 · 390,43 | 7,677824 | 0,8134 |
| 512 | Meilleure boîte finale | q8 | 401,89 · 371,30 · 539,10 · 380,64 | 0,997353 | 0,0915 |
| 512 | Meilleur Dice | q8 | 401,89 · 371,30 · 539,10 · 380,64 | 0,997353 | 0,0915 |
| 768 | Meilleur RPN | q26 | 319,21 · 374,93 · 475,22 · 390,23 | 8,562162 | 0,8059 |
| 768 | Meilleure boîte finale | q11 | 315,89 · 374,69 · 467,03 · 391,62 | 0,997389 | 0,8661 |
| 768 | Meilleur Dice | q11 | 315,89 · 374,69 · 467,03 · 391,62 | 0,997389 | 0,8661 |

## 5115614018:4 · 9302 · gain

Danielle Walquist Lynch. Seuil de masque, boîte couvrante. [Planche](figures/transition-9302-5115614018-4.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-5115614018.json) | 0,895 | 0,512 | 0,70370 | 0,90000 | -0,19630 | ∅ | ∅ | — / — / q11 |
| [768](mesures/couples/9302-768-5115614018.json) | 0,901 | 0,675 | 0,94539 | 0,90000 | 0,04539 | q5 | q5 | q5 / q5 / q5 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q9 | 385,78 · 398,78 · 536,88 · 409,28 | 10,349280 | 0,8952 |
| 512 | Meilleure boîte finale | q9 | 389,78 · 402,22 · 533,91 · 413,51 | 0,997165 | 0,5124 |
| 512 | Meilleur Dice | q11 | 355,39 · 397,66 · 518,31 · 404,97 | 0,996973 | 0,3907 |
| 768 | Meilleur RPN | q9 | 381,52 · 398,70 · 540,83 · 409,59 | 10,278534 | 0,9010 |
| 768 | Meilleure boîte finale | q5 | 367,38 · 401,05 · 540,32 · 410,96 | 0,999045 | 0,6752 |
| 768 | Meilleur Dice | q5 | 367,38 · 401,05 · 540,32 · 410,96 | 0,999045 | 0,6752 |

## wc71488242:3 · 9302 · perte

Bernard Gagnon. Seuil de masque, boîte couvrante. [Planche](figures/transition-9302-wc71488242-3.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-wc71488242.json) | 0,705 | 0,809 | 0,96407 | 0,88969 | 0,07438 | q2 | q2 | q2 / q2 / q2 |
| [768](mesures/couples/9302-768-wc71488242.json) | 0,660 | 0,599 | 0,79700 | 0,88969 | -0,09269 | ∅ | ∅ | — / — / q9 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q3 | 303,17 · 498,00 · 517,82 · 527,01 | 10,911193 | 0,7048 |
| 512 | Meilleure boîte finale | q2 | 289,79 · 503,07 · 527,10 · 531,51 | 0,999822 | 0,8090 |
| 512 | Meilleur Dice | q2 | 289,79 · 503,07 · 527,10 · 531,51 | 0,999822 | 0,8090 |
| 768 | Meilleur RPN | q3 | 274,62 · 503,80 · 584,88 · 529,35 | 10,655605 | 0,6600 |
| 768 | Meilleure boîte finale | q9 | 266,58 · 499,80 · 537,63 · 526,78 | 0,999151 | 0,5989 |
| 768 | Meilleur Dice | q9 | 266,58 · 499,80 · 537,63 · 526,78 | 0,999151 | 0,5989 |

## wc11001401:2 · 9302 · gain

4028mdk09. Seuil de masque, boîte couvrante. [Planche](figures/transition-9302-wc11001401-2.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-wc11001401.json) | 0,514 | 0,596 | 0,89895 | 0,90000 | -0,00105 | ∅ | ∅ | — / — / q3 |
| [768](mesures/couples/9302-768-wc11001401.json) | 0,571 | 0,669 | 0,94491 | 0,90000 | 0,04491 | q3 | q3 | q3 / q3 / q3 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q5 | 154,75 · 627,23 · 392,93 · 652,20 | 11,098570 | 0,5144 |
| 512 | Meilleure boîte finale | q3 | 214,77 · 625,92 · 380,61 · 647,25 | 0,999277 | 0,5958 |
| 512 | Meilleur Dice | q3 | 214,77 · 625,92 · 380,61 · 647,25 | 0,999277 | 0,5958 |
| 768 | Meilleur RPN | q5 | 160,28 · 626,86 · 419,86 · 642,83 | 10,455547 | 0,5706 |
| 768 | Meilleure boîte finale | q3 | 190,98 · 627,82 · 375,53 · 648,65 | 0,998982 | 0,6693 |
| 768 | Meilleur Dice | q3 | 190,98 · 627,82 · 375,53 · 648,65 | 0,998982 | 0,6693 |

## wc11001401:3 · 9302 · perte

4028mdk09. Seuil de masque, boîte couvrante. [Planche](figures/transition-9302-wc11001401-3.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-wc11001401.json) | 0,684 | 0,719 | 0,96290 | 0,90000 | 0,06290 | q2 | q2 | q2 / q2 / q2 |
| [768](mesures/couples/9302-768-wc11001401.json) | 0,825 | 0,689 | 0,87405 | 0,90000 | -0,02595 | ∅ | ∅ | — / — / q1 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q0 | 160,76 · 642,28 · 378,96 · 664,41 | 12,553240 | 0,6837 |
| 512 | Meilleure boîte finale | q2 | 175,68 · 642,39 · 384,87 · 664,05 | 0,999327 | 0,7186 |
| 512 | Meilleur Dice | q2 | 175,68 · 642,39 · 384,87 · 664,05 | 0,999327 | 0,7186 |
| 768 | Meilleur RPN | q44 | 158,65 · 639,98 · 383,17 · 660,08 | 5,269338 | 0,8252 |
| 768 | Meilleure boîte finale | q1 | 217,49 · 638,04 · 370,70 · 660,32 | 0,999250 | 0,6887 |
| 768 | Meilleur Dice | q1 | 217,49 · 638,04 · 370,70 · 660,32 | 0,999250 | 0,6887 |

## wc11001401:5 · 9302 · perte

4028mdk09. Couverture de boîte. [Planche](figures/transition-9302-wc11001401-5.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9302-512-wc11001401.json) | 0,805 | 0,772 | 1,00000 | 0,90000 | 0,10000 | q10 | q10 | q10 / q10 / q10 |
| [768](mesures/couples/9302-768-wc11001401.json) | 0,777 | 0,158 | 0,08123 | 0,90000 | -0,81877 | ∅ | ∅ | — / — / q5 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q8 | 177,33 · 673,49 · 388,76 · 699,10 | 10,495466 | 0,8052 |
| 512 | Meilleure boîte finale | q10 | 177,34 · 676,55 · 398,51 · 702,83 | 0,996632 | 0,7722 |
| 512 | Meilleur Dice | q10 | 177,34 · 676,55 · 398,51 · 702,83 | 0,996632 | 0,7722 |
| 768 | Meilleur RPN | q120 | 158,33 · 674,76 · 392,57 · 699,41 | 3,574260 | 0,7773 |
| 768 | Meilleure boîte finale | q5 | 177,32 · 665,81 · 388,01 · 682,44 | 0,998087 | 0,1576 |
| 768 | Meilleur Dice | q5 | 177,32 · 665,81 · 388,01 · 682,44 | 0,998087 | 0,1576 |

## 70-01 · 9303 · gain

Crigges. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-70-01.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-70-01.json) | 0,614 | 0,551 | 0,88660 | 0,90000 | -0,01340 | ∅ | ∅ | — / — / q6 |
| [768](mesures/couples/9303-768-70-01.json) | 0,755 | 0,787 | 0,95841 | 0,90000 | 0,05841 | q7 | q7 | q7 / q7 / q7 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q28 | 271,32 · 754,17 · 569,74 · 823,62 | 3,699293 | 0,6138 |
| 512 | Meilleure boîte finale | q6 | 263,23 · 726,85 · 514,87 · 808,65 | 0,996120 | 0,5513 |
| 512 | Meilleur Dice | q6 | 263,23 · 726,85 · 514,87 · 808,65 | 0,996120 | 0,5513 |
| 768 | Meilleur RPN | q45 | 269,07 · 743,84 · 540,07 · 825,48 | 2,694701 | 0,7550 |
| 768 | Meilleure boîte finale | q7 | 269,54 · 743,97 · 509,87 · 818,34 | 0,996034 | 0,7873 |
| 768 | Meilleur Dice | q7 | 269,54 · 743,97 · 509,87 · 818,34 | 0,996034 | 0,7873 |

## 24614689941:2 · 9303 · gain

renatomitra. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-24614689941-2.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-24614689941.json) | 0,802 | 0,605 | 0,85373 | 0,90000 | -0,04627 | ∅ | ∅ | — / — / q1 |
| [768](mesures/couples/9303-768-24614689941.json) | 0,938 | 0,749 | 0,94558 | 0,90000 | 0,04558 | q1 | q1 | q1 / q1 / q1 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q2 | 430,88 · 380,06 · 582,08 · 424,02 | 8,042233 | 0,8024 |
| 512 | Meilleure boîte finale | q1 | 434,54 · 371,49 · 584,91 · 419,13 | 0,996847 | 0,6055 |
| 512 | Meilleur Dice | q1 | 434,54 · 371,49 · 584,91 · 419,13 | 0,996847 | 0,6055 |
| 768 | Meilleur RPN | q3 | 439,03 · 381,91 · 582,21 · 426,89 | 7,352551 | 0,9377 |
| 768 | Meilleure boîte finale | q1 | 434,13 · 374,44 · 588,77 · 426,93 | 0,995350 | 0,7491 |
| 768 | Meilleur Dice | q1 | 434,13 · 374,44 · 588,77 · 426,93 | 0,995350 | 0,7491 |

## wc71488242:3 · 9303 · gain

Bernard Gagnon. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-wc71488242-3.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-wc71488242.json) | 0,721 | 0,588 | 0,75286 | 0,88969 | -0,13683 | ∅ | ∅ | — / — / q7 |
| [768](mesures/couples/9303-768-wc71488242.json) | 0,801 | 0,740 | 0,92661 | 0,88969 | 0,03691 | q6 | q6 | q6 / q6 / q6 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q71 | 281,22 · 504,97 · 508,89 · 534,23 | 6,059102 | 0,7209 |
| 512 | Meilleure boîte finale | q7 | 279,25 · 497,28 · 536,62 · 527,20 | 0,999283 | 0,5879 |
| 512 | Meilleur Dice | q7 | 279,25 · 497,28 · 536,62 · 527,20 | 0,999283 | 0,5879 |
| 768 | Meilleur RPN | q101 | 267,66 · 503,55 · 521,27 · 529,52 | 5,321634 | 0,8013 |
| 768 | Meilleure boîte finale | q6 | 285,30 · 501,06 · 532,62 · 529,11 | 0,999318 | 0,7403 |
| 768 | Meilleur Dice | q6 | 285,30 · 501,06 · 532,62 · 529,11 | 0,999318 | 0,7403 |

## wc11001401:2 · 9303 · gain

4028mdk09. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-wc11001401-2.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-wc11001401.json) | 0,636 | 0,541 | 0,88028 | 0,90000 | -0,01972 | ∅ | ∅ | — / — / q5 |
| [768](mesures/couples/9303-768-wc11001401.json) | 0,757 | 0,613 | 0,90182 | 0,90000 | 0,00182 | q4 | q4 | q4 / q4 / q4 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q18 | 204,89 · 630,37 · 363,48 · 648,73 | 7,665172 | 0,6357 |
| 512 | Meilleure boîte finale | q5 | 220,24 · 627,18 · 381,47 · 650,28 | 0,996407 | 0,5409 |
| 512 | Meilleur Dice | q5 | 220,24 · 627,18 · 381,47 · 650,28 | 0,996407 | 0,5409 |
| 768 | Meilleur RPN | q3 | 173,01 · 629,69 · 376,76 · 649,14 | 11,064459 | 0,7573 |
| 768 | Meilleure boîte finale | q4 | 213,39 · 626,28 · 388,87 · 646,07 | 0,997608 | 0,6133 |
| 768 | Meilleur Dice | q4 | 213,39 · 626,28 · 388,87 · 646,07 | 0,997608 | 0,6133 |

## wc11001401:4 · 9303 · gain

4028mdk09. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-wc11001401-4.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-wc11001401.json) | 0,749 | 0,546 | 0,87712 | 0,90000 | -0,02288 | ∅ | ∅ | — / — / q1 |
| [768](mesures/couples/9303-768-wc11001401.json) | 0,810 | 0,686 | 0,90269 | 0,90000 | 0,00269 | q1 | q1 | q1 / q1 / q1 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q36 | 192,72 · 655,06 · 359,86 · 675,65 | 5,839314 | 0,7485 |
| 512 | Meilleure boîte finale | q1 | 217,19 · 661,82 · 384,51 · 681,49 | 0,999615 | 0,5455 |
| 512 | Meilleur Dice | q1 | 217,19 · 661,82 · 384,51 · 681,49 | 0,999615 | 0,5455 |
| 768 | Meilleur RPN | q7 | 183,28 · 657,48 · 383,24 · 680,17 | 9,965727 | 0,8096 |
| 768 | Meilleure boîte finale | q1 | 200,53 · 661,17 · 396,02 · 677,85 | 0,999714 | 0,6857 |
| 768 | Meilleur Dice | q1 | 200,53 · 661,17 · 396,02 · 677,85 | 0,999714 | 0,6857 |

## wc11001401:5 · 9303 · gain

4028mdk09. Seuil de masque, boîte couvrante. [Planche](figures/transition-9303-wc11001401-5.png)

| Pas | IoU RPN | IoU finale max. | Dice max. | Seuil | Marge | q admis | q séparés | Attribués admis / séparé / Dice |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [512](mesures/couples/9303-512-wc11001401.json) | 0,726 | 0,696 | 0,88614 | 0,90000 | -0,01386 | ∅ | ∅ | — / — / q6 |
| [768](mesures/couples/9303-768-wc11001401.json) | 0,850 | 0,851 | 1,00000 | 0,90000 | 0,10000 | q7 | q7 | q7 / q7 / q7 |

| Pas | Sélection locale | Rang | x₁ · y₁ · x₂ · y₂ | Logit RPN ou score final | IoU boîte |
| --- | --- | --- | --- | --- | --- |
| 512 | Meilleur RPN | q10 | 217,63 · 677,05 · 370,68 · 699,91 | 9,820481 | 0,7259 |
| 512 | Meilleure boîte finale | q6 | 187,07 · 681,57 · 400,38 · 701,14 | 0,995416 | 0,6964 |
| 512 | Meilleur Dice | q6 | 187,07 · 681,57 · 400,38 · 701,14 | 0,995416 | 0,6964 |
| 768 | Meilleur RPN | q10 | 197,97 · 676,20 · 389,10 · 700,61 | 9,351994 | 0,8499 |
| 768 | Meilleure boîte finale | q7 | 188,17 · 675,10 · 401,74 · 699,76 | 0,993591 | 0,8509 |
| 768 | Meilleur Dice | q7 | 188,17 · 675,10 · 401,74 · 699,76 | 0,993591 | 0,8509 |
