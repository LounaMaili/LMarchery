# Figures R27

| Figure | Unité et source |
|---|---|
| [De la proposition au masque séparé](capacite.svg) | Cinq contrôles prévus. Rappel des boîtes en nombre d’identités, effectif de masques, Dice médian et nombre de sorties fusionnées. [Données numériques](../mesures/courbes.json) |
| [Masques séparés par provenance](provenances.svg) | Chaque panneau indique son propre dénominateur. [Données numériques](../mesures/groupes.json) |
| [Conservation des identités entre 512 et 768](stabilite.svg) | Chaque ligne suit une identité physique dans les deux graines complètes. [Ensembles séparés](../mesures/courbes.json) · [Renouvellements et Jaccard](../mesures/decision.json) |
| [Pertes et gradients par cycle](optimisation.svg) | Moyenne des pas réalisés pendant chaque cycle. Le premier cycle de 9301 comporte trois pas, les cycles des deux autres graines en comportent huit. [Données numériques](../mesures/optimisation.json) |

Le cercle ouvert de 9301 se superpose aux points initiaux des deux autres graines sur les courbes d’évaluation. Aucune interpolation ne remplace les mesures manquantes. [Code de génération](../figurer.py)
