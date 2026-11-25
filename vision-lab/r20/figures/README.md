# Figures R20

| Figure | Ce qu’elle mesure | Valeurs permettant le rejeu |
|---|---|---|
| [Neuf photos train, trois checkpoints](train.svg) | Appariements indépendants des masques, axes et entrées, puis triplets sur une même requête. Les trois graines conservent les mêmes dénominateurs. | [Synthèse](../mesures/synthese.json) · [Par identité et provenance](../photos-et-identites-A.md) |
| [Pertes et gradients](apprentissage.svg) | Une valeur par état jusqu’au pas 96, sans lissage. Les états 0 à 95 précèdent les mises à jour. L’état 96 est la mesure au contrôle terminal. | [96 mises à jour](../archives/B/progression.jsonl) · [Pertes terminales](../archives/B/0096/pertes.json) · [Normes terminales](../archives/B/0096/controle.json) |
| [Comparaison des champs d’entrée](champs-entree.svg) | Les deux axes indexent q0 à q11. La couleur est la demi-somme des écarts absolus entre les softmax spatiaux. Un cercle marque un maximum commun. Chaque matrice est symétrique et compte 66 paires distinctes. | [Pas 0](../archives/B/0000/similarites.json) · [Pas 96](../archives/B/0096/similarites.json) |
| [Deux flèches, deux requêtes](triplets.png) | À gauche, masque, axe et entrée R18. À droite, masque et fragments décodés de la requête et maximum de son champ d’entrée. Le score est calculé sur la photo entière. Les fenêtres affichent le détail des deux références. | [Mesures par paire au pas 96](../mesures/B-0096.json) · [Prédictions décodées](../archives/B/0096/predictions.json) · [Géométrie des panneaux R18](../../r18/figures/index.json) |

La planche reprend deux fenêtres de la photo publique de **renatomitra**. Le recadrage R18 et les superpositions R20 sont diffusés sous CC BY-SA 2.0. Les teintes transparentes suivent toute la prédiction décodée dans la fenêtre, y compris ses prolongements en zone ignorée. Les mesures de Dice utilisent uniquement la partie évaluable publiée par R18. [Photographie originale et attribution](https://www.flickr.com/photos/groovenite/24614689941) · [Licence](https://creativecommons.org/licenses/by-sa/2.0/) · [Notice R18](../../r18/sources.json)

Les nombres tracés, les cellules ayant un maximum commun et les deux références de planche figurent dans un fichier lisible. [Données des quatre figures](donnees.json) · [Générateur](../figurer.py) · [Commandes de reproduction](../reproduire.md)
