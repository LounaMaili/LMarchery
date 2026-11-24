# Tables R16 · Développement indépendant

B désigne le générateur R13 et V ses segments retenus à 0,5. Les flèches des tableaux indiquent B → V. Chaque checkpoint lit les mêmes onze photos.

## Quatre critères de décision

Les deux premières fractions comptent les segments retirés sur le nombre initial. Les deux suivantes comptent les appariements conservés sur le nombre initial. Les seuils sont respectivement 60 %, 60 %, 90 % et 90 %.

| Checkpoint | Vides retirés | Faux occupés retirés | Axes conservés | Bouts propres conservés | Sorties B → V | Médiane CPU V, µs |
|---|---|---|---|---|---|---|
| ordinary-9301 | 75/81 | 2/2 | 0/10 | 0/3 | 305 → 21 | 15.700 |
| ordinary-9302 | 75/80 | 2/2 | 0/10 | 0/3 | 304 → 20 | 15.790 |
| ordinary-9303 | 74/81 | 3/3 | 0/10 | 0/3 | 305 → 17 | 15.829 |
| targeted-9301 | 76/81 | 3/3 | 0/10 | 0/3 | 303 → 18 | 15.840 |
| targeted-9302 | 76/82 | 3/3 | 0/10 | 0/3 | 304 → 18 | 15.820 |
| targeted-9303 | 77/84 | 3/3 | 0/10 | 0/3 | 306 → 22 | 15.809 |

## Seuils contacts hérités de R13

Chaque rappel a 18 entrées visibles au dénominateur. Le budget sur onze photos autorise deux propositions non appariées au total. Les JSON conservent les seuils avec leur précision de calcul.

| Checkpoint | Bras | Seuil | Retrouvés / 18 | Propositions | Non appariées | Faux établis | Non résolues | Silences / 11 | Vides déclenchés / 4 | Budget tenu |
|---|---|---|---|---|---|---|---|---|---|---|
| ordinary-9301 | B | 0.9498454771 | 2/18 | 5 | 3 | 0 | 3 | 8 | 0 | non |
| ordinary-9301 | V | 0.9498454771 | 2/18 | 4 | 2 | 0 | 2 | 9 | 0 | oui |
| ordinary-9302 | B | 0.9717186614 | 1/18 | 1 | 0 | 0 | 0 | 10 | 0 | oui |
| ordinary-9302 | V | 0.9717186614 | 1/18 | 1 | 0 | 0 | 0 | 10 | 0 | oui |
| ordinary-9303 | B | 0.9512959729 | 2/18 | 4 | 2 | 1 | 1 | 9 | 1 | oui |
| ordinary-9303 | V | 0.9512959729 | 1/18 | 2 | 1 | 0 | 1 | 10 | 0 | oui |
| targeted-9301 | B | 0.9611005587 | 1/18 | 4 | 3 | 0 | 3 | 9 | 0 | non |
| targeted-9301 | V | 0.9611005587 | 1/18 | 3 | 2 | 0 | 2 | 10 | 0 | oui |
| targeted-9302 | B | 0.9765462257 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |
| targeted-9302 | V | 0.9765462257 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |
| targeted-9303 | B | 0.9514127483 | 0/18 | 2 | 2 | 0 | 2 | 9 | 0 | oui |
| targeted-9303 | V | 0.9514127483 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |

## Seuils contacts choisis sur ces onze photos

Chaque rappel a 18 entrées visibles au dénominateur. Le budget sur onze photos autorise deux propositions non appariées au total. Les JSON conservent les seuils avec leur précision de calcul.

