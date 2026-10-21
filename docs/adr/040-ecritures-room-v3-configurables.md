# ADR 040 — Écritures transactionnelles des séances configurables dans Room V3

Statut : création et révisions métier implémentées, sans branchement Android,
restauration/import global V3 ou SAF V3. Base exacte #39 :
`d62b4fada7bf56baeeb3f3cc2dbab0ce34fba34a`.
PR en brouillon vers `codex/room-v3-configured-read`, sans fusion/auto-merge.

## Contrats réutilisés et versions conservées

Les [ADR 027](027-archives-room-v2-atomiques.md),
[033](033-fondation-configurations-par-volee.md),
[034](034-scoring-par-volee-statistiques-mixtes.md),
[035](035-agregat-seances-configurations-mixtes.md),
[036](036-reconciliation-android-configurations.md),
[037](037-contrat-archive-seances-configurables-v3.md),
[038](038-codec-json-v3-strict.md) et
[039](039-room-v3-migration-lecture.md) restent applicables.

Room reste en **version 3, douze tables**. Les schémas 1/2/3, les migrations
1→2→3, les codecs et golden JSON V1/V2/V3 et le scoring 1 sont inchangés.
Aucune règle sportive de remplissage, clôture ou correction rétroactive n'est
ajoutée. Les décisions et déplacements réutilisent exactement le domaine existant.

| Représentation stockée | Autorisation |
|---|---|
| `LEGACY_HOMOGENEOUS` | API historiques `Session`, captures dérivées synchronisées selon leur contrat existant |
| `CONFIGURED` | Nouvelles API `ConfiguredSession`, captures immuables par volée |

Une révision ne convertit jamais implicitement une représentation en l'autre.
Les anciennes colonnes non nulles de `sessions` restent des **alias de l'initiale**
pour `CONFIGURED`, représentation de ligne 3 et scoring 1. Le profil de chaque
impact vient exclusivement de la configuration effective de sa volée.

## API publique et intention immuable

[`RoomSessionStore`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/RoomSessionStore.kt)
expose deux opérations supplémentaires :

```kotlin
suspend fun createConfiguredSession(session: ConfiguredSession): ConfiguredWriteResult
suspend fun updateConfiguredSession(expected: ConfiguredSession, proposed: ConfiguredSession): ConfiguredWriteResult
```

Elles n'acceptent ni DTO non fiable, ni arguments scalaires de score, ni séance
homogène intermédiaire. Une création normale exige `CREATION` et refuse toute
capture `LEGACY_DERIVED`. Une archive historique convertie n'est pas une création
ordinaire : sa future restauration devra disposer d'un autre contrat explicite.
Les origines existantes légitimes sont conservées lors des révisions, sans
fabrication d'événement, d'UUID ou de date de sélection. Cette origine est une
valeur métier validée, pas une preuve cryptographique de l'action humaine.

[`ConfiguredWriteRequest`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/ConfiguredWrite.kt)
conserve l'intention exacte : `Create(proposed)` ou `Update(expected, proposed)`.
Tous les résultats la portent, pour permettre une réconciliation explicite.
Les agrégats et descendants sont immuables. L'appelant conserve aussi ses entrées
avant de lancer l'appel ; la perte d'un résultat ou l'annulation de son Job peut
empêcher de recevoir une exception enrichie.

| Résultat typé | Signification |
|---|---|
| `Created` / `Updated` | État intégral contrôlé puis transaction acquittée par Room |
| `AlreadyApplied` | Même identité, représentation `CONFIGURED` et **tout** le contenu proposé déjà présent ; aucun DML |
| `Conflict` | Identité occupée, révision différente, absence, représentation historique, transition ou provenance interdite ; aucun écrasement |
| `NeedsIntervention` | Diagnostic `STORAGE`/`DOMAIN`, chemin précis et erreur V3 originale lorsqu'elle existe ; issue de commit explicitée |
| `TechnicalFailure` | Erreur technique avant tentative d'écriture métier de cet appel, distincte des incohérences |
| `CommitUnconfirmed` | Une écriture a été tentée et l'acquittement final n'est pas démontré ; intention exacte conservée |

