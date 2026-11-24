# Figures R16

Les quatre graphiques représentent les onze photos du développement scellé. B désigne le générateur R13. V applique le vérificateur à ses sorties. Les six checkpoints lisent les mêmes acquisitions. Les fichiers de tracé contiennent exclusivement des mesures agrégées.

| Figure | Lecture | Données numériques |
|---|---|---|
| [Critères de décision](criteres.png) | Une ligne par checkpoint. À gauche, fractions retirées sur les vides et parmi les faux établis des occupées. À droite, fractions conservées des appariements stricts et des entrées au bout de leur propre segment. Les lignes pointillées marquent les minima R14. | [Numérateurs et dénominateurs](../mesures/synthese.json) |
| [Rappel et propositions non appariées](contacts.png) | Ensemble des onze photos. Les croix indiquent le seuil hérité et les cercles le seuil R9 choisi sur le développement. L’abscisse compte les propositions non appariées par photo. | [Points par seuil et strate](../mesures/courbes.csv) · [Seuils exacts](../mesures/synthese.json) |
| [Rappel par provenance occupée](provenances.png) | Sept provenances et deux conditions transversales. Les ordonnées ont pour dénominateur les entrées visibles de chaque strate. Un trait par bras et checkpoint. | [Points par seuil et provenance](../mesures/courbes.csv) |
| [Déclenchements sur les témoins](temoins.png) | Quatre photos vides. L’ordonnée donne le nombre de propositions par photo à chaque seuil. Jim Barton et arrowcounting ont un support usé. | [Points par seuil et provenance](../mesures/courbes.csv) |

Les lignes relient les seuils effectivement évalués. Seuls les points mesurés servent au choix de seuil. Les courbes par provenance s’arrêtent à 1. Le seuil d’abstention 2 figure dans le CSV. Les compteurs entiers permettent de reconstruire chaque rappel par `matched / known` et chaque abscisse de la figure d’ensemble par `unmatched / photos`. Une photo vide a un rappel indéfini. [Script de tracé](../figurer.py) · [Commandes de reproduction](../reproduire.md)
