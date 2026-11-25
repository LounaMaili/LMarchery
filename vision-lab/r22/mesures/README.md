# Mesures R22 sur les neuf photos d’apprentissage

Les fichiers candidat contiennent les douze requêtes de chaque photo, la matrice des paires identité–requête, les appariements historiques, les erreurs d’entrée et le statut des propositions non appariées. Les fichiers du témoin sont les mesures R21 vérifiées lors du gel. Les identités et provenances sont les mêmes pour les deux bras.

| Graine | Témoin 96 | Témoin 768 | Dice 96 | Dice 768 |
|---|---|---|---|---|
| 9301 | [Témoin 96](../../r21/mesures/temoin-9301-96.json) | [Témoin 768](../../r21/mesures/temoin-9301-768.json) | [Dice 96](dice-9301-96.json) | [Dice 768](dice-9301-768.json) |
| 9302 | [Témoin 96](../../r21/mesures/temoin-9302-96.json) | [Témoin 768](../../r21/mesures/temoin-9302-768.json) | [Dice 96](dice-9302-96.json) | [Dice 768](dice-9302-768.json) |
| 9303 | [Témoin 96](../../r21/mesures/temoin-9303-96.json) | [Témoin 768](../../r21/mesures/temoin-9303-768.json) | [Dice 96](dice-9303-96.json) | [Dice 768](dice-9303-768.json) |

- Les tableaux donnent les effectifs et les trois comptes de pixels par graine, photo, provenance et type d’inventaire. Les groupes JSON ajoutent les quantiles des Dice, fractions du plafond, VP, FP et FN. [Tableaux](../tableaux.md) · [Groupes et quantiles](groupes.json)
- Pour chaque identité, les indices q, les champs disponibles, le meilleur masque isolé, celui attribué par R19 et les pixels natifs sont disponibles aux deux pas. Les transitions distinguent gains, pertes et conservation. [Identités](../identites.md) · [Données par état](../identites) · [Transitions](../transitions.md) · [Contrastes numériques](contrastes.json) · [Reculs par groupe](../reculs.md)
- Les trois journaux candidats publient les présentations dans leur ordre réel. L’agrégat comporte les périodes 1–96, 97–768 et 1–768, avec le nombre de gradients écrêtés et les pertes de chaque tête. [9301](../archives/dice/9301/progression.jsonl) · [9302](../archives/dice/9302/progression.jsonl) · [9303](../archives/dice/9303/progression.jsonl) · [Agrégat](../pertes-et-expositions.json)
