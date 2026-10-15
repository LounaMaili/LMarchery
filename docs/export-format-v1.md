# Contrat de sauvegarde V1 — LMarchery

> Snapshots Kotlin et reconstruction validée ; aucun fichier importé ou exporté.

## 1. État implémenté

Le paquet `core.scoring.archive` fournit `ArchiveSnapshot`,
`snapshotArchive(profiles, sessions)`, `DomainArchive.toSnapshot()` et
`reconstructArchive(snapshot, known?)`. Le résultat est `Accepted(archive)` ou
`NeedsIntervention(source, issue)`, sans I/O ni résolution automatique.

Les modèles acceptés sont immuables et possèdent leurs collections. Les snapshots
sont des données de transfert non fiables et copiables, potentiellement adossées
à des listes mutables ; ne pas les modifier pendant leur validation.

Le module `core:database` utilise ce contrat pour créer, relire et mettre à jour
des séances en Room/SQLite, utilisées par le premier parcours Compose ; voir les
[ADR 016](adr/016-persistance-locale-room.md) et
[ADR 017](adr/017-mise-a-jour-seances-room.md) et
[ADR 018](adr/018-integration-android-room.md). Il n'existe ni lecteur/écrivain JSON,
ni restauration de fichier Android, ni fusion entre appareils.
L'extension `.lmarchery.json`, UTF-8, MIME `application/json` et
le partage par intent restent prévus. Aucun format opérationnel antérieur n'a
été publié. L'ancien exemple `version: 1` était préparatoire ; aucune migration
ne lui attribue un fuseau ou une provenance manquante.

## 2. Identités et versions distinctes

| Élément | Contrat |
|---------|---------|
| Identité du profil | UUID v4 stable d'une définition publiée immuable |
| Définition | `TargetProfileSnapshot.definition`, `TargetType` complet, zones comprises |
| Édition réglementaire | `regulatoryEdition: String?` séparée : `2026-01-27` pour les quatre WA, `null` pour les quatre fixtures historiques |
| Schéma | `schemaVersion: 1`, obligatoire ; toute autre valeur est non prise en charge |
| Contrat de scoring | `scoringContractVersion: 1`, obligatoire, indépendant du schéma et du règlement |

Le contrat de scoring 1 désigne le calcul radial actuel en `Float` après calcul
intermédiaire en `Double`, les limites exactes intérieures, les décisions
contextualisées sans promotion tactile (ADR 011), les invalidations tracées
(ADR 012) et les événements UTC (ADR 014). Ce numéro ne certifie pas un règlement.
Un changement de représentation incrémente le schéma ; un changement incompatible
de cette sémantique exige une nouvelle version du contrat de scoring et une
stratégie explicite. Aucune compatibilité avec une version inconnue n'est présumée.

### Profils complets et registre

`BuiltInTargetRegistry` réunit les huit définitions existantes, sans dépendance
de `core:model` vers `core:scoring`. Leurs UUID et règles restent inchangés.
Voir [ADR 013](adr/013-profils-indoor-world-archery.md) pour les identités WA.

Pour un UUID connu, **tous** les champs sont comparés : nom, discipline, diamètre,
bornes de score, valeurs par défaut, `isBuiltIn`, configuration X et liste complète
de zones, ordre et couleurs compris. L'édition est vérifiée séparément. Une
définition altérée échoue, même si elle revendique `isBuiltIn=true`, ou se présente
comme personnalisée. Aucun profil courant n'est substitué silencieusement.

Un UUID inconnu revendiquant `isBuiltIn=true` reste non résolu. Une définition
personnalisée structurellement valide, avec une identité propre et `isBuiltIn=false`,
est conservée intégralement. Son édition éventuelle est déclarative, sans certification.
Deux contenus ou éditions différents sous le même UUID sont un conflit, dans
l'archive ou face au référentiel connu fourni. Une nouvelle définition reçoit
une nouvelle identité.

`TargetType` contient exactement `uuid`, `name`, `discipline`, `diameterCm`,
`maxScore`, `minScore`, `defaultArrowCount`, `defaultDistanceM`, `isBuiltIn`,
`xRingEnabled`, `xRingLabel`, `innerTenRadiusNormalized`, `zones`.
Chaque zone contient `score`, `innerRadiusNormalized`, `outerRadiusNormalized`,
`colorName`, `colorHex`. Le profil n'a **ni `id`, ni `createdAt`, ni `updatedAt`**.
L'édition enveloppe sa définition sans modifier artificiellement le modèle.
Les zones n'ont pas d'UUID autonome. L'ADR 009 impose un blason concentrique complet.

