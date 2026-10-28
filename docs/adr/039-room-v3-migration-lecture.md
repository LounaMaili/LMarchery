# ADR 039 — Migration Room 2→3 et lecture des séances configurables

> Suite : l’[ADR 040](040-ecritures-room-v3-configurables.md) ajoute les écritures
> métier locales, sans import/restauration V3 ni connexion Android. Le statut
> ci-dessous décrit le périmètre livré par la PR #39.

Statut : migration additive et lecture complète implémentées. **Aucune écriture
de séance mixte, restauration/import V3, SAF V3 ou UI configurable.**
Base exacte #38 : `e3b381027a79a6a2d56827b6ebe7f5e262d2e0ab`.
PR en brouillon vers `codex/configured-archive-v3-json`, sans fusion/auto-merge.

## Audit et contrats conservés

Les [ADR 027](027-archives-room-v2-atomiques.md),
[033](033-fondation-configurations-par-volee.md),
[034](034-scoring-par-volee-statistiques-mixtes.md),
[035](035-agregat-seances-configurations-mixtes.md),
[036](036-reconciliation-android-configurations.md),
[037](037-contrat-archive-seances-configurables-v3.md) et
[038](038-codec-json-v3-strict.md) s’appliquent.

Room V2 contient dix tables : registre global, profils, zones, séances, volées,
impacts, décisions, repositionnements, arcs et jeux de flèches. Toutes utilisent
les UUID et les contraintes/positions des ADR 016/025/027. Un impact connaît son
profil exact ; l’édition est conservée dans `target_profiles`. Les champs de séance
profil/distance/modes décrivent uniquement le contrat historique homogène.

Le nouveau contrat métier/configurations et les codecs V1/V2/V3 restent inchangés.
Aucun profil, règle de cordon, score ou décision n’est remplacé par un défaut actuel.
Les références de matériel restent opaques, sans FK nouvelle vers le catalogue.

| Version ou identité | Rôle |
|---|---|
| Room `@Database.version = 3` | Structure SQLite à douze tables ; chaîne 1→2→3 |
| JSON V1/V2/V3 | Contrats d’enveloppe distincts, octets et golden inchangés |
| `sessions.archiveSchemaVersion` | Représentation de la ligne : 1 pour le contrat homogène, 3 pour le futur configurable ; jamais affecté à partir de la version Room |
| `scoringContractVersion = 1` | Sémantique de scoring inchangée |
| Profil | UUID immuable + définition complète + édition stockée, sans déduction par diamètre/distance |

## Deux tables additives, sans identité de configuration

[`ConfiguredStorageRows.kt`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/ConfiguredStorageRows.kt)
définit les captures. Les dix anciennes tables/colonnes/index/FK ne sont pas
modifiés. Les schémas 1/2 sont figés ; le schéma 3 est exporté par Room sur les
cibles JVM et Android.

| Table | Champs |
|---|---|
| `session_configurations` | `sessionUuid` PK/FK séance, `representation`, `origin`, puis configuration initiale complète |
| `end_configurations` | `endUuid` PK/FK volée, `sessionUuid` FK initiale, `origin`, `previousEndUuid` FK volée nullable, puis configuration effective complète |
| Configuration commune | `profileUuid` FK profil, `regulatoryEdition` nullable, `distanceM`, `arrowCountMode`, `arrowCountPerEnd` nullable, `isIndoor`, `scoringMode` |

Les FK sont `RESTRICT`. Les colonnes de recherche des FK sont indexées. La clé
de chaque capture est l’UUID de son propriétaire existant ; elle ne revendique
aucune nouvelle identité dans `entity_identities`. Aucun UUID, événement, instant
de sélection ou champ d’historique n’est fabriqué.

L’appartenance `end_configurations.sessionUuid == ends.sessionUuid` est vérifiée
à la lecture ; chaque FK garantit aussi l’existence de son parent. Le prédécesseur
doit être la volée d’index immédiatement précédent du **même** agrégat et sa
configuration doit être égale : le reconstructeur 037 le contrôle. Une FK seule
ne peut pas prouver ces propriétés. Les enums, booléens, chronologies, comptes,
positions, UUID et éditions restent des validations strictes, sans coercition.

### Marqueurs et colonnes historiques

`representation` est obligatoire ; absence, valeur inconnue ou contradiction
est bloquante. Il ne s’agit ni d’une version JSON détectée par diamètre ni d’une
déduction depuis la présence d’impacts.

