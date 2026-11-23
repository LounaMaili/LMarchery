# Diagnostic des tubes

Le masque est mesuré au seuil 0,5 en 21 points par axe, avec tolérance latérale de 3 px. Un axe est retrouvé si un même segment détecté reste à 6 px et à 20° près sur au moins 11 points. Un segment peut expliquer plusieurs axes annotés. Le croisement est une intersection intérieure stricte des traits en projection. Deux tubes proches et parallèles peuvent être groupés sans croiser leurs axes.

## Développement historique

| Checkpoint | Points couverts / sondés | Axes retrouvés | Groupés | Croisés | Entrée occultée | Segments détectés | Bout près entrée / visibles | Bout près extrémité opposée du trait / visibles | Photos sans segment |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ordinary-9301 | 338/840 | 3/40 | 2/24 | 0/8 | 1/5 | 81 | 6/35 | 1/35 | 2/12 |
| ordinary-9302 | 391/840 | 1/40 | 0/24 | 0/8 | 0/5 | 71 | 3/35 | 1/35 | 3/12 |
| ordinary-9303 | 400/840 | 2/40 | 1/24 | 0/8 | 1/5 | 50 | 2/35 | 2/35 | 2/12 |
| targeted-9301 | 326/840 | 5/40 | 2/24 | 1/8 | 0/5 | 74 | 5/35 | 2/35 | 1/12 |
| targeted-9302 | 354/840 | 2/40 | 0/24 | 1/8 | 0/5 | 52 | 2/35 | 1/35 | 1/12 |
| targeted-9303 | 461/840 | 1/40 | 0/24 | 1/8 | 0/5 | 43 | 1/35 | 3/35 | 4/12 |

## Développement nouveau

| Checkpoint | Points couverts / sondés | Axes retrouvés | Groupés | Croisés | Entrée occultée | Segments détectés | Bout près entrée / visibles | Bout près extrémité opposée du trait / visibles | Photos sans segment |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ordinary-9301 | 208/315 | 0/15 | 0/12 | 0/0 | 0/4 | 56 | 1/11 | 1/11 | 1/5 |
| ordinary-9302 | 170/315 | 0/15 | 0/12 | 0/0 | 0/4 | 73 | 0/11 | 0/11 | 1/5 |
| ordinary-9303 | 149/315 | 0/15 | 0/12 | 0/0 | 0/4 | 50 | 0/11 | 2/11 | 1/5 |
| targeted-9301 | 143/315 | 0/15 | 0/12 | 0/0 | 0/4 | 73 | 0/11 | 1/11 | 1/5 |
| targeted-9302 | 139/315 | 0/15 | 0/12 | 0/0 | 0/4 | 29 | 0/11 | 2/11 | 1/5 |
| targeted-9303 | 245/315 | 0/15 | 0/12 | 0/0 | 0/4 | 34 | 0/11 | 0/11 | 2/5 |

Un dénominateur nul indique une strate sans référence. La colonne « bout près » compte les références ayant au moins un bout détecté dans un rayon de 10 px. Le bout opposé est celui du tracé humain le plus éloigné du contact. Il peut se trouver sur le corps visible. L’axe est non orienté et la proximité seule n’identifie pas le côté qui pénètre dans le support.

Les coordonnées détaillées et distances sont conservées dans les [sorties par checkpoint](predictions/). Le [relevé numérique](diagnostic-tubes.json) donne aussi les effectifs de pics dont le bonus est nul et de scores effectivement inchangés. La mesure de continuité seule ne permet pas d’estimer la précision pixel à pixel du masque.
