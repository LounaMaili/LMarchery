# R11 · Comparaison des préfixes 96 et 192

La [règle de décision publiée dans R10](https://github.com/LounaMaili/LMarchery/blob/edd8e28505a84ec42d98f42cf4334576b931714e/vision-lab/r10/experience-suivante.md) s’applique intégralement. Les six checkpoints sont ceux du pas 96, bras ordinaire et ciblé, graines 9301, 9302 et 9303. Les [empreintes des poids](../r9/inventaire-local.json), les [sources des photos](../r9/sources.json), les [empreintes des archives de développement](../r10/entrees.json) et les [dix entrées fixées](../r10/mesures.json) précèdent l’exécution.

## Exécution et contrôle de parité

Vérifier les six poids, les six archives de pics, le manifeste de développement et les douze images orientées par SHA-256 avant la première inférence. Charger en CPU dans le format mémoire R9, en évaluation déterministe avec quatre threads Torch et un thread OpenCV. Un seul processus appelle la fusion R7 une fois pour chaque couple checkpoint/photo. Budget total de 72 appels, zéro entraînement. Conserver sur disque les coordonnées et scores décodés pour tout calcul ultérieur.

Extraire une seule liste par carte avec le décodeur R7, en portant uniquement sa longueur maximale à 192. Les deux préfixes occupent les 96 et 192 premières positions disponibles dans son ordre historique, y compris la règle secondaire sur les coordonnées à score égal. Comparer dans l’ordre les coordonnées et scores du premier préfixe à l’archive R9, sans arrondi ni tolérance numérique. Vérifier également l’empreinte de chaque fichier des 12 listes courtes après sérialisation au format R9. Toute divergence suspend l’interprétation et doit être documentée avant le contraste.

## Comptage et décision

La couverture compte les correspondances un à un avec les entrées visibles dans le repère R6 à 10 px. Une provenance gagne si ce nombre augmente. Une entrée nouvellement admissible possède au moins un voisin dans la tolérance à 192 et aucun à 96 dans cet essai. Distinguer cette admissibilité de l’association retenue lorsqu’un candidat est partagé. Pour chacune des dix entrées initialement absentes, compter une seule acquisition de voisin même si plusieurs checkpoints la retrouvent. Publier le meilleur rang et score du voisin et l’affectation un à un par essai. Les strates `grouped`, `worn`, les témoins vides et les cas non résolus conservent les définitions de R9.

Les deux conditions sont conjointes. Au moins quatre checkpoints doivent gagner chacun au moins quatre entrées appariées sur au moins deux provenances. Au moins deux des dix entrées initialement absentes doivent acquérir un candidat sur deux provenances distinctes au total. Les seuils descriptif 0,50 et de décision R9 restent fixés. Le coût global du premier contact est le nombre de propositions non appariées au plus haut score admettant un voisin, en incluant les ex æquo de l’ensemble du développement. Les gains du critère primaire portent sur les listes entières.

Clore R11 après le bilan et une recommandation. Le test réservé reste fermé et le NO-GO Android est maintenu.
