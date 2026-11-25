# Cartes natives de la tête d’entrée

Chaque fichier q00 à q11 contient **deux matrices float32 sans perte**, `logits` et `p`. La probabilité a été calculée par le softmax Torch historique sur toute la grille de la requête. L’indexation des matrices est (y, x). Un fichier de requête peut être téléchargé indépendamment. Ces sorties numériques permettent de recomposer les rangs, les maxima locaux et les masses sur n’importe quelle cellule des deux vues autorisées. Les valeurs de décision et les résumés détaillés restent lisibles directement sur GitHub. [Mesures des 624 paires](../tableaux.md) · [Géométrie des disques et supports bilinéaires](../references.json) · [Calcul indépendant sur grille](../spatial.py)

| Provenance | Témoin au pas 96 | Dice au pas 96 | Témoin au pas 768 | Dice au pas 768 |
|---|---|---|---|---|
| Gagnon | [12 grilles](temoin-96-wc71488242) | [12 grilles](dice-96-wc71488242) | [12 grilles](temoin-768-wc71488242) | [12 grilles](dice-768-wc71488242) |
| Lynch | [12 grilles](temoin-96-5115614018) | [12 grilles](dice-96-5115614018) | [12 grilles](temoin-768-5115614018) | [12 grilles](dice-768-5115614018) |

Les quatre états des deux vues fournissent 96 cartes. Les fichiers JSON de chaque vue et état donnent le chemin, la taille et le SHA-256 de chaque carte, les log-probabilités exactes du support, les composantes des coûts et la géométrie de sa requête. Les champs denses de masque et d’axe ainsi que les poids sont conservés localement. [Données auxiliaires par paire](../paires) · [Inventaire local](../inventaire-local.json)