## 3. Contenu du snapshot 1

Les déclarations dans `ArchiveSnapshot.kt` font foi pour les noms et types du
contrat typé. La racine contient `schemaVersion`, `scoringContractVersion`,
`profiles`, `sessions`. Chaque profil inclut sa définition complète et son
édition explicite ou `null`, même pour un built-in déjà connu.

Chaque séance conserve toutes ses propriétés Kotlin : UUID, triplet civil,
statut, début/fin, nombre prévu de volées, titre, `targetTypeUuid`, distance,
mode/nombre de flèches, intérieur/extérieur, mode de score, références optionnelles
de matériel, lieu, notes, volées, création/révision. Chaque volée conserve UUID,
index, statut, notes, création/révision/validation et impacts. Les relations
séance → volée → impact sont imbriquées, sans faux IDs Room.

Un impact conserve UUID, index, `targetTypeUuid`, `point: {x, y}`, `inputMode`,
note, création/révision, décision et liste `repositions` ordonnée. Sa référence
de cible doit être celle de la séance. Toute référence exige la définition dans
l'archive, même si le registre connaît déjà l'UUID. La capture vérifie que la
définition fournie est exactement celle de la séance source.

Les UUID définissant profils, séances, volées et impacts sont uniques dans
toute l'archive, y compris entre types. Ils sont canoniques, minuscules, version 4,
variante RFC 4122. Les références optionnelles de matériel sont des UUID opaques :
`Bow`, `ArrowSet`, `SessionPreset` restent prévus et absents du Kotlin actuel.
Leur contenu et leur résolution complète ne sont pas implémentés par ce contrat.

### Décisions et assertions

`DecisionSnapshot` exige une provenance explicite et `selected: BoundaryValue`
(score et distinction X). Aucun score ou booléen historique ne remplace cette
provenance. X n'est jamais un point supplémentaire.

| `kind` | Contraintes |
|--------|-------------|
| `GEOMETRY` | Date manuelle, limite et motif nuls ; valeur égale à la géométrie |
| `BOUNDARY_JUDGMENT` | Date et référence réelle `(type, radiusNormalized)` ; un des deux côtés ; motif nul |
| `EXCEPTIONAL_CORRECTION` | Date, valeur légale et motif non blanc ; limite nulle |

Le jugement humain reste indépendant de la tolérance UX, qui n'attribue jamais
de score. Une confirmation de même score reste manuelle. `inputMode` décrit
séparément la provenance de la position.

`ScoreAssertions`, facultatif, peut contenir distance, score calculé, score final,
X géométrique, X/M finaux et `isCordDecisionManual`. Toute valeur reçue est
recalculée et comparée exactement dans les types actuels ; une contradiction
échoue au lieu d'être effacée. Les totaux/statistiques sont recalculés depuis les
agrégats, sans deuxième source de vérité dans le snapshot.

### Repositionnements

Chaque trace conserve `impactUuid`, `impactCreatedAt`, `inputMode`, `previousPoint`,
`previousDecision` complète avec assertions éventuelles, `previousUpdatedAt`,
`newPoint`, `changedAt`, `reason`.

Le rejeu part du premier ancien point, ou du point courant sans trace. Il résout
la décision précédente, conserve sa révision, puis appelle `ArrowImpact.reposition()`
pour chaque déplacement. Il vérifie contexte, continuité spatiale, monotonie,
motif d'invalidation manuelle, date des jugements et point d'arrivée, avant de
résoudre la décision courante. Les traces opaques sont émises par le domaine ;
aucune fabrique brute n'est ajoutée. Les parents vérifient ensuite statuts,
unicité, profil exact et couverture temporelle.

Ce rejeu conserve les traces reçues ; il ne prouve pas un événement omis de toute
source connue. Ce n'est ni un journal complet des décisions ni une preuve
cryptographique. Un remplacement doit être évalué face aux données déjà connues.

### Dates et données absentes

Les événements exigent un offset explicite ; `UtcTimestamps.parse` produit un
`Instant` et `format` écrit `Z`, sans perte de nanosecondes. Les dates naïves,
invalides et offsets inconnus négatif zéro sont non résolus. Le triplet civil
exige date/heure locale, fuseau IANA et offset, même hors changement d'heure.
Une répétition conserve l'offset choisi ; un trou ou un offset incompatible avec
les règles IANA disponibles n'est jamais normalisé. Voir
[ADR 014](adr/014-convention-temporelle-utc.md).