- **`LEGACY_HOMOGENEOUS`** : ligne selon l’ancien contrat `Session`, représentation
  de ligne 1 et scoring 1. L’initiale et chaque volée sont `LEGACY_DERIVED`, même
  vides. Les champs de configuration sont une projection redondante, vérifiée,
  des valeurs réellement présentes dans `sessions` et du profil stocké.
- **`CONFIGURED`** : futur contrat explicite, représentation de ligne 3 et scoring 1.
  L’initiale et chaque capture portent leur propre origine selon 037. Les anciennes
  colonnes non nulles de `sessions` sont seulement des **alias de l’initiale**,
  vérifiés à l’identique, jamais une source de configuration effective des volées.
  Une capture absente ne déclenche aucun repli sur ces alias.

Le contrat homogène existant accepte certaines révisions globales de distance,
mode/nombre et indoor, ainsi que ses métadonnées. **Son comportement est conservé** :
une révision `expected/proposed` autorisée de `LEGACY_HOMOGENEOUS` synchronise ses
seules projections `LEGACY_DERIVED` dans la même transaction. Elle ne fabrique pas
une ancienne sélection par volée et ne prétend pas connaître une configuration
antérieure que `Session` n’historisait pas. Un simple changement de titre ou matériel
ne lance aucun UPDATE des captures ; une révision identique n’écrit rien.

Cette compatibilité ne permet **aucune** révision rétroactive de capture
`CONFIGURED`, même homogène ou vide : les chemins historiques la refusent avant
écriture. Une future opération exceptionnelle de correction de configuration,
avec autorisation/motif/traces, reste une tâche distincte. Le marqueur historique
désigne une représentation, pas l’âge de la séance : les nouvelles séances créées
par les API historiques restent homogènes/dérivées jusqu’à une future intégration.

## Migration atomique et non destructive

[`MIGRATION_2_3`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/ConfiguredStorageMigration.kt)
est enregistrée avec `MIGRATION_1_2` dans les deux fabriques Android/JVM.
L’ancienne migration 1→2 reste inchangée. Room enveloppe le callback et la
validation du schéma cible dans sa transaction ; aucun commit ou magasin imbriqué.

1. Lire et valider la source V2 complète **avant DDL** : budgets, types SQL,
   registre/propriétaires physiques, orphelins, profils, séances et tout le catalogue.
   Réutiliser le mapping/rejeu historique, reconstruire V1 pour chaque représentation
   de séance 1, puis V2 pour l’ensemble. Une source invalide échoue sans réparation.
2. Convertir cette source validée avec `convertArchiveV2ToV3()` pour établir l’état
   complet attendu. Tous profils, éditions, catalogue et références restent présents.
3. Créer seulement les deux tables/index, puis `INSERT ... SELECT` depuis
   `sessions` + `target_profiles` et depuis `ends` + la nouvelle initiale.
   `LEGACY_DERIVED` est explicite partout, `previousEndUuid = null`.
4. Relire les douze tables sur **la même connexion de migration** ; reconstruire
   V3 et exiger l’égalité complète avec la conversion attendue, puis vérifier
   `PRAGMA foreign_key_check`. Room valide ensuite le vrai schéma 3 avant commit.

Toutes les anciennes lignes restent identiques, index/ordre/dates/statuts/UUID
compris. Aucun recalcul n’est écrit dans un score, jugement ou repositionnement.
Une volée vide a sa capture, une séance sans volée a son initiale. Le V1→V2→V3
crée légitimement un catalogue SQLite vide lorsque le fichier V1 n’en possédait
pas ; cela ne convertit pas une archive **JSON V1** incomplète en sauvegarde complète.

Aucun `REPLACE`, UPSERT, effacement/recréation de table historique, fallback
destructif ou désactivation permanente de FK dans la migration. Les seules
écritures annexes d’ouverture sont les métadonnées gérées par Room (hash/version),
validées dans sa transaction. Une panne après un INSERT de captures ou durant la
validation finale du schéma annule tables et captures et conserve le fichier V2
et son hash. Une migration 1→2→3 interrompue ne devient pas une base exploitable
partiellement migrée.

Le validateur synchrone de migration utilise `runBlocking` uniquement pour appeler
le port de lecture partagé sur **la connexion SQLite déjà fournie par Room**.
Ses accès SQL sont synchrones, sans DAO/Room imbriqué, connexion supplémentaire,
dispatch vers un autre lecteur ou tâche de fond survivant au callback.

## Lecture V3 : un instantané, aucune écriture

`RoomSessionStore.readArchiveV3(limits?)` est distinct des API historiques.
Il ouvre une seule `useReaderConnection` / `deferredTransaction`. Un adaptateur
[`StrictStorageQueries`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/StrictStorageQueries.kt)
utilise uniquement `usePrepared` sur cette connexion ; aucun appel successif aux
méthodes publiques du store ou catalogue.

