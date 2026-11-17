# Évolutions de LMarchery — séances et Desktop

> Document de cadrage, aucune fonctionnalité livrée par cette PR. Voir [ADR 028](adr/028-seances-flexibles-edition-desktop.md).

## Principes validés

- La séance est d'abord un carnet d'entraînement. Sa clôture masque les actions courantes ; un futur mode édition permettra des révisions après clôture, avec historique des scores et impacts.
- Le moteur et les décisions humaines restent la source des scores confirmés ; la vision artificielle ne produit que des suggestions.
- Configuration de séance au démarrage, **héritée par défaut par chaque nouvelle volée**. Modification volontaire à la demande, jamais demande de confirmation systématique à chaque volée.
- Distance, profil/diamètre de blason et nombre de flèches peuvent varier d'une volée à l'autre ; les anciennes volées conservent leur configuration effective.
- Presets réglementaires et configurations personnalisées coexistent ; affichage de la version de règlement et séparation des stats hétérogènes.
- Notes de séance, notes de volées, associations facultatives arc/jeu de flèches après séance et éventuel journal chronologique de réglages.
- Un mode compétition plus strict pourra être étudié indépendamment ; aucun dispositif de certification ou défi social n'est implicitement décidé.
- L'application Android fonctionne complètement hors ligne, que Desktop soit ouvert ou non.

## Chantiers et prérequis

1. Achever la validation physique de PR #22 avant toute migration V2 du Pixel.
2. Finaliser l'empilement catalogue / sauvegarde #23–#25, puis intégration SAF V2 séparée.
3. Proposer une migration domaine + SQLite + archive successeur pour les configurations par volée ; préserver exactement les imports V1/V2 et les anciennes séances.
4. Implémenter presets, édition explicite post-clôture, notes et matériel sans écrasement silencieux des résultats historiques.
5. Développer [LMarchery-Desktop](https://github.com/LounaMaili/LMarchery-Desktop) : lecture hors ligne d'abord, graphiques, photos, synchronisation locale live ensuite.

Ne pas intégrer les images au JSON V1/V2 : utiliser un manifeste futur versionné et des ressources distinctes. Une photo de blason appartient à une volée avec configuration explicitement identifiée.

## Définition de fini — volée suivante

Dans un test avec volées A (30 m, 80 cm, six flèches) et B :
- B hérite de A sans afficher de formulaire obligatoire ni créer de nouvel identifiant de profil par inadvertance.
- Un changement volontaire pour C (70 m, 122 cm) n'altère ni A ni B.
- D hérite de C tant que l'utilisateur n'a rien changé.
- Les scores restent calculés pour le bon profil par volée, après export/réimport et reprise hors ligne.
- Une séance historique homogène V1/V2 reste identique après migration, sans réécriture de l'archive d'origine.