`ConfiguredWriteConflict` distingue `IDENTITY_OCCUPIED`, `REVISION`, `NOT_FOUND`,
`NOT_CONFIGURED`, `INVALID_TRANSITION` et `INVALID_PROVENANCE`. Un contenu différent
avec le même UUID ne devient jamais une mise à jour implicite. Une proposition
illégale reste refusée même si elle provient d'une reconstruction indépendante.

## Transaction unique, identité et mapping

[`RoomConfiguredWriter`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/RoomConfiguredWriter.kt)
réutilise le verrou écrivain Room, partagé avec les parcours historiques, le
catalogue et les imports existants. Le précontrôle avant verrou commence par :

1. Revalider l'agrégat et, pour une révision, appeler
   `validateConfiguredSessionUpdate(expected, proposed)`. Vérifier les budgets V3
   et le snapshot exact, sans `reconstructArchiveV3(known = ...)` comme autorisation.

Une seule `immediateTransaction` couvre ensuite :

2. Collecter **toute la base** sous le verrou avec `StrictStorageQueries` et
   `collectArchiveV3`, sur cette même connexion écrivain. Les incohérences existantes
   bloquent avant DML ; aucun appel public lecteur/catalogue imbriqué.
3. Vérifier le marqueur `CONFIGURED`. Comparer tout l'état stocké à `proposed`
   pour une réconciliation sans écriture, sinon exiger l'égalité exacte avec
   `expected`. Aucune décision fondée sur le seul `updatedAt`.
4. Vérifier les six catégories d'identités globales et les propriétaires. Un
   nouveau descendant ne peut adopter un UUID externe. Un profil existant doit
   conserver définition complète **et** édition ; aucun profil équivalent par
   diamètre/nom n'est substitué. Les références matérielles canoniques non résolues
   ne revendiquent pas d'identité et ne créent aucune fiche.
5. Construire et valider l'archive complète attendue : état initial plus les seuls
   ajouts/changements autorisés. Les limites sont celles de 037 : 20 000 éléments
   par collection, 200 000 cumulés, 16 384 unités UTF-16 par texte, sans troncature.
6. Insérer les identités absentes, profils/zones nouveaux, séance, initiale, puis
   toutes les lignes de volées avant leurs captures : la FK d'héritage retrouve
   ainsi sa précédente, même si l'ordre de transfert est différent de l'index.
   Insérer ensuite impacts, décisions et repositionnements. Les révisions utilisent
   uniquement les deltas autorisés : nouveaux descendants/captures et UPDATE des
   seules lignes modifiées, jamais DELETE, REPLACE, IGNORE ou UPSERT.
7. Reconstruire l'archive V3 complète sur la même transaction et exiger son égalité
   avec l'attendu. Vérifier aussi l'absence de violation de clés étrangères.
8. Vérifier les valeurs SQL hors delta et les anciennes captures, puis contrôler
   le Job d'origine avant de laisser Room committer.

Le mapping [`ConfiguredWriteMapping`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/ConfiguredWriteMapping.kt)
utilise `ConfiguredSessionSnapshot`, jamais `Session`/`SessionSnapshot` pour une
séance mixte. Seule la décomposition relationnelle des enfants est partagée avec
`StorageMapping`. Le delta relationnel de `StorageUpdate` est réutilisé sans sa
synchronisation de captures homogènes. Le parcours historique conserve cette
synchronisation dans sa propre transaction et tous ses anciens tests.

Les configurations déjà enregistrées, leur propriétaire et leur provenance ne
reçoivent aucun UPDATE, même vides. Une modification de métadonnées seule ne
réécrit ni captures, ni impacts, ni décisions/traces. Suppression/transfert/
renumérotation d'impacts, disparition de provenance manuelle ou de préfixes de
repositionnement, changement d'une volée validée et révision d'une séance terminée
sont refusés par les validateurs métier, sans assouplissement.

## Vérification hors périmètre et erreurs après DML

