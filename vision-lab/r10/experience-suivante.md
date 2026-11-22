# Expérience proposée · Le plafond de 96 pics masque-t-il des contacts ?

**Statut au 22 novembre 2026. Protocole fixé, comparaison non exécutée.** Cette expérience porte sur la couverture des coordonnées déjà produites par un modèle figé. Les six essais R9 fournissent tous 96 pics par photo, mais dix entrées visibles sont hors tolérance dans les six listes. La réserve externe reste fermée.

## Question et variable isolée

Sur les mêmes 12 photos de développement, passer de 96 à 192 pics de contact par photo permet-il de récupérer plusieurs entrées visibles supplémentaires sur plusieurs provenances ? L’hypothèse porte sur la troncature de fin de décodage. Une carte qui ne comporte aucun maximum admissible près d’une entrée ne bénéficiera pas de cette modification.

Comparer les deux préfixes imbriqués d’une même liste triée. Garder les six checkpoints R9 du pas 96 (ordinaire et ciblé, graines 9301, 9302, 9303), la résolution, les tuiles, la fusion, les scores, la détection des maxima, le plancher 0,05, les ex æquo et l’ordre secondaire par coordonnées du [décodeur R7](../r7/model.py). Conserver les références assistées R9 et l’appariement un à un à 10 px dans le repère R6. Le seul paramètre modifié est la longueur du préfixe. Vérifier pour les 72 listes que les 96 premiers points et leurs scores reproduisent les archives R9. Documenter toute divergence avant d’interpréter le contraste.

## Budget commun

Une inférence par couple checkpoint/photo, soit au plus 72 passages sur photo entière, un seul processus à la fois, zéro mise à jour de poids et deux décodages de préfixe sur les mêmes cartes. Le doublement donne à chaque photo au plus 96 occasions supplémentaires et conserve un seul contraste. Les douze photos et les six checkpoints sont fixés, sans ajout de graine, d’acquisition, de longueur ou de seuil après lecture. Un modèle peut fournir moins de 192 maxima après le plancher de score. Consigner le nombre réel.

## Critère de décision

Pour chaque checkpoint, calculer le gain du nombre de contacts visibles appariés sur 35 entre 96 et 192. Compter une provenance gagnante si sa cardinalité appariée augmente. Publier la liste des nouvelles entrées admissibles, en identifiant celles qui n’avaient de voisin dans aucun des six essais R9. La liste des dix entrées est figée sous `sans_candidat_six_essais` dans les [mesures du diagnostic](mesures.json).

Retenir la troncature comme **limite matérielle sur ce développement** seulement si les deux conditions suivantes sont réunies :

1. Au moins quatre des six checkpoints gagnent chacun au moins quatre contacts (4/35, soit 11,4 points de rappel potentiel) répartis sur au moins deux provenances pour chacun.
2. Au moins deux des dix entrées jusque-là absentes acquièrent un candidat appariable, sur deux provenances distinctes, dans au moins une des listes élargies chacune.

Si l’une des conditions échoue, écarter **au budget testé** l’hypothèse d’un effet de cette ampleur. Les six checkpoints partagent les mêmes acquisitions. Cette règle mesure une répétition sur ce développement déjà examiné. Elle ne fournit pas une preuve externe.

## Mesures secondaires et portée

Pour chaque préfixe, publier le nombre réel de pics, le rappel maximal, les faux établis et non résolus, les témoins vides et les strates usée et groupée. À titre descriptif, reproduire 0,50 et le seuil de décision R9 de chaque essai. Indiquer le coût réel du premier contact au classement global. Conserver les scores dans leur ordre d’origine. Le critère primaire porte sur le rappel accessible à un éventuel classement parfait. Un gain de couverture ne vaut pas un gain au budget de 0,25 non-appariée par photo. Les critères GO R6 et le NO-GO Android restent applicables.
