# Figures R24

- [Triplets par provenance](provenances.svg). Chaque cellule donne le nombre d’identités à entrée connue dont une même requête satisfait masque, axe et entrée au pas 768. Les huit provenances sont présentées pour les trois graines et les trois configurations.
- [Pertes d’apprentissage](pertes.svg). Les six panneaux suivent les pertes du candidat, moyennées par blocs consécutifs de 32 mises à jour. Les composantes sont affichées avant pondération. Seule la présence a un coefficient de 0,25 dans la perte totale.
- [Gradients, écrêtage et déplacements](optimisation.svg). Les normes sont mesurées avant l’écrêtage global à 5. Les déplacements mesurent le changement effectif de poids après AdamW. L’échelle est propre à chaque panneau.

Les trois figures sont tracées depuis les registres numériques sans pixels photographiques. [Toutes les valeurs tracées](donnees.json) · [Mesures par photo et par graine](../mesures/README.md) · [Pertes séparées, normes et amplitudes](../apprentissage.md) · [Script de tracé](../presenter.py)