L'égalité de l'archive protège toutes les valeurs métier, profils non référencés,
matériel et références opaques compris. En complément,
[`ConfiguredWriteAudit`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/ConfiguredWriteAudit.kt)
calcule des empreintes SHA-256 des lignes SQL hors delta, avant/après dans la même
transaction. Chaque cellule encode son véritable type, sa longueur et ses octets
exacts, NUL compris ; aucun `quote()` ou texte coercitif. Cela détecte même une
altération de lexème civil équivalente après normalisation métier.

Les profils existants, identités anciennes, toutes les fiches et séances externes,
ainsi que les anciennes captures de la séance révisée, sont dans ce contrôle.
Les descendants dans le delta restent protégés par le validateur de transitions
et l'égalité complète du résultat. Les données sont préalablement bornées et
typées par le lecteur strict ; ces empreintes ne constituent pas une signature
ou une preuve d'authenticité d'un historique absent de toutes les sources.

Après la première tentative DML, **toute erreur lève hors de la transaction**.
Les diagnostics sont convertis seulement à l'extérieur : une erreur tardive ne
peut pas retourner normalement du corps transactionnel et committer un sous-ensemble.
Le contrôle intégral est volontairement conservateur : une corruption sans rapport
avec la séance bloque l'écriture et exige une intervention, sans réparation.
Il implique un coût de lecture proportionnel à la base bornée ; une optimisation
future devra conserver ces preuves d'intégrité et d'isolation.

## Réessais, annulation et acquittement incertain

Les tests prouvent des rollbacks effectifs, mais une exception technique n'est
pas une preuve universelle de rollback : un COMMIT déjà acquis ne peut plus être
annulé par une perte d'acquittement. Toute erreur technique après tentative DML
est donc conservativement `CommitUnconfirmed`, même lorsque le test constate que
SQLite a effectivement annulé. `NeedsIntervention` porte également
`NOT_ATTEMPTED` ou `UNCONFIRMED`, sans transformer une incohérence en succès.

L'annulation coroutine se propage via `ConfiguredWriteCancelled`, avec intention
et issue connue de la tentative. Le Job de l'appelant peut masquer cette exception ;
conserver `expected/proposed` reste obligatoire. Une annulation après COMMIT ne
prouve pas un rollback. Aucun retry automatique, abandon ou rebase implicite.

Protocole de réconciliation explicite :

- Relire pour examiner l'état réel ; conserver l'intention d'origine.
- Un nouvel appel explicite avec **les mêmes** `expected/proposed` relit encore
  sous verrou. `proposed` exactement présent donne `AlreadyApplied`, sans DML.
- Si `expected` est encore exactement présent, une nouvelle tentative explicitement
  demandée peut appliquer la même proposition ; elle n'est jamais lancée par le store.
- Un troisième contenu reste `REVISION` : conserver les deux valeurs, résoudre
  explicitement le conflit hors du store et préparer une nouvelle transition.
- Une identité différente ou une base incohérente ne devient jamais une autorisation
  de rejouer. `TechnicalFailure` avant DML ne dit rien de l'issue d'un appel antérieur.

## Preuves sur SQLite natif disque

Les trois nouvelles suites et leur fixture pure utilisent le vrai pilote natif,
les vrais DAO Room, de vraies transactions et des fichiers synthétiques fermés/
réouverts. Les hooks observent **après** `SQLiteStatement.step` natif : ce ne sont
ni des DAO doubles ni des simulations d'interface.

- Création vide/riche, démarrage, initiale/héritages successifs, choix explicite
  égal et différent ; profils classique/poulies, distances indépendantes,
  FIXED 3/6/personnalisé et FREE, volées vides/provisoires/validées et clôture.
- Conservation exacte des décisions, X/M, plusieurs invalidations motivées,
  nanosecondes, Unicode, références opaques ; réouverture et aller-retour JSON V3.
- Catalogue/profils non référencés, anciennes lignes d'une véritable base V1→V3,
  coexistence des créations/lectures/révisions historiques et du matériel.
- Collisions entre catégories, définitions/éditions contradictoires, mauvais
  propriétaire, héritage incohérent, configuration initiale/effective/origine
  remplacée, identité/création/index/historique altérés, volées figées et dates.
- Idempotence sous triggers interdisant tous les DML et observation SQL ; refus
  V1/V2 global même pour une séance configurable vide ou apparemment homogène.