L’adaptateur sert aussi à la migration. Il exige le véritable type SQLite et la
plage Int avant conversion, préservant les bits Float et les couples secondes/nanos.
Un REAL `18.5` ne devient pas 18 ; un INTEGER 4294967314 ne devient pas 18.
Les chaînes, unités, nanosecondes, dates civiles/IANA/offset et références restent
exactes. Le profil de chaque impact provient de sa volée effective, pas de l’initiale.

La collecte comprend toutes les racines, y compris profils/matériel non référencés,
et tous les descendants. Registre et propriétaires physiques doivent être égaux ;
les enfants/captures orphelins ou inaccessibles sont bloquants. Chaque initiale et
chaque capture est obligatoire. Les positions/compteurs, décisions et traces sont
mappés avec les fonctions historiques, sans nouveau mécanisme de jugement.
Le snapshot complet est validé par `reconstructArchiveV3()` avant tout résultat.

| Résultat | Signification |
|---|---|
| `Found(DomainArchiveV3)` | Toute la base métier représentable, cohérente et validée |
| `NeedsIntervention(ArchiveV3StorageDiagnostic)` | Problème `STORAGE` ou `DOMAIN`, chemin/détail ; diagnostic métier original conservé lorsqu’il existe |
| `TechnicalFailure(cause)` | Erreur SQL, fermeture ou incident technique, distinct d’une incohérence métier |

L’annulation du Job d’origine se propage et ne produit pas de succès partiel.
Une lecture sur magasin fermé ne le rouvre pas. Aucun UPDATE de normalisation.
La migration déclenchée au premier accès à un **ancien** fichier est une phase
d’ouverture transactionnelle ; les preuves de lecture sans écriture portent sur
le fichier déjà ouvert/migré en version 3.

### Budgets de stockage et de transfert

Les plafonds 037 sont obligatoires : 20 000 éléments par collection, 200 000
éléments cumulés, 16 384 caractères UTF-16 par texte. Ils peuvent être resserrés,
jamais agrandis par l’API de lecture. Un précontrôle SQL des comptes évite de
matérialiser des graphes hors budget. La longueur BLOB des textes borne leur
allocation même en présence de NUL (où `length(TEXT)` serait trompeur), puis la
longueur UTF-16 exacte est vérifiée en Kotlin. L’ensemble est de nouveau validé
par le reconstructeur. Tout dépassement donne `LIMIT`, sans troncature.

Ces limites ne sont pas les plafonds de fichier JSON : une archive métier lue
peut encore être trop volumineuse pour les 16 MiB/200 000 valeurs JSON de 038.
Le codec continue de refuser cet encodage sans résultat incomplet. Aucun plafond
de nombre de flèches sportif ou d’ergonomie n’est déduit de ces budgets techniques.

## Fermeture des parcours anciens pour les lignes configurables

Le marqueur est contrôlé **avant** toute projection homogène : `read(uuid)`,
création idempotente, révision `expected/proposed`, lectures d’archives V1/V2,
aperçus et confirmations d’import V1/V2. Les confirmations refont le contrôle sous
verrou écrivain. Une présence configurable bloque la sauvegarde globale ancienne
et l’import global ; aucune liste « complète » n’omet la séance.
Même une ligne configurable aux valeurs homogènes est refusée : V1/V2 perdraient
son contrat de capture/provenance. `discover()` continue de nommer son UUID ;
sa lecture historique signale une intervention, sans masquer l’entrée.

Les API historiques gardent leur comportement sur leurs propres séances et le
catalogue, leurs conflits, atomicité et idempotence. Les insertions de séances/
volées homogènes créent leurs captures dans la transaction existante. Une erreur
de capture annule aussi identités, séance et descendants. Il n’existe aucune
méthode acceptant `ConfiguredSession` en création/mise à jour Room, ni import V3.

## Preuves SQLite et limites physiques

Les nouvelles suites utilisent **le pilote SQLite natif et de vrais fichiers**,
avec DDL/hash V1 et V2 figés, pas des fichiers V3 relabellisés. Le fichier actuel
de seed fournit seulement les valeurs des dix anciennes tables, jamais son schéma.

- Migration V1→V2→V3 et V2→V3 riches ; égalité intégrale des anciennes lignes et
  DDL/index, captures initiales/de volées vides, éditions exactes/personnalisées,
  profils non référencés, catalogue plein/vide et références opaques.
- Statuts, positions/index épars, jugements, X/M, deux invalidations, nanos,
  fermeture/réouverture, `foreign_key_check` et `integrity_check`.
