# Recette automatisée d'intégration — PR44

État de référence : 28 octobre 2026, PR43
`db1169869cc961853cd5a0ac8bc96233cef3a4c8`.
Le présent document concerne la branche de stabilisation, **pas main**.
Voir [ADR 044](adr/044-recette-integration-v3-et-consolidation.md) et le
[plan de consolidation](plan-consolidation-main.md).

## Périmètre et méthode

L'audit des ADR 033–043 et des implémentations conserve les contrats établis.
Les nouvelles preuves portent sur les liaisons entre couches, sans refaire les
tests unitaires de chaque règle. Aucun défaut de production n'a été reproduit.
Les descriptions périmées du backlog et de la procédure de première CI sont
corrigées ; aucune règle sportive n'en découle.

Les fichiers `App*Test.kt` de
[`core/database/src/jvmTest`](../core/database/src/jvmTest/kotlin/com/lmarchery/core/database)
compilent les **vrais** contrôleurs, coordinateurs et repository de l'application.
Ils utilisent Room, SQLite natif et `@TempDir`, avec fermeture et réouverture de
fichiers. Les documents sont des flux sur disque synthétiques, pas un fournisseur
SAF Android. Aucun mock de DAO ou faux résultat de commit ne remplace SQLite.

## Audit des parcours

| Parcours relu | Constat et preuve automatisée |
|---|---|
| Création/démarrage historique et configurable | Deux contrôleurs distincts ; recette complète via leurs API réelles, sans conversion de racine historique |
| Changement de configuration, INITIAL/INHERITED/EXPLICIT | Captures immuables et provenance validées par le domaine ; recette multi-volées et suites ConfiguredSession/RoomConfiguredEditor préexistantes |
| Classique/poulies, éditions et distances | Définition exacte de la volée utilisée ; 70 m ne change ni profil ni édition ; recette et golden V3 |
| FIXED 3/6/personnalisé et FREE | Capacités UI distinctes ; FREE dépasse trois impacts dans la recette ; pas de nouvelle obligation de remplissage |
| Placement/visée précise | Candidat non durable, confirmations répétées et ancienne époque de présentation testés avec vraie persistance ; rendu et précision tactile reportés |
| Jugement/correction exceptionnelle | Opérations typées contextualisées ; ancienne décision conservée lors de deux repositionnements ; aucune réattribution implicite |
| Repositionnement/historique | Motifs et invalidations présents après restauration/réouverture ; pas de modification des anciennes traces |
| Validation/reprise/clôture | Volées validées et séance terminée restent protégées ; réouverture du fichier conserve l'agrégat complet |
| Matériel | Création/révision via son contrôleur, références associées sans perte ; catalogue extérieur et références opaques conservés |
| Statistiques homogènes/mixtes | Scores validés/provisoires séparés, X/M finaux et groupes exacts ; totaux attendus indépendants dans la recette |
| Archives/coordinateurs V1/V2/V3 | Collecte réelle cohérente, flux disque, imports successifs et non-perte ; aucune modification des codecs |
| Navigation/annulation/callbacks | Verrou partagé, formulaire local abandonné sans DML et callbacks périmés vérifiés à travers les vrais acteurs |
| Conflits/concurrence/incertitude | Deux écrivains natifs, rollback, perte d'ACK après commit et relecture explicite ; aucune réexécution automatique |

Le manque de couverture confirmé concernait la traversée **contrôleur → repository
→ Room → document → restauration** et le retour des erreurs SQL dans les vrais
états de présentation. Les tests ajoutés comblent ce manque. La couverture de
chaque validation scalaire ou règle sportive existante est laissée à ses suites
spécialisées conservées ; il ne s'agit pas d'une preuve formelle d'absence de bug.

## Exécution reproductible

Prérequis : [JDK 21 complet et SDK](environnement-et-ci.md).

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
git diff --check
```

Pour isoler les **17 nouveaux tests** sans exclure les anciennes suites lors de
la validation complète :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_USER_HOME=/tmp/lmarchery-android-home
./gradlew :core:database:jvmTest --tests 'com.lmarchery.core.database.App*Test' --rerun-tasks --console=plain
```

Adapter les chemins aux installations réelles. Ce filtre est réservé au
diagnostic ; le Quality gate et la livraison exécutent toutes les suites.
Les XML sont sous `*/build/test-results`, le rapport SQLite sous
`core/database/build/reports/tests/jvmTest/index.html`.

## Matrice des preuves ajoutées

