# R8 — protocole avant apprentissage

Point de départ R7 `33c12ba263bd2cd53be720045c533e54c7a4ee15`. Référence gelée ImageNet `672d6b0b24c9713e6b8aa4df34a2cf3d5718f182de777e6032e3156fa06c8b27`. Le diagnostic initial reprend les pics R7 enregistrés aux rangs 00 et 01, puis rejoue les cartes avec et sans troncature. L’appariement R6 maximise le nombre de contacts à 10 px dans la vue à 704 px et minimise ensuite la distance. Deux rangs de confiance sont lus par photo.

Les 12 vues de mémorisation proviennent des séquences Crigges 13, 70, 90 et 101 aux rangs 00, 01 et 03. Toutes restent sur le support numéro 4. L’inventaire des groupes porte sur les instances visibles dans l’image courante. La propagation par homographie suivie d’une inspection des pixels est une lecture assistée. Le troisième contact de 70-03 garde une zone ignorée. Le masque de tube du dernier ajout conserve sa sémantique R7 dans tous les bras.

Les nouvelles photos, les groupes de photographes et les supports sont scellés avant apprentissage dans le manifeste local. Trois photos nouvelles sont affectées au train, trois au développement et quatre au test. Les trois groupes positifs du test restent réservés jusqu’à adjudication humaine. Les sept photos externes de R7 restent historiques.

| Bras | Six fenêtres communes | Deux fenêtres complémentaires par lot |
|---|---|---|
| A, supervision partielle | Sur les vues groupées, seul le dernier contact lisible possède une supervision locale positive. Le reste est ignoré. | Une fenêtre positive du même support et une fenêtre vide. |
| B, supervision complétée | Même tirage, carte de tous les contacts visibles et fond supervisé hors incertitude. | Mêmes pixels que A, inventaire complété pour la tête de contacts. |
| C, supports ajoutés | Mêmes six fenêtres et mêmes annotations que B. | Une fenêtre positive et une fenêtre vide sur les nouveaux supports de train. |

Les deux fenêtres complémentaires supervisent uniquement la tête contact dans les trois bras. Cette disposition évite de transformer un tracé d’axe externe en masque. Le tirage des six fenêtres communes a sa propre suite aléatoire. Les changements de distribution du bras C caractérisent le lot ajouté dans son ensemble.

Chaque essai repart du même checkpoint entraîné R7. Architecture, normes, résolution, pertes, plafond de 96, fusion des tuiles et décodage sont ceux de R7. AdamW neuf, taux 0,001, quatre threads CPU, batch huit. Budget ferme de 64 mises à jour. Aux pas 0, 16, 32, 48 et 64, mesurer les photos contrôlées entières à 0,50 et 0,90, y compris les groupes et les vides. L’époque finale est fixe. Compter par tête les pixels effectivement positifs, négatifs, ignorés et la masse positive pondérée reçue.

Comparer A, B et C à la graine 8301. Choisir sur les trois photos de développement au dernier pas, par nombre de contacts appariés au budget de 0,25 non-appariée/photo, puis moins de non-appariées. En cas d’égalité, retenir le meilleur rappel groupé contrôlé à 0,90, puis le moins de faux contrôlés à ce seuil, puis le premier bras dans l’ordre A, B, C. Rejouer le bras retenu avec les graines 8302 et 8303. Ces graines modifient les fenêtres et symétries. Pour chaque graine, appliquer la même sélection de seuil R7 sur développement. Les comparaisons sont descriptives et concernent ces acquisitions.

Les critères GO R6 sont conservés. Il faut au moins 70 % de rappel réel, au moins 50 % sur usés et groupés, au plus 0,25 non-appariée par photo et aucun point sur témoins vides ou contacts occultés, avec test externe adjugé. Le NO-GO Android R7 reste en vigueur pendant ce pilote.