Les propriétés sans valeur par défaut des snapshots sont nécessaires, listes
et `null` explicites compris. Les versions, dates, définitions et provenances
nullables permettent de diagnostiquer certaines données manquantes, jamais de les
valider par défaut. Les propriétés non nullables absentes, enums inconnus,
coordonnées invalides et définitions impossibles devront être rejetés par le
futur décodeur avant construction. Celui-ci devra aussi traiter explicitement
les champs inconnus sans ignorer une provenance nouvelle. Aucune validation
syntaxique JSON n'est implémentée ici.

## 4. Conflits et intervention

`NeedsIntervention` conserve le snapshot reçu et un `ArchiveIssue` avec code,
chemin et détail. Le premier problème est signalé ; aucune séance partielle
n'est rendue comme acceptée. Une nouvelle validation après correction reste explicite.

| Situation | Code |
|-----------|------|
| Version absente / inconnue | `MISSING_FIELD` / `UNSUPPORTED_VERSION` |
| Définition absente, référence inconnue, faux built-in | `UNKNOWN_PROFILE` |
| Profil connu de contenu/édition différent | `PROFILE_IDENTITY_CONFLICT` |
| Provenance absente | `MISSING_PROVENANCE` |
| Événement naïf/invalide | `TEMPORAL_CONTEXT` |
| Triplet civil incomplet | `MISSING_FIELD` |
| Triplet incompatible avec les règles IANA courantes | `CIVIL_CONTEXT_CONFLICT` |
| Assertion dérivée contradictoire | `CONTRADICTORY_DERIVED_VALUE` |
| Trace incohérente, supprimée ou réécrite face au connu | `HISTORY_CONFLICT` |
| Autre divergence d'identité connue, parent compris | `DIVERGENT_REVISION` |
| Autre violation métier | `INVALID_DOMAIN_VALUE` |

`reconstructArchive(snapshot, known)` exige le référentiel connu complet concerné.
Un contenu identique est accepté ; toute divergence exige intervention, que
`updatedAt` soit égal, inférieur ou supérieur. Ni une horloge ni un préfixe de
traces ne prouvent la causalité. La disparition d'un impact porteur de traces
dans une séance connue est détectée, ainsi que la réutilisation d'un impact sous
d'autres identités parentes. Les racines absentes d'une archive partielle ne sont
pas des instructions de suppression. Aucune fusion ni sélection du plus récent
n'est effectuée.

Sans `known`, il s'agit d'une restauration indépendante, incapable de découvrir
une histoire absente. Le futur adaptateur de remplacement devra fournir les
données connues, sans confondre validité structurelle et autorisation d'intégration.
Une ancienne date ou un triplet devenu incompatible après mise à jour IANA reste
identifiable dans les données reçues, sans conversion silencieuse en séance valide.

## 5. Persistance et étapes restantes pour les fichiers

Room/SQLite 1 conserve déjà profils complets, enfants ordonnés, décisions et
traces dans des tables reliées par UUID. Les instants sont des couples entiers
`epochSecond` / `nano`, la date civile garde son triplet et les `Float` leurs bits.
Le schéma Room exporté est distinct de `schemaVersion` et de `scoringContractVersion`.
Aucun JSON de données utilisateur n'est employé comme substitut aux relations.
Les révisions locales passent par `RoomSessionStore.update(expected, proposed)`
et le validateur indépendant de transition de l’[ADR 017](adr/017-mise-a-jour-seances-room.md),
sans assouplir les conflits de `reconstructArchive(snapshot, known)`.
Le format et les versions de sauvegarde restent inchangés.

Restent à réaliser :

- Prévoir les migrations explicites pour chaque évolution future du schéma Room.
- Développer le JSON strict, les limites de taille et les erreurs syntaxiques ;
  préserver les décimales nécessaires au même `Float`.
- Définir matériel/presets et portée d'une sauvegarde utilisateur complète.
- Définir l'intervention sur conflits et l'éventuelle preuve de filiation des
  révisions, sans écrasement fondé sur la seule horloge.
- Décider le traitement d'anciens horaires/provenances et des changements IANA
  à partir de sources fiables, sans migration fictive ni fuseau inventé.

Voir [ADR 015](adr/015-sauvegarde-versionnee-reconstruction.md).

*Dernière mise à jour : 15 octobre 2026*
