# ADR 025 — Fondation du catalogue local de matériel

## Portée et préalables

Ajouter `Bow` et `ArrowSet` au domaine et à SQLite, sans écran, association de
séance nouvelle, suppression ni évolution du JSON V1. Le parcours de visée précise
en cours de validation reste indépendant. Les ADR [008](008-integrite-horodatages-domaine.md),
[014](014-convention-temporelle-utc.md), [016](016-persistance-locale-room.md),
[017](017-mise-a-jour-seances-room.md), [021](021-export-json-versionne-android.md)
et [022](022-import-json-controle-transactionnel.md) restent applicables.

L'audit trouve huit tables V1 et un registre `entity_identities`. Les séances
conservent déjà `bowUuid` / `arrowSetUuid`, mais ces références sont opaques : ni
existence, ni type d'une fiche, ni clé étrangère ne sont présumés. Les anciennes
projections documentaires avec `id: Long` ne décrivent pas le schéma réel.

## Valeurs métier immuables

Le package `core:model.equipment` expose les classes finales `Bow`, `ArrowSet`
et leur interface scellée `Equipment`. Chaque constructeur valide un instantané :

- UUID v4 canonique, minuscules et variante RFC 4122 ; nom non blanc, conservé tel quel ;
- `createdAt` et `updatedAt` explicites en `Instant`, avec `createdAt <= updatedAt` ;
- `drawWeightLbs`, si présent, fini et strictement positif ; aucune borne physique inventée ;
- champs descriptifs optionnels conservés exactement, y compris chaînes vides,
  espaces, Unicode et unités saisies ; aucun parsing implicite de spine, longueur
  ou poids de pointe, aucune conversion et aucune liste fermée de types d'arc.

`copy(...)` construit une nouvelle valeur puis applique
`validateEquipmentUpdate(expected, proposed)` : même type, UUID et création ;
révision non décroissante. L'égalité des dates est permise comme dans l'ADR 008 ;
aucune horloge implicite ni incrément artificiel. L’appelant choisit la date de révision ; par défaut, `copy` conserve la date
existante, sans prétendre dater automatiquement une modification utilisateur. L'égalité et le hash portent sur tous les champs ; une
`data class` privée évite un `copy` généré qui contournerait ces contrôles.

Un instantané reconstruit indépendamment ne prouve pas sa filiation. Le stockage
réapplique donc ce validateur au couple courant/proposé, même si le constructeur
ou une reconstruction a contourné la copie de la valeur initiale.

## Schéma Room 2 et migration 1→2

Le schéma `SessionDatabase/1.json` reste figé. `2.json` ajoute seulement :

| Table | Contenu |
|-------|---------|
| `bows` | UUID primaire, nom, type, puissance en bits Float, marque, modèle, notes, création/révision |
| `arrow_sets` | UUID primaire, nom, spine, longueur, poids de pointe, encoche, empennage, matériau, notes, création/révision |

Le registre reçoit les types `BOW` et `ARROW_SET`. Chaque nouvelle table possède
une FK UUID vers ce registre, avec `RESTRICT`. Les huit tables antérieures, leurs
colonnes, index, FK et lignes restent inchangés. Aucun ID numérique n'est ajouté.
Les instants utilisent les couples entiers seconde/nanoseconde de l'ADR 016 ;
les composantes manquantes, nanos invalides et chronologies inversées sont des
diagnostics, jamais des valeurs normalisées. La puissance conserve ses bits
IEEE 754, puis est revalidée par le modèle à la lecture.

`MIGRATION_1_2` exécute deux `CREATE TABLE` dans la transaction de migration Room.
Elle est enregistrée dans **les deux fabriques Android et JVM**. Aucun `IF NOT EXISTS`
ne masque une table inattendue lors de cette migration ; aucune réécriture,
suppression, `REPLACE`, fallback destructif ou migration descendante n'est prévu.
Un schéma inconnu ou une migration impossible échoue en conservant les données.
Le numéro Room 2 ne change ni `schemaVersion = 1` ni `scoringContractVersion = 1`.

## API et identités globales

`RoomSessionStore.equipment` expose `RoomEquipmentCatalog` sur la **même base**,
le même verrou SQL et le même cycle de vie : pas de second magasin Android ou
connexion ouverte depuis Compose.

- `create(Bow)` / `create(ArrowSet)` : `Created`, `AlreadyExists` seulement si tout
  le contenu correspond, sinon `Conflict`, `NeedsIntervention` ou `TechnicalFailure`.