| Checkpoint | Bras | Seuil | Retrouvés / 18 | Propositions | Non appariées | Faux établis | Non résolues | Silences / 11 | Vides déclenchés / 4 | Budget tenu |
|---|---|---|---|---|---|---|---|---|---|---|
| ordinary-9301 | B | 0.9955698794 | 1/18 | 1 | 0 | 0 | 0 | 10 | 0 | oui |
| ordinary-9301 | V | 0.9575244391 | 2/18 | 4 | 2 | 0 | 2 | 9 | 0 | oui |
| ordinary-9302 | B | 0.9526875078 | 3/18 | 4 | 1 | 0 | 1 | 9 | 0 | oui |
| ordinary-9302 | V | 0.9526875078 | 3/18 | 4 | 1 | 0 | 1 | 9 | 0 | oui |
| ordinary-9303 | B | 0.9542124414 | 2/18 | 4 | 2 | 1 | 1 | 9 | 1 | oui |
| ordinary-9303 | V | 0.9542124414 | 1/18 | 2 | 1 | 0 | 1 | 10 | 0 | oui |
| targeted-9301 | B | 0.9938745809 | 1/18 | 1 | 0 | 0 | 0 | 10 | 0 | oui |
| targeted-9301 | V | 0.9601095315 | 3/18 | 5 | 2 | 0 | 2 | 9 | 0 | oui |
| targeted-9302 | B | 2.0000000000 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |
| targeted-9302 | V | 2.0000000000 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |
| targeted-9303 | B | 2.0000000000 | 0/18 | 0 | 0 | 0 | 0 | 11 | 0 | oui |
| targeted-9303 | V | 0.9432766732 | 1/18 | 3 | 2 | 1 | 1 | 8 | 1 | oui |

## Provenances et conditions

Chaque ligne contient les comptes dans les deux bras. Le champ « non résolus sur occupées » regroupe les segments sans lecture et les lectures incertaines. Un segment compatible avec un axe peut être inclus dans ce total. Les lignes de conditions peuvent se recouper. Les faux des tableaux de contacts ont pour unité une proposition d’entrée, les faux géométriques un segment examiné.

Les JSON associés donnent aussi le détail des contacts à chaque seuil, chaque inventaire, les rangs documentaires hors sortie et les voisins par instance.

### ordinary-9301

