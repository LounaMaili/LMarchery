# ADR 044 — Recette intégrée V3 et préparation de la consolidation

- Date : 2026-10-28
- Statut : proposé, PR de stabilisation en brouillon ; aucune fusion autorisée ici
- Base auditée : PR #43, `db1169869cc961853cd5a0ac8bc96233cef3a4c8`
- Suite de : [ADR 036](036-reconciliation-android-configurations.md),
  [040](040-ecritures-room-v3-configurables.md),
  [041](041-restauration-archives-v3-atomique.md),
  [042](042-saf-v3-consultation-configurable.md) et
  [043](043-editeur-android-seances-configurables.md)

## Contexte et audit

Les tests du domaine, du stockage et des contrôleurs couvrent déjà séparément
les configurations, révisions, restaurations et documents. Il manque une preuve
automatisée reliant les **sources réelles** des contrôleurs et coordinateurs
Android au repository et aux transactions SQLite sur disque. Les doubles des
tests Android JVM ne constituent pas cette preuve ; les tests de stockage ne
prouvent pas à eux seuls la conservation des intentions dans la présentation.

L'audit n'a reproduit aucun défaut de production nécessitant une correction.
Il a confirmé des descriptions périmées : backlog limitant tout Android à
trois flèches, index des ADR arrêté à 042, procédure CI racontant encore la
première pile comme état courant. Ces descriptions sont mises en contexte,
sans réécriture des décisions historiques.

## Décision : un pont limité à la compilation des tests JVM

Le source set `:core:database:jvmTest` compile une liste explicite de 21 fichiers
Android indépendants du framework : ports, repository, contrôleurs historique,
configurable et matériel, présentations pures et coordinateurs de documents.
Il utilise les fichiers de `app/src/main/java`, sans copie, réimplémentation ou
stub Android. Leur existence est vérifiée à la configuration Gradle. L'inclusion
des tests database préexistants est conservée explicitement.

Ce pont reste **exclusivement un outil de test**. Il ne crée aucune dépendance
de production de database vers app, aucun nouveau module, aucune bibliothèque
ou version. Il évite une extraction architecturale motivée seulement par la
recette. Une future extraction des contrôleurs vers un module pur devra remplacer
ce pont et conserver les mêmes tests ; elle n'est pas décidée par cet ADR.

Le [harnais](../../core/database/src/jvmTest/kotlin/com/lmarchery/core/database/AppIntegrationHarness.kt)
utilise Room et son moteur SQLite natif, un acteur de présentation dédié,
une Clock et des UUID synthétiques déterministes. Les ports de documents ouvrent
de vrais fichiers temporaires. Ils remplacent uniquement le fournisseur Android
SAF, pas la logique des coordinateurs, les codecs ou les opérations SQL.

## Preuves et limites

Les 17 nouveaux tests relient création rapide historique, catalogue, création
configurable, captures initiales/héritées/explicites, profils/éditions distincts,
distances et capacités FIXED/FREE, scores finaux et repositionnements, clôture,
migration 1→2→3, fermeture/réouverture et restauration JSON V1/V2/V3. La recette
principale compare intégralement les archives V3 et leurs octets réexportés,
les anciennes lignes conservées et l'intégrité des douze tables.

Les pannes sont injectées autour de véritables opérations SQLite : triggers
tardifs après INSERT, annulation de coroutine après INSERT, perte d'acquittement
après `END TRANSACTION` réellement exécuté. Les deux écrivains possèdent chacun
leur magasin et leur acteur ; le conflit est vérifié avec des propositions ayant
le même `updatedAt`. Des triggers interdisent INSERT/UPDATE/DELETE pour prouver
les reconnaissances idempotentes et les relectures sans écriture. Une panne de
close survient après fermeture du véritable flux de fichier.

Une perte d'acquittement simulée autour d'un commit réel prouve la gestion du
résultat incertain ; elle ne simule pas une coupure matérielle du disque. Une
réconciliation reconnaît l'agrégat exact déjà enregistré ou exige une nouvelle
confirmation de la même intention. Un troisième état reste un conflit ; aucune
réexécution, nouvelle identité, date ou configuration n'est fabriquée.

Les tests n'exécutent ni Compose, ni gestes humains, ni fournisseur SAF Android,
ni cycle de vie d'un processus Android. L'API de contrôleur est exercée sur JVM,
pas par des appuis simulés sur écran. Les compilations et lint vérifient le
raccordement Android statiquement. ADB, téléphone et émulateur ne sont pas
utilisés. La précision tactile réelle et la recette physique restent NON TESTÉES
par cette PR.

## Frontières conservées

Aucun code de production, test historique, profil, règle sportive, schéma Room,
migration, codec ou golden JSON n'est changé. Les archives, éditions de profil,
versions Room et contrat de scoring restent distincts. Les anciens lecteurs
V1/V2 refusent toujours une base contenant une racine CONFIGURED. Les références
matériel opaques ne sont ni résolues ni effacées implicitement.

Les règles sportives ouvertes restent celles de 033–035/043 : pas de nouvelle
obligation de remplir une volée FIXED, pas de comparabilité réglementaire d'un
total hétérogène, pas de correction rétroactive ou réouverture après clôture.

## Conséquences et consolidation future

La recette devient une partie obligatoire de `./scripts/dev-check.sh`, via le
`jvmTest` déjà requis par `test`. Ses commandes, assertions et résultats sont
décrits dans la [recette PR44](../recette-integration-v3-pr44.md).

Le [plan vers main](../plan-consolidation-main.md) distingue les intégrations
déjà présentes dans le graphe, les propositions documentaires indépendantes,
les anciens checks et les validations à refaire sur chaque nouvelle base.
Il propose des paliers conservant l'ascendance ; il n'autorise aucune fusion,
fermeture de PR, actualisation de branche parente ou modification de protection
pendant cette mission.