- `readBow(uuid)` / `readArrowSet(uuid)` : `Found` typé, `NotFound`,
  `NeedsIntervention` ou `TechnicalFailure`.
- `discoverBows()` / `discoverArrowSets()` : UUID triés, y compris racines
  corrompues et identités orphelines ; une découverte impose encore une lecture.
- `update(expected, proposed)` sur chacun des deux types : `Updated`, `Unchanged`,
  `NotFound`, `Conflict`, `NeedsIntervention` ou `TechnicalFailure`.

Les créations et révisions ont une transaction écrivain immédiate. Elles relisent
le modèle et l'identité, comparent **l'état complet** avant l'idempotence,
valident la transition, écrivent avec échec `ABORT`, puis relisent et comparent
avant commit. Une attente obsolète reste un conflit même si la proposition est
déjà égale au courant. Aucun retry/rebase automatique ou arbitrage par date.
Une erreur après écriture doit sortir de la transaction pour annuler toute
l'opération, y compris la nouvelle identité. L'annulation coroutine est propagée,
avec vérification du contexte appelant avant commit malgré le contexte Room.

Les UUID sont exclusifs entre arcs, jeux de flèches, profils, séances, volées et
impacts. Les identités des profils publiés sont aussi réservées avant leur première
persistance. Les lectures vérifient le registre **et** les propriétaires physiques.
Les créations/révisions de séances et l'import contrôlent également les lignes
physiques : une identité manquante dans une base corrompue ne permet pas de
réattribuer silencieusement l'UUID d'une fiche. Aucun diagnostic ne répare les données.
Le contrôle des lignes atteignables de l'import reste limité aux entités du
contrat JSON V1 ; les fiches légitimes ne deviennent pas de faux orphelins d'archive.

## Références historiques et frontière de sauvegarde

`Session.bowUuid` et `Session.arrowSetUuid` gardent leur contrat opaque, même si
une fiche du même UUID existe désormais. Aucune FK vers le matériel, résolution,
réparation ou nouvelle validation d'existence/type n'est ajoutée aux séances et
archives. Une archive historique sans fiches reste lisible et importable sans
perdre ses références.

**Les fiches matériel ne sont pas sauvegardées dans JSON V1.** La racine, les
versions, les octets encodés et les contrats d'import/export des séances restent
inchangés. Aucun écran ni commande utilisateur de création/édition de matériel
n'est exposé dans cette PR. Copier seulement une archive V1 ne sauvegarde pas
le catalogue ; désinstallation ou perte de la base le perdrait.

Avant toute utilisation du catalogue dans Compose, une PR dédiée doit définir
un contrat de sauvegarde versionné incluant les fiches et leurs références,
avec lecture rétrocompatible du V1, reconstruction contextualisée, collisions
globales et import atomique de l'ensemble. Cette PR ne définit pas implicitement
ce futur format, ne convertit pas les références opaques, et n'ajoute ni moteur
complet de migration d'archives ni stratégie de fusion.

## Vérifications

Les tests purs couvrent champs optionnels, valeurs limites, UUID, noms,
puissance, copies, révisions, égalités et nanosecondes. Les tests SQLite natifs
utilisent de vrais fichiers disque avec fermeture/réouverture, deux magasins,
conflits à dates égales, idempotence sous triggers interdisant l'écriture,
corruptions et rollbacks, dont une annulation après une véritable insertion.

La fixture `room-schema-v1.sql` provient du **schéma 1 figé**, avec son hash Room.
Le test construit cette base V1 indépendamment, sans rétrograder une base V2.
Seules des lignes synthétiques des huit tables inchangées sont copiées depuis
un magasin de préparation : deux séances, profil/zones, volées, impacts, décisions
manuelles, deux traces de repositionnement par séance, X/M, références opaques,
bits Float et nanosecondes. Le test prouve avant ouverture V1 l'absence de tables
catalogue et la présence de données dans les huit tables. Après migration, il
compare chaque ancienne ligne et définition SQL, le domaine complet et tous les
octets JSON ; puis ferme et rouvre encore la base et utilise les nouvelles tables.
Une erreur au second `CREATE` vérifie le rollback de la migration elle-même.

Les tests d'import JSON V1 vérifient conservation des références, absence de
modification du catalogue, idempotence sans écriture, plan périmé après réservation
d'un UUID et concurrence import/catalogue sur le verrou commun. La validation
complète reste `./scripts/dev-check.sh --rerun-tasks --console=plain`.
Aucun test ADB, instrumenté ou essai physique n'est effectué pour cette fondation.

*17 octobre 2026*
