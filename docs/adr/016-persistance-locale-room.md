# ADR 016 — Première persistance locale Room / SQLite

> Étape suivante : l’[ADR 017](017-mise-a-jour-seances-room.md) ajoute les révisions
> locales contrôlées, sans modifier le schéma ni les garanties ci-dessous.

## Choix technique vérifié

Conserver Kotlin **2.0.21**, AGP **8.7.3**, Gradle **8.10.2**, JDK **21**,
SDK **35** / minSdk **26**. Ajouter le module `:core:database`, les plugins Room
**2.7.2** et KSP **2.0.21-1.0.28** (KSP2 activé), Room runtime/compiler **2.7.2**,
SQLite Bundled **2.5.2** et les outils JUnit 5 déjà employés. Les coroutines et
dépendances techniques du processeur viennent de Room.

Références officielles consultées le 15 octobre 2026 :

- [Room 2.7 et suivantes](https://developer.android.com/jetpack/androidx/releases/room#2.7.2) : version stable, JVM/Android, langage Kotlin 2.0 et support recommandé de KSP2.
- [KSP 2.0.21-1.0.28](https://github.com/google/ksp/releases/tag/2.0.21-1.0.28), aligné sur Kotlin 2.0.21.
- [SQLite 2.5.2](https://developer.android.com/jetpack/androidx/releases/sqlite#2.5.2) et [construction Room par plateforme](https://developer.android.com/kotlin/multiplatform/room).
- [Room 3](https://developer.android.com/jetpack/androidx/releases/room3) et [POM officiel du processeur 3.0.3](https://dl.google.com/dl/android/maven2/androidx/room3/room3-compiler/3.0.3/room3-compiler-3.0.3.pom).

Room 3.0.3 est également stable, mais son processeur tire Kotlin 2.3.20 ; cette
pile plus récente n'est pas qualifiée ici avec le compilateur du projet. La
branche 2.8 existe aussi ; 2.7.2 est un choix conservateur de compatibilité,
pas une affirmation qu'il s'agit de la dernière version. La combinaison retenue
est vérifiée par génération KSP et compilation JVM **et** Android, tests natifs
et lint ; aucune mise à jour générale n'est nécessaire.

Kotlin Multiplatform 2.0.21 avertit que sa plage AGP historiquement testée se
termine à 8.5. Cet avertissement reste visible, sans propriété de suppression.
La combinaison 8.7.3 est vérifiée par ce dépôt ; ces résultats ne changent pas
la plage annoncée par JetBrains. Aucun échec n'est ignoré.

## Architecture

`core:database` utilise les cibles JVM et Android du plugin Kotlin Multiplatform
de même version. `src/sharedMain/kotlin` est inclus dans les **deux sources de
plateforme**, sans déplacer le domaine `java.time` dans `commonMain` ni prétendre
prendre en charge iOS/JS. KSP génère séparément les DAO et bases pour chaque cible.
Les fabriques sont `openSessionStore(path: Path)` en JVM et
`openSessionStore(context, name)` sur Android, avec contexte applicatif et chemin
privé `getDatabasePath`. Aucun dossier temporaire n'est choisi implicitement en JVM.

Les deux emploient `BundledSQLiteDriver`. Le pilote JVM ouvre SQLite natif ; les
tests n'emploient ni DAO simulé, ni Robolectric, ni émulateur. La même logique de
mapping et transaction est compilée pour Android. Les fabriques Android sont
compilées et lintées, sans prétendre avoir exécuté des tests instrumentés.
Le sens reste `database → scoring → model`. Les types métier de l'API sont exposés
par dépendances `api`, Room reste interne. Compose ne dépend pas encore du module.

## Schéma relationnel SQLite 1

`core/database/schemas/com.lmarchery.core.database.SessionDatabase/1.json` est
l'export **de schéma Room** versionné, pas une sérialisation de données utilisateur.

| Table | Identité et contenu |
|-------|---------------------|
| `entity_identities` | UUID primaire global et type, empêche les collisions entre agrégats/types |
| `target_profiles` | UUID, définition complète hors liste de zones, édition, nombre de zones |
| `target_zones` | Clé profil/position, rayons, score unique par profil, couleurs |
| `sessions` | UUID, profil, versions archive/scoring, triplet civil, configuration, statut, temps, nombre de volées |
| `ends` | UUID, séance, position/index uniques par séance, statut, temps, notes, nombre d'impacts |
| `impacts` | UUID, volée/profil, position/index uniques par volée, point, origine, note, temps, nombre de déplacements |
| `score_decisions` | Clé impact/révision : `-1` courant, `0..n-1` décisions précédant les déplacements |
| `impact_repositions` | Clé impact/séquence, contexte conservé, ancien/nouveau point, temps et motif, référence à la décision antérieure |

Clés étrangères `RESTRICT`, index et contraintes d'unicité protègent les liens.
Les décisions sont insérées avant les traces. Aucun ID local numérique n'est une
identité métier. Le matériel reste référencé par UUID opaque, sans fausse FK.
Les positions explicites conservent l'ordre original des listes, indépendamment
des index métier. Elles doivent être contiguës et correspondre aux nombres d'enfants
attendus : une suppression partielle ne doit pas paraître valide. Les décisions
attendues sont exactement les révisions `-1, 0, …, n-1`. Le registre est aussi relu.

Les `Float` sont conservés comme bits IEEE 754 dans des colonnes `Int`, pour les
coordonnées, rayons et assertions de distance : valeurs adjacentes et zéro signé
restent exacts. Ce codage numérique documenté n'est pas une sérialisation opaque.
Les booléens sont des entiers strictement décodés en `0` ou `1`.

## Versions et temps

- Room/SQLite : `@Database(version = 1)`, contrôlé par Room / `user_version`.
- Archive : `archiveSchemaVersion`, issu d'`ArchiveContractVersions.SCHEMA`.
- Scoring : `scoringContractVersion`, issu d'`ArchiveContractVersions.SCORING`.
- Profil : UUID et `regulatoryEdition` indépendants.

Ces valeurs initialement égales ne sont pas interchangeables. Chaque instant
possède deux colonnes entières `epochSecond: Long` et `nano: Int`, sans millisecondes.
Les composantes sont nullables afin de diagnostiquer un couple partiellement
corrompu : le mapper exige les deux pour un temps présent et
`nano in 0..999999999` **avant** `Instant.ofEpochSecond`, qui pourrait normaliser
un nano invalide. L'absence complète n'est admise que pour les dates optionnelles.
La date civile conserve `localDateTime`, IANA `zoneId` et offset choisi. Aucun
fuseau système, ajustement d'ambiguïté ni perte de précision n'est introduit.

## API et garanties transactionnelles

`RoomSessionStore.create(archive: DomainArchive)` accepte exactement **une** séance
et son profil complet validé ; une autre portée est refusée explicitement.
La capture puis `reconstructArchive` revérifient le contrat avant écriture.
Une transaction écrivain Room `immediateTransaction` vérifie l'existant et les UUID,
puis insère profil si absent, identités, séance, enfants, décisions et traces.
Toutes les insertions emploient `ABORT` par défaut : pas de `REPLACE`, `IGNORE`,
`UPSERT` ni choix selon `updatedAt`. Le profil déjà présent doit être identique.
Une séance déjà présente est relue/reconstruite avant comparaison :

- strictement identique : `AlreadyExists`, aucune ligne modifiée ;
- contenu différent ou UUID déjà possédé ailleurs : `Conflict` ;
- données stockées incohérentes : `NeedsIntervention`, sans réparation.

La transaction relit et reconstruit les données insérées puis vérifie leur égalité
complète avec la source. Toute erreur tardive provoque une exception et annule
toutes les insertions, profil et identités compris. Les créations concurrentes
sont sérialisées avant le contrôle d'existence.

`read(uuid)` utilise une transaction lecteur cohérente et distingue :

- `NotFound` : absence réelle ; une identité séance sans sa ligne est incohérente ;
- `Found(session, profile)` : uniquement un domaine validé ;
- `NeedsIntervention(StorageDiagnostic)` : chemin, détail, éventuel `ArchiveIssue` ;
- `TechnicalFailure(cause)` : accès, ouverture, fermeture ou schéma Room incompatible.

La lecture retrouve profil complet, enfants ordonnés, décisions et traces dans
les snapshots de l'ADR 015, puis appelle `reconstructArchive`. Elle ne construit
jamais directement une trace opaque. Les frontières, motifs, assertions et dates
sont revérifiés ; les déplacements sont rejoués. La corruption reste sur disque
avec son diagnostic, jamais sous forme d'une séance vide. Une annulation coroutine
est propagée. `close()` ferme la base et le magasin fermé refuse de la rouvrir.

## Validation et migrations

`./gradlew test` dépend de `:core:database:jvmTest` via la tâche `test` Android
existante. Le script complet ajoute aussi `:core:database:assembleDebug` et
`:core:database:lintDebug`. Le workflow existant exécute ce script, sans émulateur
ni secret nouveau. Les rapports doivent confirmer le nombre de tests exécutés.
Les tests retournent explicitement `Unit` pour leur découverte JUnit 5.

Les tests utilisent des fichiers **sur disque**, ferment et rouvrent Room,
comparent les domaines et interrogent le moteur pour vérifier version, en-tête,
contraintes et absence d'écriture lors d'un conflit. Une erreur SQL au second
déplacement prouve le rollback de toutes les tables. Ce n'est pas une simple
validation d'une base en mémoire.

Une évolution future exige une nouvelle version Room, une migration explicite
conservant les données, les schémas versionnés et des tests sur fichiers anciens.
Aucun `fallbackToDestructiveMigration` n'est autorisé. La version initiale n'a
pas de migration fictive ; une version inconnue échoue sans effacer les séances.

Restent hors périmètre : UI/historique, branchement Compose, édition/suppression,
liste de métadonnées, JSON d'import/export, synchronisation, matériel/presets et
intervention interactive. La limite de l'ADR 015 reste valable : un historique
entièrement falsifié avec des compteurs cohérents ne peut être authentifié sans
autre source connue.

*15 octobre 2026*