[Mesures et rangs](mesures/ordinary-9301.json) · [Prédictions individuelles](predictions/ordinary-9301.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 11 | 0 → 0 | 0 → 0 | 127 → 11 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 174 → 12 | 46 → 5 | 1 → 0 | 126 → 7 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 81 → 6 | 81 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 46 → 5 | 46 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 224 → 15 | 0 → 0 | 2 → 0 | 221 → 15 | 10 → 0 | 3 → 0 | 29 / 3 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 12 → 0 | 12 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 5 | 32 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 3 | 0 → 0 | 1 → 0 | 31 → 3 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| N Chadwick | 1 | 23 → 1 | 23 → 1 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 0 → 0 | 0 → 0 | 4 / 1 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 4 | 0 → 0 | 0 → 0 | 32 → 4 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 14 → 0 | 14 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 4 | 0 → 0 | 0 → 0 | 31 → 4 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |

### ordinary-9302

[Mesures et rangs](mesures/ordinary-9302.json) · [Prédictions individuelles](predictions/ordinary-9302.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 11 | 0 → 0 | 0 → 0 | 127 → 11 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 174 → 11 | 46 → 4 | 1 → 0 | 126 → 7 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 80 → 5 | 80 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 46 → 4 | 46 → 4 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 224 → 15 | 0 → 0 | 2 → 0 | 220 → 15 | 10 → 0 | 3 → 0 | 29 / 2 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 12 → 0 | 12 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 4 | 32 → 4 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 3 | 0 → 0 | 1 → 0 | 31 → 3 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| N Chadwick | 1 | 22 → 1 | 22 → 1 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 31 → 1 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 4 | 0 → 0 | 0 → 0 | 32 → 4 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 14 → 0 | 14 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 4 | 0 → 0 | 0 → 0 | 31 → 4 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |

### ordinary-9303

[Mesures et rangs](mesures/ordinary-9303.json) · [Prédictions individuelles](predictions/ordinary-9303.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 7 | 0 → 0 | 0 → 0 | 127 → 7 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 174 → 10 | 46 → 6 | 1 → 0 | 126 → 4 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 81 → 7 | 81 → 7 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 46 → 6 | 46 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 224 → 10 | 0 → 0 | 3 → 0 | 220 → 10 | 10 → 0 | 3 → 0 | 28 / 3 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 11 → 0 | 11 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 6 | 32 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 2 | 0 → 0 | 2 → 0 | 30 → 2 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| N Chadwick | 1 | 24 → 1 | 24 → 1 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 0 → 0 | 0 → 0 | 4 / 1 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 3 | 0 → 0 | 0 → 0 | 32 → 3 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 14 → 0 | 14 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 31 → 1 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |

### targeted-9301

[Mesures et rangs](mesures/targeted-9301.json) · [Prédictions individuelles](predictions/targeted-9301.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 10 | 0 → 0 | 0 → 0 | 127 → 10 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 174 → 11 | 46 → 5 | 1 → 0 | 126 → 6 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 81 → 5 | 81 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 46 → 5 | 46 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 222 → 13 | 0 → 0 | 3 → 0 | 218 → 13 | 10 → 0 | 3 → 0 | 28 / 3 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 12 → 0 | 12 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 5 | 32 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 2 | 0 → 0 | 2 → 0 | 30 → 2 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| N Chadwick | 1 | 23 → 0 | 23 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 30 → 1 | 0 → 0 | 0 → 0 | 30 → 1 | 0 → 0 | 0 → 0 | 4 / 1 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 4 | 0 → 0 | 0 → 0 | 32 → 4 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 14 → 0 | 14 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 3 | 0 → 0 | 0 → 0 | 31 → 3 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |

### targeted-9302

[Mesures et rangs](mesures/targeted-9302.json) · [Prédictions individuelles](predictions/targeted-9302.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 10 | 0 → 0 | 0 → 0 | 127 → 10 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 175 → 14 | 47 → 5 | 1 → 0 | 126 → 9 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 82 → 6 | 82 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 47 → 5 | 47 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 222 → 12 | 0 → 0 | 3 → 0 | 218 → 12 | 10 → 0 | 3 → 0 | 28 / 3 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 12 → 0 | 12 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 5 | 32 → 5 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 1 | 0 → 0 | 2 → 0 | 30 → 1 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| N Chadwick | 1 | 23 → 1 | 23 → 1 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 30 → 1 | 0 → 0 | 0 → 0 | 30 → 1 | 0 → 0 | 0 → 0 | 4 / 1 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 15 → 0 | 15 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 6 | 0 → 0 | 0 → 0 | 31 → 6 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |

### targeted-9303

[Mesures et rangs](mesures/targeted-9303.json) · [Prédictions individuelles](predictions/targeted-9303.json)

| Provenance ou condition | Photos | Sorties | Parasites sur vides | Faux occupés | Non résolus sur occupées | Axes stricts | Entrées au bon bout | Hors sortie négatifs / tubes / incertains |
|---|---|---|---|---|---|---|---|---|
| grouped | 4 | 128 → 12 | 0 → 0 | 0 → 0 | 127 → 12 | 9 → 0 | 2 → 0 | 17 / 2 / 0 |
| worn | 6 | 175 → 16 | 47 → 6 | 1 → 0 | 126 → 10 | 9 → 0 | 2 → 0 | 24 / 2 / 0 |
| empty | 4 | 84 → 7 | 84 → 7 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 15 / 0 / 0 |
| occlusion | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| vides_uses | 2 | 47 → 6 | 47 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 8 / 0 / 0 |
| occupees | 7 | 222 → 15 | 0 → 0 | 3 → 0 | 218 → 15 | 10 → 0 | 3 → 0 | 28 / 3 / 0 |
| Archery Scoring | 1 | 32 → 1 | 0 → 0 | 0 → 0 | 32 → 1 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| DWZ | 1 | 12 → 0 | 12 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Jim Barton | 1 | 32 → 6 | 32 → 6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| Moo.The.Cow | 1 | 32 → 2 | 0 → 0 | 2 → 0 | 30 → 2 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| N Chadwick | 1 | 25 → 1 | 25 → 1 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 3 / 0 / 0 |
| Ozizo | 1 | 30 → 1 | 0 → 0 | 0 → 0 | 30 → 1 | 0 → 0 | 0 → 0 | 4 / 1 / 0 |
| Special Olympics Oesterreich | 1 | 32 → 0 | 0 → 0 | 1 → 0 | 31 → 0 | 1 → 0 | 1 → 0 | 4 / 0 / 0 |
| Voltmetro | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 1 → 0 | 1 → 0 | 5 / 0 / 0 |
| arrowcounting | 1 | 15 → 0 | 15 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 4 / 0 / 0 |
| juantiagues | 1 | 32 → 2 | 0 → 0 | 0 → 0 | 32 → 2 | 6 → 0 | 0 → 0 | 4 / 1 / 0 |
| surtr | 1 | 32 → 7 | 0 → 0 | 0 → 0 | 31 → 7 | 1 → 0 | 0 → 0 | 3 / 1 / 0 |