| Suite | Tests | Preuve reliant les couches |
|---|---:|---|
| [AppRoomRecipeTest](../core/database/src/jvmTest/kotlin/com/lmarchery/core/database/AppRoomRecipeTest.kt) | 1 | Migration réelle V1→2→3, catalogue et éditeur historique, séance mixte complète, export/import V1/V2/V3, réimport idempotent et réouverture des deux bases |
| [AppConfiguredFailureTest](../core/database/src/jvmTest/kotlin/com/lmarchery/core/database/AppConfiguredFailureTest.kt) | 5 | INSERT puis rollback, annulation après INSERT, commit acquis sans ACK puis reconnaissance sans DML, deux contrôleurs/écrivains concurrents, capture corrompue refusée sans réparation |
| [AppArchiveFailureTest](../core/database/src/jvmTest/kotlin/com/lmarchery/core/database/AppArchiveFailureTest.kt) | 8 | Restauration interrompue après INSERT, commit acquis sans ACK et re-prévisualisation mémoire, aperçu périmé, collision globale, fichier tronqué/close en échec, double callback et snapshot d'export stable face à un autre écrivain |
| [AppNavigationIntegrationTest](../core/database/src/jvmTest/kotlin/com/lmarchery/core/database/AppNavigationIntegrationTest.kt) | 3 | Candidat précis et formulaire annulés sans DML, callbacks d'une ancienne présentation, verrou partagé réel, matériel d'une séance historique terminée et origine historique restaurée sans conversion implicite |

La recette principale enregistre successivement :

1. Une base synthétique V1 peuplée, migrée par les DDL et migrations publiées.
2. Arc et jeu de flèches via le contrôleur matériel, puis séance rapide historique
   avec ces références, trois impacts, validation/clôture et exports V1/V2.
3. Une séance configurable : classique FIXED 3, poulies FIXED 6 à 70 m,
   choix EXPLICIT identique, héritage, profil historique autorisant X en FIXED 7,
   puis FREE. Le profil officiel n'est pas réinterprété en règlement à 70 m.
4. Un 9 jugé 10, repositionné en 8, corrigé exceptionnellement en 9 puis
   repositionné en 7 : deux invalidations motivées conservées, sans promotion
   automatique. X et M, volées vides/provisoires/validées puis clôture sont inclus.
5. Des assertions indépendantes sur les statistiques : total validé 44,
   provisoire 19, enregistré 63, neuf impacts, deux X et deux M, quatre groupes,
   capacité enregistrée 320. La capacité n'est pas un maximum planifié.
6. Une seconde base avec catalogue préexistant, puis imports V1, V2 et V3 :
   V1 ne vide pas le catalogue, les racines historiques existantes restent
   LEGACY_HOMOGENEOUS et seule la nouvelle racine est CONFIGURED.
7. Réimport V3 et réexport sous triggers interdisant toute DML, puis fermeture
   et réouverture des deux bases : égalité métier V3 complète et octets identiques.

Les assertions contrôlent l'en-tête SQLite réel, `PRAGMA user_version = 3`,
les douze tables exactes, `PRAGMA integrity_check = ok` et un
`PRAGMA foreign_key_check` vide. Les anciennes lignes hors opération doivent
rester présentes à l'identique ; les autres séances et captures ne changent pas.
L'égalité V3 couvre définitions/éditions de profils, origines, propriétaires,
UUID, dates nanoseconde, matériel, décisions et traces.

## Pannes et garanties observées

- Les hooks sont exécutés après de véritables `step()` natifs. Un trigger tardif
  échoue après l'INSERT d'un impact ou avant l'ajout final du catalogue : toutes
  les lignes sont comparées avant/après pour prouver le rollback.
- La perte d'ACK est injectée après `END TRANSACTION` acquis. L'écran conserve
  l'ancien état et l'intention exacte ; la relecture retrouve `proposed`, sans
  répéter la commande. Une re-prévisualisation d'import utilise les octets déjà
  validés, même si le fichier sélectionné a changé depuis.
- Deux écrivains ouvrent le même fichier et soumettent le même `expected`, avec
  le même `updatedAt` proposé. Un seul gagne ; le candidat perdant est conservé.
  Relecture d'un troisième état : aucun rebase ni retry automatique.
- L'annulation réelle d'une coroutine après INSERT annule sa transaction sans
  tuer l'acteur parent. Relecture explicite puis confirmation exacte, sans
  nouvel UUID ni date, permettent ensuite une opération légitime.
- Les erreurs de flux conservent un éventuel fichier incomplet, n'annoncent pas
  de succès, ne l'effacent pas et libèrent le verrou lorsque l'issue est connue.
  Une intention d'écriture non confirmée conserve en revanche ce verrou.
