# Mesures spatiales R23

Les huit fichiers détaillés contiennent 624 paires identité–requête. Chaque paire donne les quatre cellules du support bilinéaire avec leur poids, leur probabilité spatiale, leur log-probabilité historique et leurs rangs. Les sommes par disque, maxima locaux stricts et rangs sont calculés sur la grille entière. Les relations avec le masque, l’axe, les coûts et les appariements se trouvent dans la même ligne JSON. [Tables Markdown par identité et requête](../tableaux.md) · [Effectifs, faux établis, cas non résolus et abstentions](effectifs.json)

| État | Bernard Gagnon, 8 entrées connues | Danielle Walquist Lynch, 5 entrées connues |
|---|---|---|
| Témoin 96 | [96 paires](temoin-96-wc71488242.json) | [60 paires](temoin-96-5115614018.json) |
| Dice 96 | [96 paires](dice-96-wc71488242.json) | [60 paires](dice-96-5115614018.json) |
| Témoin 768 | [96 paires](temoin-768-wc71488242.json) | [60 paires](temoin-768-5115614018.json) |
| Dice 768 | [96 paires](dice-768-wc71488242.json) | [60 paires](dice-768-5115614018.json) |

Le champ `q_perte` est l’affectation de l’identité après la mise à jour du contrôle. `pic_decode` est l’argmax de probabilité de la requête. `q_entree_geometrique` désigne l’appariement un à un de couverture R19 sur les douze pics. La valeur `null` signifie qu’aucune sortie n’a été attribuée dans ce champ. Chaque fichier distingue le meilleur logit du disque, son rang dans toute la grille, son statut local et la masse de toutes ses cellules. [Concurrence nº 11 / nº 9](../concurrence.md) · [Entrées détaillées et perte recomposée](../supports.md) · [Matrices sources](../cartes/README.md)
