# ADR 023 — Historique chronologique et présentation des scores Android

## Portée

Améliorer la consultation du parcours des [ADR 018](018-integration-android-room.md)
à [022](022-import-json-controle-transactionnel.md), sans changer domaine, scoring,
profils, transactions, schéma Room ou format d’archive. Aucun nouveau mode de saisie.

## Accueil et dates

Les séances lisibles sont triées par `Session.date.toInstant()` décroissant, puis
UUID lexical croissant en cas d’égalité. La révision `updatedAt` ne déplace pas une
séance dans l’historique. La carte conserve l’heure civile, le fuseau IANA et
l’offset d’origine : les deux occurrences d’une heure répétée restent distinguables.

« Séances à reprendre » regroupe DRAFT et IN_PROGRESS ; « Historique » regroupe
COMPLETED. Les entrées illisibles ou accompagnées d’un problème restent dans
« Séances nécessitant une intervention », avec UUID, diagnostic et relecture
explicite. Une découverte échouée reste une erreur, jamais une liste vide réussie.
Chaque section indique aussi son absence d’entrées. « Nouvelle séance » est en tête.

Les cartes donnent titre, date, profil, distance, statut, nombre de volées validées
et total validé. « Reprendre » est réservé aux configurations éditables existantes ;
les autres sont proposées en « Consulter », sans conversion ou écriture.

## Tableau et résumé

`presentEndScore` adapte chaque volée sans trier ses flèches : l’ordre stocké et
les index réels restent visibles, avec M et 10X selon les indicateurs finaux.
`calculateEndScore(end)` fournit le total ; une volée vide montre « Vide » et un
tiret plutôt qu’un zéro assimilable à une volée validée. EDITING, même complète,
reste provisoire ; seule la commande de validation existante change son statut.

`presentSessionScore` utilise exclusivement `summarizeSessionScore(session)` pour
les totaux, compteurs et moyennes. Seules les volées VALIDATED y contribuent.
Les nombres de 10 (ou du maximum réel pour un autre profil), 9, M et X si activé
sont issus du résumé métier. Une moyenne absente est dite indisponible, sans faux
zéro. Les labels distinguent séance en cours et bilan terminé.

`maxPossibleScore` est affiché comme **maximum de référence**, avec sa portée :
volées prévues si renseignées, sinon enregistrées (y compris vides/provisoires)
pour FIXED ; flèches enregistrées pour FREE. Aucun pourcentage de réussite ni
rapport trompeur avec les seules flèches validées n’est présenté.

## Navigation et saisie

Le tableau sélectionne les blasons par UUID. Sur une séance active, consulter
une ancienne volée donne « Revenir à la volée en cours ». Les gardes existantes
interdisent toute modification d’une volée validée ou séance terminée. Une
sélection/correction et un PendingSave bloquent la navigation jusqu’à résolution
explicite ; ils ne sont pas abandonnés par le tableau.

Titre/statut, scores validé/provisoire et compte de flèches précèdent le blason.
Les commandes, corrections, tableau, statistiques et métadonnées suivent. Les
messages SQL et actions de sauvegarde en attente gardent leur priorité visible.
Le blason a une largeur maximale de 480 dp et reste carré ; en paysage, un
défilement vertical est nécessaire. Les lignes et commandes peuvent revenir à la
ligne, sans grille de largeur fixe ni masquage des commandes sur écran étroit.

L’état de défilement Compose est restaurable par écran/UUID de séance, jamais
par référence d’objet `Session`. Un acquittement SQL ne recrée donc pas cet état.
Une sélection explicite de blason revient en haut de l’écran ; aucun défilement
programmatique n’est déclenché à chaque sauvegarde. Une relecture explicite de
la même séance conserve l’UUID consulté s’il existe encore. Une nouvelle volée
créée est intentionnellement affichée selon la commande existante.
Le ViewModel conserve la volée sélectionnée, la correction et la proposition
pendant la rotation. Après mort du processus, la redécouverte SQLite reste la règle.

## Sauvegardes et lecture seule

L’accueil regroupe import et export sous « Sauvegardes », après les séances.
La section reste dépliée : états actifs, incidents, avertissements de fichier
incomplet et confirmations restent accessibles. Elle n’occupe plus la saisie.
Les coordinateurs, leur verrou commun et leurs deux launchers SAF racine restent
inchangés, indépendants des cartes recyclées par LazyColumn.

Trier, ouvrir, sélectionner une volée et consulter les statistiques ne soumettent
aucune écriture. Les fonctions de présentation ne connaissent ni repository ni SQL.
Les mises à jour acquittées restent la seule source de données affichée comme durable.

## Validation et limites

Tests JVM de présentation : instants/DST/UUID, statuts et entrées illisibles, volées
vides/partielles/pleines/validées, scores manuels et repositionnés, compteurs et
moyennes, archive JSON reconstruite. Comparaisons aux calculateurs existants.
Tests du contrôleur : navigation sans écriture, relecture et acquittement conservant
la sélection, correction/PendingSave protégés, consultation terminée non modifiable.
Les tests des PR 16 à 20 restent conservés.

La campagne ADB et les contrôles SQLite/export sont consignés dans les
[tests terrain](../tests-terrain-v1.md#protocole-historique-et-scores-pr-21).
Ils ne qualifient pas l’ergonomie au doigt, en extérieur ou avec grande police.
Pas de suppression, gestes de déplacement, zoom, configuration 6/libre dans
Android, matériel/presets, statistiques avancées ou nouvelle dépendance.

*16 octobre 2026*