- Égalité avec `convertArchiveV2ToV3()` et aller-retour JSON V3 complet. Les golden
  et octets historiques V1/V2/V3 ne sont pas modifiés.
- Sources corrompues refusées avant DDL, panne après INSERT natif, conflit DDL
  tardif et échec de validation Room : rollback du schéma/captures/hash/version.
- Lecture sous triggers interdisant INSERT/UPDATE/DELETE sur les douze tables,
  UPDATE sans changement prouvé refusé ; observation des instructions même sans
  ligne affectée, aucun DML de lecture ; écrivain concurrent et annulation.
- Configurations absentes, mauvais propriétaire/édition/héritage, graphes orphelins,
  traces ou assertions altérées, types SQL illégaux, budgets racines/cumul/Unicode/NUL.
- Fixtures configurables **uniquement par SQL synthétique** : une volée classique
  à .075 donne bien 10 sous une initiale poulies qui donnerait 9. Tous les chemins
  anciens refusent ces données, même après un aperçu initialement valide.
- Probe d’ouverture du vrai runtime Room attendu en version 2 : contrôle positif
  sur un fichier V2, puis rejet par Room de la migration absente 3→2 après migration,
  sans modification. Ce n’est ni un APK ancien ni un essai sur appareil.

Les ports de lecture et hooks d’observation ne remplacent pas SQLite par un double.
Les tests historiques sont conservés ; leurs seules adaptations sont les assertions
de version cible 3, le comptage des deux tables additives et les fixtures de racine
manquante qui doivent aussi retirer la nouvelle FK enfant. Les anciens contrôles
d’égalité de lignes/DDL excluent uniquement les nouvelles tables, sans les affaiblir.

Validation de livraison : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
XML, builds/lint app/database, schémas 1/2 figés et 3 exporté, liens locaux,
`git diff --check`, puis Quality gate sur le commit exact publié.
Aucun accès Pixel, ADB, APK, base utilisateur ou essai physique.

## Risques restants et prérequis des écritures mixtes

Les versions de schéma sont un engagement : ne jamais installer un APK Room V2
sur les données migrées V3. Une future validation physique doit préparer une
sauvegarde relue puis une installation synthétique isolée compatible ; aucune
restauration/downgrade implicite ni utilisation de la base réelle n’est autorisée ici.

Avant toute création/modification mixte : contrat écrivain complet avec capture
atomique, comparaison `expected` exacte, protection des captures immuables et
traces, conflits/concurrence/rollback/annulation ; planification et restauration
V3 globales avec idempotence ; seulement ensuite SAF et contrôleurs/UI.
Les politiques sportives de remplissage/clôture, statistiques d’interface et
corrections rétroactives/post-clôture restent ouvertes. La lecture ne prouve pas
l’authenticité d’une histoire absente de toutes les sources et ne confère aucune
autorisation de révision. Aucune étape suivante n’est engagée automatiquement.

### Résultat local du 21 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : PASS, trois étapes
`BUILD SUCCESSFUL`. Rapports XML inspectés : modèle 135, scoring/reconstruction
407, codec 92, SQLite natif disque **233**, Android JVM 228 debug + 228 release.
Soit **1 323 exécutions**, zéro échec/erreur/test ignoré ; les variantes Android
partagent des suites et ne représentent pas autant de tests uniques.

Les **53 nouveaux tests SQLite** se répartissent en 13 migrations, 29 lectures
et 11 protections des parcours historiques. Le rollback de **toute** la chaîne
1→2→3 est aussi prouvé : une source V1 invalide garde version/hash V1 et n’obtient
aucune des quatre tables des étapes suivantes. Les 180 tests antérieurs restent
exécutés, avec les seules adaptations de version/FK décrites plus haut.

Assemblages debug app/database et lint : PASS, zéro erreur lint, cinq avertissements
GradleDependency préexistants et messages KMP/AGP non masqués. 273 liens locaux et
`git diff --check` : PASS. Les dix anciennes définitions d’entités du schéma 2
figurent à l’identique dans le schéma 3. Empreintes SHA-256 :

- Schéma 1 : `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`.
- Schéma 2 : `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`.
- Nouveau schéma 3 : `3333739e70b6ffc1781df44624a152ef6940c25f2d4299a23b34b8f41e41a2b7`.

Les golden JSON V1/V3 et sorties V2 restent inchangés, ainsi que toutes les
sources des codecs et du scoring. Aucun appareil ou donnée utilisateur touché.
La CI GitHub est vérifiée séparément sur le SHA exact publié de cette PR.

*21 octobre 2026*
