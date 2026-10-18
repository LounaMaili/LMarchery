# ADR 030 — Catalogue matériel Android local

Statut : implémenté, essais ergonomiques physiques non effectués.

## Préalables et portée

Les [ADR 025](025-fondation-catalogue-materiel.md), [027](027-archives-room-v2-atomiques.md)
et [029](029-saf-android-archives-v2.md) fournissent des fiches immuables, leur
stockage et une sauvegarde complète V2. Le catalogue peut donc être exposé sans
créer de données absentes des sauvegardes complètes. L’export V1 reste clairement
marqué comme excluant les fiches.

## Décision

Une section « Matériel » à l’accueil ouvre un parcours localisé : listes d’arcs
et jeux de flèches, fiche en consultation, formulaire d’ajout ou de modification.
Les noms identiques restent des fiches distinctes ; l’UUID est affiché et fait
autorité. Aucune suppression, fusion de noms, association à une séance ou
modification de score n’est ajoutée dans cette étape.

`RoomEquipmentRepository` adapte le catalogue du **magasin déjà ouvert par le
processus**, sans seconde base. Son enveloppe IO est testable. Le repository ne
fabrique ni date, ni UUID, ni révision ; les méthodes de `RoomEquipmentCatalog`
conservent transactions, registre d’identités et comparaison complète attendue.

`EquipmentController` appartient au ViewModel. Ses commandes passent sur le
thread UI ; une opération en cours refuse les autres commandes sans les mettre
en file. L’entrée au catalogue est possible depuis l’accueil seulement, en
acquérant le verrou commun aux séances et à SAF. Ce verrou est conservé jusqu’au
retour explicite aux séances. L’édition du matériel et une sauvegarde/restauration
ne peuvent ainsi pas être déclenchées simultanément par l’interface.

## Saisie et révisions

- Nom obligatoire non blanc, conservé sans trim, comme les modèles métier.
- Chaque champ facultatif possède un choix « Renseigner ». Désactivé : `null` ;
  activé et laissé vide : chaîne vide. Aucun défaut inventé pour les unités,
  spine, longueur, pointe, marque ou type libre.
- La puissance, explicitement en livres, exige un Float fini strictement positif.
  Le formulaire indique l’usage du point décimal ; aucune conversion d’unité.
  La représentation aller-retour Float préserve les bits d’une valeur inchangée.
- L’enregistrement exige un appui explicite. La création utilise un UUID v4 et
  un Instant de la Clock injectée, UTC par défaut. La révision conserve UUID et
  création et date explicitement la modification. Une horloge en recul donne une
  erreur, sans plafonnement ni incrément artificiel. Un formulaire inchangé
  réutilise exactement sa valeur initiale, sans nouvelle date.
- Le dernier modèle confirmé reste affiché comme confirmé jusqu’à acquittement.
  Le couple attendu/proposé est figé pendant l’écriture. Les doubles appuis ne
  créent pas une seconde commande.
- Échec technique : proposition, UUID, dates et état attendu conservés. Une relance
  explicite réutilise le même couple. Conflit/intervention/absence : aucune
  recréation ni relance sur une base récente. « Consulter la version enregistrée »
  conserve la proposition séparément. L’abandon est confirmé par dialogue ; pour
  une fiche existante, il attend une lecture réussie avant d’effacer la saisie.

## Cycle de vie et limites

Le ViewModel conserve saisie, sélection et proposition pendant la rotation. Ces
valeurs temporaires ne sont pas un journal durable : après arrêt du processus,
le catalogue est redécouvert depuis SQLite. Une opération interrompue ne donne
pas de succès ; une écriture dont l’acquittement a été perdu peut déjà être
présente, d’où la relecture et l’absence de retry automatique.

La découverte n’est pas une sauvegarde atomique : chaque fiche est relue et une
entrée illisible reste visible avec diagnostic. L’export global continue d’utiliser
uniquement `readArchiveV2()` pour son instantané cohérent.

Room 2, JSON V1/V2, scoring, traces et références opaques des séances restent
inchangés. Les associations seront un parcours distinct. Aucun accès au Pixel,
installation, migration réelle sur appareil ou essai ergonomique n’est effectué.

## Vérifications

Tests du formulaire (null/vide, Unicode/unités, puissances limites, copies,
chronologie), du contrôleur (création, édition, doublons, acquittement, conflit,
relecture, abandon, double commande, annulation, rotation logique/perte de
processus et verrou partagé), et de l’enveloppe IO du repository.

Les tests SQLite natifs restent présents et ajoutent deux parcours synthétiques :
éditer les deux types, fermer/réouvrir, exporter V2/restaurer dans une deuxième
base avec comparaison de toutes les tables ; conserver deux noms identiques avec
UUID distincts. Les lignes des séances, décisions/traces et les octets V1 sont
comparés avant/après édition. Les tests de migration, concurrence et rollback de
l’ADR 025 restent exécutés.
