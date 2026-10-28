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

## Correction de l'observation des états après synchronisation avec main

La tête `10568dd84d0adbf58e52dbe1985dc3257e791aea`, synchronisée avec main
`724a7e28fdd6bf6e6d9a4eeb5c3262b645728754`, a le même arbre que la livraison
initiale #44. Son Quality gate 40713111171 échoue pourtant dans deux tests
`AppArchiveFailureTest`. Une nouvelle exécution locale ordinaire réussit ; ce
succès seul ne suffit pas à expliquer ou écarter les échecs.

Une observation immédiate, avec `Dispatchers.Unconfined`, reproduit les deux
assertions de verrou **trois fois sur trois** avec l'ancien harnais :

- Restauration : après la nouvelle confirmation explicite réussie, le test
  attend `SucceededV3`, puis considère `busy == false` comme une fin de tour UI.
  `ImportCoordinator.finish` publie cependant le résultat avant de libérer le
  verrou ; l'actualisation de liste peut aussi démarrer ensuite. L'observateur
  JVM externe peut reprendre dans cet intervalle et lire le verrou encore actif.
- Export : le second export explicite attend `Succeeded`, puis vérifie le
  verrou dans `AppIntegrationHarness.export`. Le coordinateur publie ce résultat
  avant son nettoyage et la libération, dans le même tour UI. Un observateur
  immédiat peut donc déclencher la même assertion avant la fin de ce tour.

Les diagnostics XML locaux localisent `Expected value to be false` dans le
premier test et dans le helper d'export. Les lignes 22/115 du log CI court
désignaient les fonctions de test coroutine, pas les assertions précises.
La différence pertinente est l'ordonnancement de l'observateur par rapport à
la fin du tour UI ; aucune différence de contenu Git ou de format SQLite ne
l'explique. Aucune incohérence d'instantané ou restauration partielle n'a été
reproduite. Les points d'entrée de production sont explicitement réservés au
thread UI ; ce harnais doit respecter leur sérialisation avant de juger leur
post-état. Aucun code de production n'est modifié par cette correction.

`awaitUiState` collecte désormais les états sur le **même acteur UI** que les
contrôleurs, sous le délai borné de 15 secondes déjà existant. L'entrée ou la
reprise de cette observation attend le tour du producteur ; elle ne confond plus
publication et achèvement de ce tour. Les helpers et attentes directes concernés
utilisent ce protocole commun, y compris les états d'erreur. Les assertions de
verrou restent présentes, après cette synchronisation, ainsi que toutes les
égalités d'archives, de lignes SQL, de profils et de traces.

Les deux scénarios conservent l'observateur immédiat comme non-régression : le
premier vérifie toujours le rollback après INSERT et la nouvelle confirmation,
le second les deux instantanés complets avant/après l'autre écrivain. Aucun délai
n'est augmenté, aucun sleep, retry, test ignoré, suppression d'assertion ou
modification des triggers SQL n'est introduit. L'issue de COMMIT incertaine garde
son candidat et son verrou jusqu'à une réconciliation explicite.

Les résultats ciblés, répétés, complets et le nouveau Quality gate sont consignés
dans la PR44 sur la tête corrigée. Le succès initial et le dry-run ne remplacent
pas cette nouvelle validation contre main. La PR reste en brouillon, sans fusion.

Validation locale après correction : les deux scénarios isolés passent, la
classe complète passe (8 tests), puis dix répétitions de cette classe passent
(80 exécutions supplémentaires), avec l'observateur immédiat conservé.
La suite SQLite entière passe (370 tests), puis
`./scripts/dev-check.sh --rerun-tasks --console=plain` réussit intégralement :
1 680 exécutions, zéro échec/erreur/ignoré, assemblages app/database et lint PASS.
Les cinq avertissements lint préexistants restent visibles, les 366 liens locaux
sont valides, schémas/migrations/golden et code de production sont inchangés.