- Deux écrivains simultanés avec le même expected : un succès, un conflit ; deux
  créations identiques : un succès, une reconnaissance exacte. Révision périmée,
  puis résolution explicite légitime, sans priorité au dernier horodatage.
- Pannes après vrai INSERT et vrai UPDATE de décision, FK tardive, altérations
  par trigger de captures/catalogue/lignes externes et identité orpheline :
  égalité de toutes les lignes avant/après rollback, contrôle des FK.
- Annulation avant écriture, après INSERT natif ou UPDATE natif ; acquittement
  perdu après COMMIT acquis pour création/révision et annulation post-COMMIT :
  réouverture prouvant le commit et réconciliation exacte sans duplication/DML.
- Budgets texte et cardinalité, source reconstruite avec des plafonds externes
  plus larges refusée par le store, données incohérentes et magasin fermé.

Les tests de perte d'acquittement arment le hook après le DML ciblé : ils ne
confondent pas le COMMIT métier avec les transactions d'invalidation internes
Room. Les triggers et hooks ne touchent que des bases synthétiques de test.
Aucun appareil, ADB, installation APK ou donnée utilisateur n'est employé.

## Frontières et étapes suivantes

Les anciennes sauvegardes/imports V1/V2 refusent toute base contenant `CONFIGURED`,
sans omission ni projection homogène. Le lecteur V3 restitue tout, y compris les
séances historiques. Les API historiques restent utilisables sur leurs propres
séances ; elles ne peuvent adopter une identité configurable.

Ces nouvelles API ne sont **pas reliées aux contrôleurs Android** : pas de parcours
producteur de séances mixtes avant la restauration et SAF V3. Le codec autonome
V3 ne constitue pas à lui seul un mécanisme de restauration opérationnel.

La future PR d'import V3 devra définir aperçu exact, comparaison globale des
identités/provenances, plan recalculé sous verrou, restauration légitime des
origines historiques, budgets, transaction unique et gestion des acquittements
incertains. Elle ne devra pas appeler ces créations l'une après l'autre avec des
commits séparés. Aucune correction rétroactive, réouverture ou nouvelle politique
sportive n'est autorisée par cet écrivain.

Validation de livraison : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
XML, SQLite disque et FK, builds/lint Android, empreintes des trois schémas et
migrations/golden/codecs/scoring inchangés, liens locaux et `git diff --check`,
puis Quality gate GitHub sur le SHA exact publié.

*21 octobre 2026*

### Résultat local du 21 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : PASS, trois étapes
`BUILD SUCCESSFUL`, JDK 21 complet, SDK 35 et Build Tools 34.0.0.
Rapports XML inspectés : modèle 135, scoring/reconstruction 407, codecs 92,
SQLite natif disque **292**, Android JVM 228 debug + 228 release.
Soit **1 382 exécutions**, zéro échec, erreur ou test ignoré ; les variantes
Android partagent des suites, ce ne sont pas autant de tests uniques.

Les **59 nouveaux tests** se répartissent en 19 créations/coexistence,
19 révisions et 21 transactions/interruptions. Les 233 tests SQLite précédents,
les migrations disque, les coexistences #36 et les golden sont toujours exécutés.
Les vérifications `foreign_key_check`/`integrity_check` et les comparaisons de lignes
sont faites sur les fichiers de test ; les hooks attestent le DML/COMMIT natif réel.

Assemblages debug app/database et lint : PASS, zéro erreur lint et les cinq
avertissements GradleDependency préexistants (3 app, 2 database), sans masquage des
messages KMP/AGP. Les 173 fichiers protégés vérifiés sont identiques à la base #39 :
schémas 1/2/3, migrations, codecs/golden, domaine/scoring et Android. Empreintes
SHA-256 Room inchangées :

- 1 : `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`.
- 2 : `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`.
- 3 : `3333739e70b6ffc1781df44624a152ef6940c25f2d4299a23b34b8f41e41a2b7`.

290 liens documentaires locaux et `git diff --check` : PASS. La CI GitHub est
vérifiée séparément sur le commit exact publié ; aucun test physique revendiqué.