- Une capture corrompue ne devient ni éditable ni exportable ; aucune
  normalisation SQL ne la répare. Les appels concurrents SAF/catalogue/séance,
  doubles confirmations et callbacks anciens ne prennent pas le verrou d'autrui.

## Couverture antérieure conservée

Les suites `RoomConfiguredTransactionTest`, `RoomArchiveV3AtomicTest`,
`RoomArchiveV3ConflictTest`, `RoomArchiveV3ReadTest`, `RoomV3LegacySafetyTest`,
`RoomEquipmentMigrationTest` et `RoomConfiguredMigrationTest` restent exécutées.
Elles couvrent notamment les budgets, suppressions/révisions interdites,
orphanages, annulation de restauration après INSERT, migrations peuplées et
refus des parcours historiques sur CONFIGURED. Leurs tests ne sont pas recopiés.
Les golden et bits Float relèvent également des suites export historiques.

## Résultats de livraison

`./scripts/dev-check.sh --rerun-tasks --console=plain` : **PASS**, avec JDK
Temurin 21.0.12.1, Gradle 8.10.2, SDK 35 et Build Tools 34.0.0.

| Rapport XML | Exécutions | Échecs / erreurs / ignorés |
|---|---:|---:|
| core:model/test | 135 | 0 / 0 / 0 |
| core:scoring/test | 422 | 0 / 0 / 0 |
| core:export/test | 99 | 0 / 0 / 0 |
| core:database/jvmTest | 370 | 0 / 0 / 0 |
| app/testDebugUnitTest | 327 | 0 / 0 / 0 |
| app/testReleaseUnitTest | 327 | 0 / 0 / 0 |
| **Total** | **1 680** | **0 / 0 / 0** |

Les 327 cas Android JVM sont les mêmes en debug et release : ce sont deux
exécutions, pas 654 nouveaux tests. Le total sans répétition de variante est
1 353 cas ; **17 tests uniques sont ajoutés**, tous dans database/jvmTest
(353 → 370). Les autres suites et leurs cas restent inchangés.

`:app:assembleDebug` et `:core:database:assembleDebug` : PASS.
`:app:lintDebug` et `:core:database:lintDebug` : PASS, zéro erreur, cinq
avertissements `GradleDependency` préexistants (trois app, deux database).
L'avertissement Kotlin Multiplatform/AGP et les bibliothèques natives non
stripées restent visibles, sans masquage ni changement de dépendances.

Les schémas Room 1/2/3, les deux DDL historiques et les ressources golden JSON
ont été comparés octet par octet à la base. Aucun code de production, migration,
test historique ou codec n'est modifié. Liens locaux : **366 vérifiés, zéro
introuvable** ; `git diff --check` : PASS.

Le résultat CI propre au SHA publié est lié dans la [PR44](https://github.com/LounaMaili/LMarchery/pull/44).
Il doit être vérifié indépendamment des résultats locaux ; une ancienne CI verte
de #43 ne prouve pas la CI de #44. Aucun résultat post-fusion n'est attendu ici,
puisqu'aucune fusion n'est réalisée.

Après retarget vers main, le run 40713111171 a révélé deux observations trop
précoces du verrou dans le harnais. Leur reproduction contrôlée et le protocole
d'observation sur l'acteur UI sont décrits dans l'ADR 044. Les deux tests existants
sont renforcés avec un observateur immédiat ; leurs vérifications SQL et d'archives
restent inchangées. La correction ne crée aucun nouveau cas unique et ne change
pas les nombres ci-dessus. Les nouvelles répétitions et la CI sur la tête corrigée
sont rapportées séparément dans la PR ; aucune réussite n'est déduite d'un ancien run.

## NON TESTÉ : appareil et ergonomie

Aucune commande ADB, connexion, installation ou lecture de données de téléphone.
Aucun émulateur. NON TESTÉS ici : gestes au doigt et précision de la loupe,
rotation/recomposition Compose rendue, arrêt/recréation du processus Android,
fournisseurs SAF réels et erreurs matérielles du téléphone. Les tests appellent
les API de présentation ; ils ne constituent pas des essais tactiles.

La campagne future devra utiliser un applicationId, UID et stockage synthétiques
distincts, vérifier le SHA de l'APK final et les sauvegardes V3, puis tester
création mixte, annulations, héritage, jugements, repositionnements, reprise,
clôture et SAF avec documents synthétiques. Une base ayant atteint Room V3 ne
doit jamais être rouverte par un ancien APK ne supportant que Room V1 ou V2.
Ne pas remplacer l'application réelle pour réaliser cette campagne.
