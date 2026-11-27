# Les 31 identités aux quatre contrôles

Chaque ligne relie la carte native et les deux jeux de douze sorties par les identifiants de photo et de fût. Les deux valeurs d’une cellule vont de 512 à 768. Un tiret indique une attribution absente. Le rang q recommence à zéro à chaque contrôle. Les coordonnées et scores de toutes les boîtes et les quatre classes de pixels figurent dans les JSON en bout de ligne.

Une arête admissible franchit le seuil personnel. Une arête séparée franchit aussi le test de recouvrement des autres identités. Les trois attributions globales sont calculées séparément. [Table des bascules](transitions.md) · [Détail des 62 trajectoires](mesures/trajectoires.json)

## Graine 9302

### Crigges

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 13-01 | 0,592 → 0,622 | q1 0,775 → q4 0,734 | q1 0,950 → q4 0,911 | 0,900 | 0,0496 → 0,0105 | q1 → q4 | stable |
| 70-01 | 0,649 → 0,634 | q6 0,739 → q8 0,559 | q6 0,942 → q7 1,000 | 0,900 | 0,0418 → 0,1000 | q6 → q7 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 13-01 | [512](mesures/couples/9302-512-13-01.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,950 | 0,950 | q1 | q1 |
| 13-01 | [768](mesures/couples/9302-768-13-01.json) | q4 | q4 | q4 / q4 / q4 | q4 / q4 / q4 | 0,911 | 0,911 | q4 | q4 |
| 70-01 | [512](mesures/couples/9302-512-70-01.json) | q6 | q6 | q6 / q6 / q6 | q6 / q6 / q6 | 0,942 | 0,942 | q6 | q6 |
| 70-01 | [768](mesures/couples/9302-768-70-01.json) | q7 | q7 | q7 / q7 / q7 | q7 / q7 / q7 | 1,000 | 1,000 | q7 | q7 |

### jrladia

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 7245527662:1 | 0,908 → 0,925 | q0 0,941 → q0 0,944 | q0 0,983 → q0 0,998 | 0,900 | 0,0830 → 0,0985 | q0 → q0 | stable |
| 7245527662:2 | 0,895 → 0,895 | q5 0,934 → q6 0,879 | q5 0,985 → q6 0,998 | 0,900 | 0,0852 → 0,0977 | q5 → q6 | stable |
| 7245527662:3 | 0,914 → 0,864 | q6 0,936 → q8 0,715 | q6 0,970 → q8 0,967 | 0,900 | 0,0701 → 0,0667 | q6 → q8 | stable |
| 7245527662:4 | 0,805 → 0,859 | q4 0,835 → q9 0,804 | q4 0,981 → q9 1,000 | 0,900 | 0,0813 → 0,1000 | q4 → q9 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7245527662:1 | [512](mesures/couples/9302-512-7245527662.json) | q0 | q0 | q0 / q0 / q0 | — / — / q0 | 0,492 | 0,988 | q0 | q0 |
| 7245527662:1 | [768](mesures/couples/9302-768-7245527662.json) | q0 | q0 | q0 / q0 / q0 | — / — / q0 | 0,478 | 0,999 | q0 | q0 |
| 7245527662:2 | [512](mesures/couples/9302-512-7245527662.json) | q5 | q5 | q5 / q5 / q5 | — / — / q5 | 0,682 | 0,994 | q0, q5 | q5 |
| 7245527662:2 | [768](mesures/couples/9302-768-7245527662.json) | q6 | q6 | q6 / q6 / q6 | — / — / q6 | 0,629 | 1,000 | q0, q6 | q6 |
| 7245527662:3 | [512](mesures/couples/9302-512-7245527662.json) | q6 | q6 | q6 / q6 / q6 | — / — / q6 | 0,732 | 0,972 | q0, q5, q6 | q6 |
| 7245527662:3 | [768](mesures/couples/9302-768-7245527662.json) | q8 | q8 | q8 / q8 / q8 | — / — / q8 | 0,719 | 0,996 | q0, q6, q8 | q8 |
| 7245527662:4 | [512](mesures/couples/9302-512-7245527662.json) | q4 | q4 | q4 / q4 / q4 | q4 / q4 / q4 | 0,978 | 0,998 | q4, q9 | q9 |
| 7245527662:4 | [768](mesures/couples/9302-768-7245527662.json) | q9 | q9 | q9 / q9 / q9 | q9 / q9 / q9 | 0,984 | 1,000 | q9 | q9 |

### Danielle Walquist Lynch

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5115614018:1 | 0,850 → 0,724 | q1 0,723 → q0 0,614 | q1 0,990 → q0 0,957 | 0,900 | 0,0900 → 0,0574 | q1 → q0 | stable |
| 5115614018:2 | 0,813 → 0,806 | q8 0,092 → q11 0,866 | q8 0,487 → q11 0,983 | 0,900 | -0,4128 → 0,0828 | — → q11 | gain |
| 5115614018:3 | 0,394 → 0,423 | q10 0,005 → q3 0,018 | q10 0,037 → q4 0,194 | 0,900 | -0,8629 → -0,7056 | — → — | stable |
| 5115614018:4 | 0,895 → 0,901 | q9 0,512 → q5 0,675 | q11 0,704 → q5 0,945 | 0,900 | -0,1963 → 0,0454 | — → q5 | gain |
| 5115614018:5 | 0,690 → 0,686 | q2 0,280 → q9 0,163 | q2 0,838 → q4 0,488 | 0,900 | -0,0618 → -0,4118 | — → — | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5115614018:1 | [512](mesures/couples/9302-512-5115614018.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,990 | 0,990 | q1 | q1 |
| 5115614018:1 | [768](mesures/couples/9302-768-5115614018.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 0,957 | 1,000 | q0, q4 | q0 |
| 5115614018:2 | [512](mesures/couples/9302-512-5115614018.json) | ∅ | ∅ | — / — / q8 | — / — / q8 | 0,487 | 1,000 | q10 | q10 |
| 5115614018:2 | [768](mesures/couples/9302-768-5115614018.json) | q11 | q11 | q11 / q11 / q11 | q11 / q11 / q11 | 0,991 | 1,000 | q4, q11 | q11 |
| 5115614018:3 | [512](mesures/couples/9302-512-5115614018.json) | ∅ | ∅ | — / — / q10 | — / — / q10 | 0,035 | 1,000 | q10 | — |
| 5115614018:3 | [768](mesures/couples/9302-768-5115614018.json) | ∅ | ∅ | — / — / — | — / — / — | 0,180 | 1,000 | q4 | q4 |
| 5115614018:4 | [512](mesures/couples/9302-512-5115614018.json) | ∅ | ∅ | — / — / q11 | — / — / q11 | 0,704 | 1,000 | q10 | — |
| 5115614018:4 | [768](mesures/couples/9302-768-5115614018.json) | q5 | q5 | q5 / q5 / q5 | q5 / q5 / q5 | 0,945 | 1,000 | q4, q5 | q5 |
| 5115614018:5 | [512](mesures/couples/9302-512-5115614018.json) | ∅ | ∅ | — / — / q2 | — / — / q2 | 0,838 | 1,000 | q10 | — |
| 5115614018:5 | [768](mesures/couples/9302-768-5115614018.json) | ∅ | ∅ | — / — / q4 | — / — / q4 | 0,457 | 1,000 | q4 | — |

### zigazou76

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 18105652008:1 | 0,797 → 0,704 | q0 0,676 → q0 0,809 | q0 1,000 → q0 1,000 | 0,900 | 0,1000 → 0,1000 | q0 → q0 | stable |
| 18105652008:2 | 0,895 → 0,754 | q1 0,811 → q6 0,768 | q1 1,000 → q6 0,962 | 0,900 | 0,1000 → 0,0620 | q1 → q6 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 18105652008:1 | [512](mesures/couples/9302-512-18105652008.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 1,000 | 1,000 | q0 | q0 |
| 18105652008:1 | [768](mesures/couples/9302-768-18105652008.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 1,000 | 1,000 | q0 | q0 |
| 18105652008:2 | [512](mesures/couples/9302-512-18105652008.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 1,000 | 1,000 | q1 | q1 |
| 18105652008:2 | [768](mesures/couples/9302-768-18105652008.json) | q6 | q6 | q6 / q6 / q6 | q6 / q6 / q6 | 0,962 | 0,962 | q6 | q6 |

### renatomitra

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 24614689941:1 | 0,828 → 0,788 | q1 0,803 → q0 0,857 | q1 0,948 → q0 0,988 | 0,900 | 0,0478 → 0,0878 | q1 → q0 | stable |
| 24614689941:2 | 0,905 → 0,787 | q2 0,811 → q1 0,704 | q2 0,986 → q1 0,958 | 0,900 | 0,0862 → 0,0583 | q2 → q1 | stable |
| 24614689941:3 | 0,954 → 0,982 | q3 0,904 → q2 0,959 | q3 0,983 → q2 0,999 | 0,900 | 0,0828 → 0,0991 | q3 → q2 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 24614689941:1 | [512](mesures/couples/9302-512-24614689941.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,948 | 0,992 | q1, q7, q11 | q7 |
| 24614689941:1 | [768](mesures/couples/9302-768-24614689941.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 0,988 | 0,992 | q0, q5, q6, q9 | q0 |
| 24614689941:2 | [512](mesures/couples/9302-512-24614689941.json) | q2 | q2 | q2 / q2 / q2 | q2 / q2 / q2 | 0,986 | 1,000 | q2, q4, q7, q11 | q4 |
| 24614689941:2 | [768](mesures/couples/9302-768-24614689941.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,957 | 1,000 | q1, q5, q6, q9 | q5 |
| 24614689941:3 | [512](mesures/couples/9302-512-24614689941.json) | q3, q10 | q3, q10 | q3 / q3 / q3 | q3 / q3 / q3 | 0,983 | 1,000 | q3, q10 | q10 |
| 24614689941:3 | [768](mesures/couples/9302-768-24614689941.json) | q2, q7 | q2, q7 | q2 / q2 / q2 | q2 / q2 / q2 | 1,000 | 1,000 | q2, q7 | q7 |

### cristina.sanvito

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4169106197:1 | 0,773 → 0,619 | q3 0,665 → q0 0,738 | q3 1,000 → q9 1,000 | 0,900 | 0,1000 → 0,1000 | q3 → q9 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4169106197:1 | [512](mesures/couples/9302-512-4169106197.json) | q3 | q3 | q3 / q3 / q3 | q3 / q3 / q3 | 1,000 | 1,000 | q3 | q3 |
| 4169106197:1 | [768](mesures/couples/9302-768-4169106197.json) | q0, q8, q9 | q0, q8, q9 | q9 / q9 / q9 | q9 / q9 / q9 | 1,000 | 1,000 | q0, q8, q9 | q9 |

### Bernard Gagnon

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| wc71488242:1 | 0,905 → 0,725 | q0 0,000 → q0 0,000 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |
| wc71488242:2 | 0,760 → 0,700 | q4 0,667 → q6 0,556 | q4 0,848 → q6 0,767 | 0,893 | -0,0444 → -0,1251 | — → — | stable |
| wc71488242:3 | 0,705 → 0,660 | q2 0,809 → q9 0,599 | q2 0,964 → q9 0,797 | 0,890 | 0,0744 → -0,0927 | q2 → — | perte |
| wc71488242:6 | 0,786 → 0,858 | q3 0,467 → q10 0,494 | q3 0,766 → q10 0,730 | 0,900 | -0,1344 → -0,1697 | — → — | stable |
| wc71488242:7 | 0,854 → 0,794 | q11 0,308 → q5 0,303 | q6 0,482 → q3 0,440 | 0,900 | -0,4180 → -0,4604 | — → — | stable |
| wc71488242:8 | 0,833 → 0,770 | q7 0,280 → q5 0,313 | q7 0,225 → q5 0,195 | 0,900 | -0,6746 → -0,7049 | — → — | stable |
| wc71488242:9 | 0,905 → 0,781 | q7 0,052 → q5 0,058 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |
| wc71488242:10 | 0,686 → 0,666 | q0 0,000 → q8 0,212 | q0 0,000 → q8 0,494 | 0,900 | -0,9000 → -0,4062 | — → — | stable |
| wc71488242:11 | 0,845 → 0,841 | q0 0,000 → q0 0,000 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| wc71488242:1 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:1 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:2 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / q4 | — / — / q4 | 0,795 | 0,889 | ∅ | — |
| wc71488242:2 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q6 | — / — / q4 | 0,725 | 0,842 | ∅ | — |
| wc71488242:3 | [512](mesures/couples/9302-512-wc71488242.json) | q2 | q2 | q2 / q2 / q2 | — / — / q2 | 0,778 | 1,000 | q2 | q2 |
| wc71488242:3 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q9 | — / — / q9 | 0,717 | 0,981 | q9 | q9 |
| wc71488242:6 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / q3 | — / — / q11 | 0,705 | 0,856 | ∅ | — |
| wc71488242:6 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q10 | — / — / q10 | 0,667 | 0,757 | ∅ | — |
| wc71488242:7 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / q6 | — / — / q6 | 0,505 | 0,559 | ∅ | — |
| wc71488242:7 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q3 | — / — / q3 | 0,475 | 0,508 | ∅ | — |
| wc71488242:8 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / q7 | — / — / q7 | 0,539 | 0,626 | ∅ | — |
| wc71488242:8 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q5 | — / — / q5 | 0,506 | 0,614 | ∅ | — |
| wc71488242:9 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,092 | 0,144 | ∅ | — |
| wc71488242:9 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,078 | 0,127 | ∅ | — |
| wc71488242:10 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:10 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / q8 | — / — / q8 | 0,511 | 0,511 | ∅ | — |
| wc71488242:11 | [512](mesures/couples/9302-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:11 | [768](mesures/couples/9302-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |

### 4028mdk09

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| wc11001401:1 | 0,753 → 0,857 | q5 0,834 → q2 0,712 | q5 1,000 → q2 0,956 | 0,900 | 0,1000 → 0,0563 | q5 → q2 | stable |
| wc11001401:2 | 0,514 → 0,571 | q3 0,596 → q3 0,669 | q3 0,899 → q3 0,945 | 0,900 | -0,0010 → 0,0449 | — → q3 | gain |
| wc11001401:3 | 0,684 → 0,825 | q2 0,719 → q1 0,689 | q2 0,963 → q1 0,874 | 0,900 | 0,0629 → -0,0260 | q2 → — | perte |
| wc11001401:4 | 0,774 → 0,692 | q0 0,642 → q6 0,646 | q0 0,853 → q6 0,898 | 0,900 | -0,0472 → -0,0019 | — → — | stable |
| wc11001401:5 | 0,805 → 0,777 | q10 0,772 → q5 0,158 | q10 1,000 → q5 0,081 | 0,900 | 0,1000 → -0,8188 | q10 → — | perte |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| wc11001401:1 | [512](mesures/couples/9302-512-wc11001401.json) | q5 | q5 | q5 / q5 / q5 | q5 / q5 / q5 | 1,000 | 1,000 | q5 | q5 |
| wc11001401:1 | [768](mesures/couples/9302-768-wc11001401.json) | q2 | q2 | q2 / q2 / q2 | q2 / q2 / q2 | 0,956 | 0,956 | q2 | q2 |
| wc11001401:2 | [512](mesures/couples/9302-512-wc11001401.json) | ∅ | ∅ | — / — / q3 | — / — / q3 | 0,847 | 0,981 | q7 | q7 |
| wc11001401:2 | [768](mesures/couples/9302-768-wc11001401.json) | q3 | q3 | q3 / q3 / q3 | — / — / q3 | 0,813 | 0,950 | q3 | q3 |
| wc11001401:3 | [512](mesures/couples/9302-512-wc11001401.json) | q2 | q2 | q2 / q2 / q2 | — / — / q2 | 0,837 | 0,991 | q2 | q2 |
| wc11001401:3 | [768](mesures/couples/9302-768-wc11001401.json) | ∅ | ∅ | — / — / q1 | — / — / q1 | 0,706 | 0,880 | ∅ | — |
| wc11001401:4 | [512](mesures/couples/9302-512-wc11001401.json) | ∅ | ∅ | — / — / q0 | — / — / q0 | 0,854 | 0,854 | ∅ | — |
| wc11001401:4 | [768](mesures/couples/9302-768-wc11001401.json) | ∅ | ∅ | — / — / q6 | — / — / q6 | 0,887 | 0,898 | ∅ | — |
| wc11001401:5 | [512](mesures/couples/9302-512-wc11001401.json) | q10 | q10 | q10 / q10 / q10 | q10 / q10 / q10 | 1,000 | 1,000 | q10 | q10 |
| wc11001401:5 | [768](mesures/couples/9302-768-wc11001401.json) | ∅ | ∅ | — / — / q5 | — / — / q5 | 0,235 | 0,339 | ∅ | — |

## Graine 9303

### Crigges

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 13-01 | 0,594 → 0,609 | q0 0,861 → q0 0,867 | q0 1,000 → q0 0,975 | 0,900 | 0,1000 → 0,0746 | q0 → q0 | stable |
| 70-01 | 0,614 → 0,755 | q6 0,551 → q7 0,787 | q6 0,887 → q7 0,958 | 0,900 | -0,0134 → 0,0584 | — → q7 | gain |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 13-01 | [512](mesures/couples/9303-512-13-01.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 1,000 | 1,000 | q0 | q0 |
| 13-01 | [768](mesures/couples/9303-768-13-01.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 0,975 | 0,975 | q0 | q0 |
| 70-01 | [512](mesures/couples/9303-512-70-01.json) | ∅ | ∅ | — / — / q6 | — / — / q6 | 0,887 | 0,887 | ∅ | — |
| 70-01 | [768](mesures/couples/9303-768-70-01.json) | q7 | q7 | q7 / q7 / q7 | q7 / q7 / q7 | 0,958 | 0,958 | q7 | q7 |

### jrladia

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 7245527662:1 | 0,847 → 0,968 | q0 0,875 → q0 0,953 | q0 0,942 → q0 1,000 | 0,900 | 0,0416 → 0,0995 | q0 → q0 | stable |
| 7245527662:2 | 0,785 → 0,893 | q3 0,629 → q2 0,656 | q3 0,789 → q2 0,881 | 0,900 | -0,1111 → -0,0192 | — → — | stable |
| 7245527662:3 | 0,798 → 0,868 | q5 0,870 → q6 0,928 | q5 0,993 → q6 0,999 | 0,900 | 0,0934 → 0,0990 | q5 → q6 | stable |
| 7245527662:4 | 0,572 → 0,881 | q4 0,799 → q3 0,870 | q4 1,000 → q3 0,995 | 0,900 | 0,0995 → 0,0955 | q4 → q3 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7245527662:1 | [512](mesures/couples/9303-512-7245527662.json) | q0 | q0 | q0 / q0 / q0 | — / — / q0 | 0,499 | 0,968 | q0 | — |
| 7245527662:1 | [768](mesures/couples/9303-768-7245527662.json) | q0 | q0 | q0 / q0 / q0 | — / — / q0 | 0,456 | 1,000 | q0 | — |
| 7245527662:2 | [512](mesures/couples/9303-512-7245527662.json) | ∅ | ∅ | — / — / q3 | — / — / q3 | 0,640 | 0,980 | q0 | q0 |
| 7245527662:2 | [768](mesures/couples/9303-768-7245527662.json) | ∅ | ∅ | — / — / q2 | — / — / q2 | 0,674 | 1,000 | q0 | q0 |
| 7245527662:3 | [512](mesures/couples/9303-512-7245527662.json) | q5 | q5 | q5 / q5 / q5 | — / — / q5 | 0,746 | 0,997 | q0, q3, q5 | q5 |
| 7245527662:3 | [768](mesures/couples/9303-768-7245527662.json) | q6 | q6 | q6 / q6 / q6 | — / — / q6 | 0,748 | 1,000 | q0, q2, q4, q6 | q2 |
| 7245527662:4 | [512](mesures/couples/9303-512-7245527662.json) | q4 | q4 | q4 / q4 / q4 | q4 / q4 / q4 | 0,975 | 1,000 | q4 | q4 |
| 7245527662:4 | [768](mesures/couples/9303-768-7245527662.json) | q3 | q3 | q3 / q3 / q3 | q3 / q3 / q3 | 0,988 | 0,995 | q3 | q3 |

### Danielle Walquist Lynch

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5115614018:1 | 0,686 → 0,826 | q0 0,428 → q3 0,865 | q0 0,926 → q3 1,000 | 0,900 | 0,0259 → 0,1000 | q0 → q3 | stable |
| 5115614018:2 | 0,885 → 0,809 | q7 0,666 → q7 0,029 | q7 0,813 → q7 0,078 | 0,900 | -0,0871 → -0,8216 | — → — | stable |
| 5115614018:3 | 0,563 → 0,362 | q6 0,068 → q7 0,004 | q6 0,151 → q7 0,042 | 0,900 | -0,7491 → -0,8578 | — → — | stable |
| 5115614018:4 | 0,748 → 0,823 | q3 0,544 → q10 0,620 | q2 0,843 → q10 0,880 | 0,900 | -0,0570 → -0,0200 | — → — | stable |
| 5115614018:5 | 0,407 → 0,705 | q4 0,304 → q11 0,274 | q5 0,844 → q11 0,790 | 0,900 | -0,0559 → -0,1095 | — → — | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 5115614018:1 | [512](mesures/couples/9303-512-5115614018.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 0,926 | 0,926 | q0 | q0 |
| 5115614018:1 | [768](mesures/couples/9303-768-5115614018.json) | q3 | q3 | q3 / q3 / q3 | q3 / q3 / q3 | 1,000 | 1,000 | q3, q7 | q3 |
| 5115614018:2 | [512](mesures/couples/9303-512-5115614018.json) | ∅ | ∅ | — / — / q7 | — / — / q7 | 0,839 | 0,839 | ∅ | — |
| 5115614018:2 | [768](mesures/couples/9303-768-5115614018.json) | ∅ | ∅ | — / — / q7 | — / — / q7 | 0,070 | 1,000 | q7 | q7 |
| 5115614018:3 | [512](mesures/couples/9303-512-5115614018.json) | ∅ | ∅ | — / — / q6 | — / — / q6 | 0,185 | 0,233 | ∅ | — |
| 5115614018:3 | [768](mesures/couples/9303-768-5115614018.json) | ∅ | ∅ | — / — / — | — / — / — | 0,037 | 1,000 | q7 | — |
| 5115614018:4 | [512](mesures/couples/9303-512-5115614018.json) | ∅ | ∅ | — / — / q2 | — / — / q2 | 0,843 | 0,843 | ∅ | — |
| 5115614018:4 | [768](mesures/couples/9303-768-5115614018.json) | ∅ | ∅ | — / — / q10 | — / — / q10 | 0,880 | 1,000 | q7 | — |
| 5115614018:5 | [512](mesures/couples/9303-512-5115614018.json) | ∅ | ∅ | — / — / q5 | — / — / q4 | 0,849 | 0,923 | q4 | q4 |
| 5115614018:5 | [768](mesures/couples/9303-768-5115614018.json) | ∅ | ∅ | — / — / q11 | — / — / q11 | 0,794 | 1,000 | q7 | — |

### zigazou76

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 18105652008:1 | 0,653 → 0,830 | q1 0,657 → q0 0,801 | q6 1,000 → q0 1,000 | 0,900 | 0,1000 → 0,1000 | q6 → q0 | stable |
| 18105652008:2 | 0,685 → 0,817 | q3 0,886 → q2 0,887 | q3 0,980 → q2 1,000 | 0,900 | 0,0805 → 0,1000 | q3 → q2 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 18105652008:1 | [512](mesures/couples/9303-512-18105652008.json) | q1, q6 | q1, q6 | q6 / q6 / q6 | q6 / q6 / q6 | 1,000 | 1,000 | q1, q6 | q6 |
| 18105652008:1 | [768](mesures/couples/9303-768-18105652008.json) | q0, q9 | q0, q9 | q0 / q0 / q0 | q0 / q0 / q0 | 1,000 | 1,000 | q0, q9 | q0 |
| 18105652008:2 | [512](mesures/couples/9303-512-18105652008.json) | q3 | q3 | q3 / q3 / q3 | q3 / q3 / q3 | 0,980 | 0,980 | q3 | q3 |
| 18105652008:2 | [768](mesures/couples/9303-768-18105652008.json) | q2 | q2 | q2 / q2 / q2 | q2 / q2 / q2 | 1,000 | 1,000 | q2 | q2 |

### renatomitra

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 24614689941:1 | 0,855 → 0,795 | q0 0,714 → q4 0,687 | q0 0,920 → q4 0,943 | 0,900 | 0,0203 → 0,0433 | q0 → q4 | stable |
| 24614689941:2 | 0,802 → 0,938 | q1 0,605 → q1 0,749 | q1 0,854 → q1 0,946 | 0,900 | -0,0463 → 0,0456 | — → q1 | gain |
| 24614689941:3 | 0,934 → 0,982 | q2 0,941 → q3 0,946 | q2 0,982 → q3 0,998 | 0,900 | 0,0822 → 0,0981 | q2 → q3 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 24614689941:1 | [512](mesures/couples/9303-512-24614689941.json) | q0 | q0 | q0 / q0 / q0 | q0 / q0 / q0 | 0,919 | 1,000 | q0, q4, q10, q11 | q4 |
| 24614689941:1 | [768](mesures/couples/9303-768-24614689941.json) | q4 | q4 | q4 / q4 / q4 | q4 / q4 / q4 | 0,943 | 1,000 | q4, q5, q6, q7 | q5 |
| 24614689941:2 | [512](mesures/couples/9303-512-24614689941.json) | ∅ | ∅ | — / — / q1 | — / — / q1 | 0,836 | 1,000 | q1, q4, q8, q11 | q8 |
| 24614689941:2 | [768](mesures/couples/9303-768-24614689941.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,929 | 1,000 | q1, q6, q7, q9, q10 | q6 |
| 24614689941:3 | [512](mesures/couples/9303-512-24614689941.json) | q2, q6 | q2, q6 | q2 / q2 / q2 | q2 / q2 / q2 | 0,984 | 1,000 | q2, q6 | q6 |
| 24614689941:3 | [768](mesures/couples/9303-768-24614689941.json) | q3 | q3 | q3 / q3 / q3 | q3 / q3 / q3 | 0,999 | 0,999 | q3 | q3 |

### cristina.sanvito

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4169106197:1 | 0,776 → 0,877 | q1 0,709 → q0 0,794 | q1 0,938 → q8 1,000 | 0,900 | 0,0381 → 0,1000 | q1 → q8 | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4169106197:1 | [512](mesures/couples/9303-512-4169106197.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,938 | 0,938 | q1 | q1 |
| 4169106197:1 | [768](mesures/couples/9303-768-4169106197.json) | q0, q8 | q0, q8 | q8 / q8 / q8 | q8 / q8 / q8 | 1,000 | 1,000 | q0, q8 | q8 |

### Bernard Gagnon

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| wc71488242:1 | 0,774 → 0,927 | q0 0,000 → q0 0,000 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |
| wc71488242:2 | 0,800 → 0,888 | q7 0,564 → q1 0,624 | q5 0,778 → q1 0,835 | 0,893 | -0,1146 → -0,0579 | — → — | stable |
| wc71488242:3 | 0,721 → 0,801 | q7 0,588 → q6 0,740 | q7 0,753 → q6 0,927 | 0,890 | -0,1368 → 0,0369 | — → q6 | gain |
| wc71488242:6 | 0,762 → 0,818 | q0 0,260 → q4 0,497 | q0 0,626 → q4 0,818 | 0,900 | -0,2741 → -0,0819 | — → — | stable |
| wc71488242:7 | 0,798 → 0,906 | q8 0,586 → q9 0,355 | q8 0,618 → q8 0,504 | 0,900 | -0,2822 → -0,3963 | — → — | stable |
| wc71488242:8 | 0,838 → 0,900 | q8 0,502 → q9 0,361 | q10 0,252 → q9 0,301 | 0,900 | -0,6479 → -0,5988 | — → — | stable |
| wc71488242:9 | 0,835 → 0,883 | q8 0,207 → q11 0,109 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |
| wc71488242:10 | 0,614 → 0,719 | q0 0,000 → q0 0,000 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |
| wc71488242:11 | 0,748 → 0,895 | q0 0,000 → q0 0,000 | q0 0,000 → q0 0,000 | 0,900 | -0,9000 → -0,9000 | — → — | stable |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| wc71488242:1 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:1 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:2 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / q5 | — / — / q5 | 0,657 | 0,789 | ∅ | — |
| wc71488242:2 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / q1 | — / — / q1 | 0,797 | 0,860 | ∅ | — |
| wc71488242:3 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / q7 | — / — / q7 | 0,694 | 0,981 | q7 | q7 |
| wc71488242:3 | [768](mesures/couples/9303-768-wc71488242.json) | q6 | q6 | q6 / q6 / q6 | — / — / q6 | 0,746 | 0,997 | q6 | q6 |
| wc71488242:6 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / q0 | — / — / q0 | 0,638 | 0,638 | ∅ | — |
| wc71488242:6 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / q4 | — / — / q4 | 0,752 | 0,819 | ∅ | — |
| wc71488242:7 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / q8 | — / — / q9 | 0,546 | 0,881 | ∅ | — |
| wc71488242:7 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / q8 | — / — / q8 | 0,520 | 0,581 | ∅ | — |
| wc71488242:8 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / q10 | — / — / q10 | 0,547 | 0,807 | ∅ | — |
| wc71488242:8 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / q9 | — / — / q9 | 0,559 | 0,695 | ∅ | — |
| wc71488242:9 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / q8 | 0,227 | 0,467 | ∅ | — |
| wc71488242:9 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,157 | 0,259 | ∅ | — |
| wc71488242:10 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:10 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:11 | [512](mesures/couples/9303-512-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |
| wc71488242:11 | [768](mesures/couples/9303-768-wc71488242.json) | ∅ | ∅ | — / — / — | — / — / — | 0,000 | 0,000 | ∅ | — |

### 4028mdk09

| Identité | IoU RPN | q boîte · IoU | q masque · Dice | Seuil | Marge Dice | q séparé | Évolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| wc11001401:1 | 0,737 → 0,739 | q7 0,704 → q5 0,682 | q7 0,939 → q5 0,949 | 0,900 | 0,0388 → 0,0494 | q7 → q5 | stable |
| wc11001401:2 | 0,636 → 0,757 | q5 0,541 → q4 0,613 | q5 0,880 → q4 0,902 | 0,900 | -0,0197 → 0,0018 | — → q4 | gain |
| wc11001401:3 | 0,728 → 0,900 | q2 0,586 → q2 0,646 | q2 0,668 → q2 0,878 | 0,900 | -0,2324 → -0,0216 | — → — | stable |
| wc11001401:4 | 0,749 → 0,810 | q1 0,546 → q1 0,686 | q1 0,877 → q1 0,903 | 0,900 | -0,0229 → 0,0027 | — → q1 | gain |
| wc11001401:5 | 0,726 → 0,850 | q6 0,696 → q7 0,851 | q6 0,886 → q7 1,000 | 0,900 | -0,0139 → 0,1000 | — → q7 | gain |

| Identité | Mesures du pas | q admis appris | q séparés appris | Attribués appris¹ | Attribués pleins¹ | Meilleur Dice plein | Borne max. | q possibles | q oracle un à un |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| wc11001401:1 | [512](mesures/couples/9303-512-wc11001401.json) | q7 | q7 | q7 / q7 / q7 | q7 / q7 / q7 | 0,939 | 0,939 | q7 | q7 |
| wc11001401:1 | [768](mesures/couples/9303-768-wc11001401.json) | q5 | q5 | q5 / q5 / q5 | q5 / q5 / q5 | 0,950 | 0,950 | q5 | q5 |
| wc11001401:2 | [512](mesures/couples/9303-512-wc11001401.json) | ∅ | ∅ | — / — / q5 | — / — / q5 | 0,773 | 0,883 | ∅ | — |
| wc11001401:2 | [768](mesures/couples/9303-768-wc11001401.json) | q4 | q4 | q4 / q4 / q4 | — / — / q4 | 0,869 | 0,906 | q4 | q4 |
| wc11001401:3 | [512](mesures/couples/9303-512-wc11001401.json) | ∅ | ∅ | — / — / q2 | — / — / q2 | 0,623 | 0,872 | ∅ | — |
| wc11001401:3 | [768](mesures/couples/9303-768-wc11001401.json) | ∅ | ∅ | — / — / q2 | — / — / q2 | 0,699 | 0,913 | q2 | q2 |
| wc11001401:4 | [512](mesures/couples/9303-512-wc11001401.json) | ∅ | ∅ | — / — / q1 | — / — / q1 | 0,851 | 0,877 | ∅ | — |
| wc11001401:4 | [768](mesures/couples/9303-768-wc11001401.json) | q1 | q1 | q1 / q1 / q1 | q1 / q1 / q1 | 0,903 | 0,903 | q1 | q1 |
| wc11001401:5 | [512](mesures/couples/9303-512-wc11001401.json) | ∅ | ∅ | — / — / q6 | — / — / q6 | 0,886 | 0,886 | ∅ | — |
| wc11001401:5 | [768](mesures/couples/9303-768-wc11001401.json) | q7 | q7 | q7 / q7 / q7 | q7 / q7 / q7 | 0,982 | 1,000 | q7 | q7 |

¹ Les trois rangs correspondent successivement aux attributions admissible, séparée et de Dice total maximal. Les mesures JSON donnent aussi les listes d’arêtes du témoin plein, les boîtes en xyxy et les scores sans arrondi.
