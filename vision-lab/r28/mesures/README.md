# Mesures R28

L’unité est un état d’une photo du train. Chaque JSON par photo contient ses douze sorties et toutes ses identités, avec une ligne par couple identité–sortie. Les coordonnées xyxy sont flottantes en pixels natifs, q commence à zéro et le score final garde sa précision archivée. Le logit RPN reste distinct du score final.

[Reproduction des quatre contrôles R27](verification.json) · [Comparaisons par contrôle, provenance et photo](comparaison.json) · [Toutes les trajectoires](trajectoires.json) · [Pixels appariés, incidences et unions](partiel.json) · [Empreintes et contrôle des collages](../supports/index.json)

| Photo | Provenance | Identités | Contrôle 1 | Contrôle 2 | Contrôle 3 | Contrôle 4 |
| --- | --- | --- | --- | --- | --- | --- |
| 13-01 | Crigges | 1 | [9302 / 512](couples/9302-512-13-01.json) | [9302 / 768](couples/9302-768-13-01.json) | [9303 / 512](couples/9303-512-13-01.json) | [9303 / 768](couples/9303-768-13-01.json) |
| 70-01 | Crigges | 1 | [9302 / 512](couples/9302-512-70-01.json) | [9302 / 768](couples/9302-768-70-01.json) | [9303 / 512](couples/9303-512-70-01.json) | [9303 / 768](couples/9303-768-70-01.json) |
| 7245527662 | jrladia | 4 | [9302 / 512](couples/9302-512-7245527662.json) | [9302 / 768](couples/9302-768-7245527662.json) | [9303 / 512](couples/9303-512-7245527662.json) | [9303 / 768](couples/9303-768-7245527662.json) |
| 5115614018 | Danielle Walquist Lynch | 5 | [9302 / 512](couples/9302-512-5115614018.json) | [9302 / 768](couples/9302-768-5115614018.json) | [9303 / 512](couples/9303-512-5115614018.json) | [9303 / 768](couples/9303-768-5115614018.json) |
| 18105652008 | zigazou76 | 2 | [9302 / 512](couples/9302-512-18105652008.json) | [9302 / 768](couples/9302-768-18105652008.json) | [9303 / 512](couples/9303-512-18105652008.json) | [9303 / 768](couples/9303-768-18105652008.json) |
| 24614689941 | renatomitra | 3 | [9302 / 512](couples/9302-512-24614689941.json) | [9302 / 768](couples/9302-768-24614689941.json) | [9303 / 512](couples/9303-512-24614689941.json) | [9303 / 768](couples/9303-768-24614689941.json) |
| 4169106197 | cristina.sanvito | 1 | [9302 / 512](couples/9302-512-4169106197.json) | [9302 / 768](couples/9302-768-4169106197.json) | [9303 / 512](couples/9303-512-4169106197.json) | [9303 / 768](couples/9303-768-4169106197.json) |
| wc71488242 | Bernard Gagnon | 9 | [9302 / 512](couples/9302-512-wc71488242.json) | [9302 / 768](couples/9302-768-wc71488242.json) | [9303 / 512](couples/9303-512-wc71488242.json) | [9303 / 768](couples/9303-768-wc71488242.json) |
| wc11001401 | 4028mdk09 | 5 | [9302 / 512](couples/9302-512-wc11001401.json) | [9302 / 768](couples/9302-768-wc11001401.json) | [9303 / 512](couples/9303-512-wc11001401.json) | [9303 / 768](couples/9303-768-wc11001401.json) |

Le champ `sorties` donne le q RPN ancêtre et les boîtes, scores, aires et statuts des deux masques. Une fusion est une sortie couvrant au moins 25 % de deux identités. Un dédoublement est une identité couverte à au moins 25 % par deux sorties. Ces deux compteurs peuvent se recouper. Le statut exclusif de chaque sortie non appariée suit la priorité R27.

Chaque entrée de `identites` donne le meilleur RPN, le meilleur q selon la boîte, le meilleur q selon le Dice, chaque liste d’arêtes et chaque attribution. Les douze `couples` donnent les marges, les partitions de pixels des deux masques, I et la borne. Les champs `admissibles` et `separes` désignent des possibilités locales. Les champs `q_admis`, `q_separe` et `q_dice_total` désignent trois attributions globales distinctes.

Les sous-tables `memes_couples` fixent q avant de comparer. Les autres compteurs de rappel utilisent le réappariement indépendant. Dans `partiel.json`, la médiane ordinaire porte sur les associations présentes avec une aire positive. La fraction pondérée est le rapport des sommes. Chaque union conserve un repère distinct par photo et compte une seule fois chaque position, même si plusieurs q, pas ou graines la couvrent dans le groupe.

Le Dice et les statuts conservent l’étiquette 255 hors de l’aire évaluable. Les sorties sans attribution admissible sont décrites dans les compteurs historiques. Les zéros de témoins vides indiquent une absence de tels témoins dans ces neuf vues occupées. Le contact physique, une courbe selon le seuil de score et le rappel hors train ne sont pas mesurés par ce diagnostic.

[Tables par identité](../identites.md) · [Transitions](../transitions.md) · [Comparaison par provenance](../comparaison.md) · [Portée de l’annotation](../annotation.md) · [Figures](../figures/README.md)
