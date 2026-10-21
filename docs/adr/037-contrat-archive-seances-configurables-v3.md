# ADR 037 — Contrat métier d’archive V3 des séances configurables

> Suite : l’[ADR 038](038-codec-json-v3-strict.md) ajoute le codec JSON autonome,
> sans collecte/restauration Room ni connexion Android V3. Le statut ci-dessous
> décrit le périmètre livré par la PR #37.

Statut : transfert et reconstruction Kotlin purs implémentés. **Aucun codec JSON
V3, stockage Room nouveau, import transactionnel ou parcours Android V3.**
Base exacte : #36 `d9443b54f307ab8767beebe8728e17a20079041d` ; PR empilée sur
`codex/reconcile-android-configured-domain`, en brouillon sans fusion/auto-merge.

## Exigences établies et versions indépendantes

Les [ADR 015](015-sauvegarde-versionnee-reconstruction.md),
[026](026-contrat-sauvegarde-json-v2.md), [027](027-archives-room-v2-atomiques.md),
[033](033-fondation-configurations-par-volee.md),
[034](034-scoring-par-volee-statistiques-mixtes.md),
[035](035-agregat-seances-configurations-mixtes.md) et
[036](036-reconciliation-android-configurations.md) s’appliquent.

| Version | Valeur et portée |
|---|---|
| Archive métier V3 | `ArchiveV3Contract.SCHEMA = 3` : structure des séances configurables |
| Archives publiées | V1 et V2 inchangées : snapshots, codecs, fixtures et résultats |
| Scoring | `ArchiveV3Contract.SCORING = 1`, aucune nouvelle règle de score |
| SQLite | Room 2, schémas exportés 1/2 et migration 1→2 inchangés |
| Profils | UUID de définition immuable et édition séparée, sans nouveau règlement |

Le nouveau paquet `core.scoring.archive.v3` ne dépend d’aucun codec, SQL ou écran.
`DomainArchiveV3` n’hérite ni de `DomainArchive` ni de `DomainArchiveV2`, et ne
possède aucune projection vers eux. Leurs encodeurs ne peuvent pas le recevoir.
Une V3 valide est un contenu métier complet ; ce n’est pas encore un fichier de
sauvegarde utilisable, une preuve d’authenticité ou une autorisation d’écraser Room.

## Schéma conceptuel et champs explicites

Les déclarations dans
[`ArchiveV3Snapshot.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/archive/v3/ArchiveV3Snapshot.kt)
font foi. Les arguments des DTO n’ont **aucun défaut Kotlin**, y compris ceux dont
la valeur sémantique peut être `null`. Le futur codec devra exiger chaque clé :
une clé nullable absente n’est pas un `null` fourni. Le contrat pur n’est pas un
lecteur capable de détecter des clés JSON absentes, inconnues ou dupliquées.

| Objet | Propriétés |
|---|---|
| `ArchiveV3Snapshot` | `schemaVersion`, `scoringContractVersion`, `profiles`, `sessions`, `bows`, `arrowSets` |
| `ConfiguredSessionSnapshot` | `uuid`, `metadata`, `initialConfiguration`, `initialConfigurationOrigin`, `status`, `startedAt`, `endedAt`, `ends`, `createdAt`, `updatedAt` |
| `ConfiguredMetadataSnapshot` | `date`, `title`, `plannedEndCount`, `bowUuid`, `arrowSetUuid`, `locationName`, `notes` |
| `EndConfigurationSnapshot` | `targetTypeUuid`, `regulatoryEdition`, `distanceM`, `arrowCountMode`, `arrowCountPerEnd`, `isIndoor`, `scoringMode` |
| `ConfiguredEndSnapshot` | `sessionUuid`, `end`, `configuration`, `origin` |
| `EndConfigurationOriginSnapshot` | `kind`, `previousEndUuid` |

Les quatre listes racines sont obligatoires, même vides. Une archive vide, de
matériel seul ou de profils seuls est valide. Profils non référencés et fiches
non associées sont conservés. Aucun tri, dédoublonnage ou sélection du plus récent.
Les listes gardent leur ordre ; le calcul #34 continue de fournir son propre ordre
canonique. Les valeurs acceptées possèdent toutes leurs collections non modifiables.
Les DTO restent des transferts non fiables, éventuellement mutables : ne pas les
modifier pendant leur reconstruction ; les muter ensuite n’affecte pas le domaine.

Les profils réutilisent `TargetProfileSnapshot` : définition `TargetType` complète,
zones/ordre/couleurs et édition. Une référence de configuration doit retrouver
**cette définition dans l’archive**, puis vérifier son édition identique. Aucun
profil actuel, diamètre, distance par défaut ou configuration initiale ne remplace
la valeur effective d’une volée. La configuration contient toutes ses valeurs,
même égales à celles héritées ou aux défauts du profil.

Les métadonnées conservent le triplet civil (`localDateTime`, zone IANA, offset
choisi), les chaînes facultatives et les références opaques. Un UUID de matériel
non résolu reste intact ; aucune fiche n’est fabriquée, aucun type/existence n’est
déduit et aucune référence n’est remplacée par `null`. Les références doivent
néanmoins être des UUID v4 canoniques lorsqu’elles sont fournies, comme en V1/V2.

`EndSnapshot`, `ImpactSnapshot`, décisions, assertions et traces sont réutilisés
car ils décrivent déjà des enfants sous un **profil effectif explicite**. Seul
`SessionSnapshot` homogène est remplacé. Les champs du catalogue réutilisent les
`BowSnapshot`/`ArrowSetSnapshot` publiés, sans interpréter spine, longueur ou pointe.
Aucun ID Room, statistique dérivée, UUID ou date de configuration n’est ajouté.

## Capture et provenance

L’initiale conserve `InitialConfigurationOrigin.CREATION` ou `LEGACY_DERIVED`,
même sans volée. Les origines de volée sont :

| `kind` | Contraintes |
|---|---|
| `INITIAL` | Première volée selon l’index, valeur égale à l’initiale, `previousEndUuid = null` |
| `INHERITED` | UUID canonique de la volée d’index immédiatement précédent ; configuration complète identique |
| `EXPLICIT` | Choix pour cette volée, même égal à la précédente ; `previousEndUuid = null` |
| `LEGACY_DERIVED` | Initiale d’origine historique et configuration homogène égale à cette initiale ; `previousEndUuid = null` |

Un héritage ne crée ni identité ni instant de sélection. Une origine explicite
est une déclaration conservée, pas une preuve de confirmation humaine. Une volée
vide est déjà liée définitivement à son propriétaire et sa configuration.
Une capture nouvelle exige une séance démarrée et ne peut précéder son début.
Les anciennes volées dérivées peuvent provenir d’un brouillon historique sans
`startedAt` ; leurs index épars sont conservés, sans événements inventés.

Les statuts et nombres restent ceux du domaine : validation vide/incomplète/en
excès et clôture avec provisoire ne reçoivent pas de nouvelle politique sportive.
Le contrat peut les représenter sans certifier une épreuve ou une comparaison
réglementaire de scores hétérogènes.

## Reconstruction intégrale et réutilisation contrôlée

[`reconstructArchiveV3(snapshot, known?, limits)`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/archive/v3/ArchiveV3Reconstruction.kt)
retourne seulement `Accepted(DomainArchiveV3)` ou `NeedsIntervention(source, issue)`.
La source rejetée et le premier problème typé sont conservés ; aucun sous-ensemble
valide ne s’échappe. Les étapes sont :

1. Exiger archive 3/scoring 1 et contrôler les budgets du transfert.
2. Valider les représentations partagées profils/matériel par le validateur V2,
   **sans y passer aucune séance configurable**. Ce partage interne ne constitue
   ni archive projetée ni conversion publique perdant des données.
3. Contrôler l’unicité UUID v4 globale entre profils, séances, volées, impacts,
   arcs et flèches. Les références ne revendiquent pas de nouvelle identité.
4. Reconstruire métadonnées, initiale et configurations effectives exactes,
   propriétaires et index ; vérifier chaque impact sous la cible de sa volée.
5. Rejouer chaque volée avec le composant **historique** de reconstruction :
   décisions typées, valeurs autorisées, assertions, frontières réelles, dates,
   continuité des repositionnements et motifs d’invalidation. Aucun tube brut
   d’évidence n’est exposé et aucun score contradictoire n’est corrigé silencieusement.
6. Vérifier la chaîne d’héritage, les dates parent/enfant, validation/clôture et
   les règles de capture, puis appeler la réhydratation interne de `ConfiguredSession`.
7. Si un connu est fourni, comparer les identités et contenus complets avant acceptation.

La capture depuis le domaine exige elle aussi chaque définition complète et édition
exacte, initiale comprise, avant de créer ses références UUID. Un profil de même
UUID/géométrie mais renommé ou réédité ne peut ainsi remplacer silencieusement
celui de l’objet source lors du snapshot.

Le constructeur de `ConfiguredSession` reste privé. Les nouveaux points de
composition/réhydratation sont `internal` et `@JvmSynthetic`, sans nouveau
constructeur public ni copie paramétrable. Ils conservent la validation complète
existante et les contraintes des captures nouvelles ; une réhydratation ne
confère aucune permission de révision.

Seuls des points de partage internes sont ajoutés aux fichiers historiques :
validation/capture d’un `End` et composition d’une séance historique déjà validée.
Les corps des règles de reconstruction/scoring et les contrats V1/V2 sont conservés.
Aucune fausse séance homogène, identité technique ou date de conversion n’est créée.

Les événements passent par `UtcTimestamps` : offset explicite, émission `Z`,
nanosecondes conservées. Les dates naïves, offsets inconnus négatif zéro,
chronologies inversées et triplets civils incompatibles sont refusés. Les textes
Unicode valides, espaces, chaînes vides facultatives, unités et bits Float sont
conservés ; aucun trim, remplacement Unicode ou passage Float→Double n’est appliqué.

## Connu et limites de preuve

Les diagnostics `ArchiveV3Issue(code, path, detail)` désignent un chemin du DTO,
par exemple `sessions[0].ends[2].end.impacts[0].targetTypeUuid`.

| Codes | Problème |
|---|---|
| `MISSING_FIELD`, `UNSUPPORTED_VERSION` | Valeur indispensable absente ou version incompatible |
| `INVALID_UUID`, `DUPLICATE_UUID` | Identité non canonique ou collision globale |
| `UNKNOWN_PROFILE`, `PROFILE_IDENTITY_CONFLICT` | Définition absente, inconnue ou édition/contenu contradictoire |
| `MISSING_PROVENANCE`, `WRONG_OWNER` | Origine absente ou volée rattachée à une autre séance |
| `CONFIGURATION_CONFLICT`, `INHERITANCE_CONFLICT` | Paramètres incohérents ou chaîne de capture invalide |
| `TEMPORAL_CONTEXT`, `CIVIL_CONTEXT_CONFLICT` | Événement ou date civile non reconstructible sans supposition |
| `INVALID_DOMAIN_VALUE`, `CONTRADICTORY_DERIVED_VALUE` | Violation métier, Unicode mal formé ou assertions de score contradictoires |
| `HISTORY_CONFLICT`, `DIVERGENT_REVISION` | Histoire connue omise/réécrite, réaffectation ou contenu connu différent |
| `LIMIT` | Budget technique dépassé, sans troncature |

La comparaison avec `known` reste **conservatrice**, comme l’ADR 015 : une
révision différente n’est jamais sélectionnée par `updatedAt`. Configuration
initiale/effective déjà connue, origine, propriétaires, décisions et traces ne
peuvent être remplacés silencieusement, même à vide. Les suppressions d’enfants
connus et les modifications après validation/clôture sont signalées. Une révision
locale autorisée relève de `validateConfiguredSessionUpdate` et d’un futur
stockage `expected/proposed`, pas d’un import qui devinerait sa causalité.

Les racines absentes ne sont pas des instructions de suppression. Sans connu,
la reconstruction vérifie la cohérence déclarée mais ne peut découvrir une trace
entièrement omise de toutes ses sources ni authentifier une confirmation humaine.
Ce contrat n’est ni journal d’événements complet, ni signature, ni mécanisme de fusion.

## Conversions historiques sans perte

[`convertArchiveV2ToV3(source)`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/archive/v3/DomainArchiveV3.kt)
prend une **`DomainArchiveV2` déjà validée**, conserve tous profils (non référencés
compris), arcs et jeux de flèches, et dérive chaque configuration depuis les
valeurs réelles de la séance et son profil enregistré. Toutes les origines sont
historiques, les dates/UUID/statuts/décisions/traces/références inchangés.
Il n’y a aucune projection V2→V1. Le résultat passe la reconstruction V3 entière ;
un refus explicite (notamment budget) ne rend pas de contenu complet tronqué.

`convertArchiveV1ToConfiguredContent(source)` prend une `DomainArchive` validée
et retourne **`LegacyV1ConfiguredContent`**, distinct d’une V3 complète :
`equipmentAvailability = NOT_PROVIDED`, profils et séances configurables connus,
sans listes matériel inventées, `toSnapshot` V3 ou encodeur. Son absence de catalogue
n’est pas un catalogue vide. Pour produire une archive complète, il faudra une
source de catalogue fiable et cohérente ; la collecte V2 atomique fournit déjà
un tel contrat. Aucune concaténation implicite de lectures V1/catalogue indépendantes.
Cette PR ne livre pas de fonction qui ferait passer une V1 seule pour une V3 complète.

V2 avec `bows = []` et `arrowSets = []` est au contraire une source explicitement
complète avec zéro fiche reçue ; ses références opaques sont néanmoins conservées.
Ni absence V1, ni listes vides V2/V3 ne donnent une autorisation d’effacer un magasin.

## Budgets actuels et futur codec JSON V3

`ArchiveV3Limits` borne dès maintenant chaque collection à 20 000 éléments, la
somme des éléments de toutes les collections (zones et traces incluses) à
200 000, et chaque texte à 16 384 caractères UTF-16. Les paramètres doivent être
positifs ; tout dépassement échoue avec chemin, sans wrapping entier ni troncature.
Les surrogates isolés sont rejetés, les paires Unicode valides sont préservées.

Ces budgets de DTO ne sont **pas** un lecteur de fichiers borné. Le futur codec
devra au minimum reprendre les plafonds publiés V1/V2 : 16 MiB UTF-8, profondeur
32, 200 000 valeurs JSON, 20 000 éléments par tableau, 64 propriétés par objet,
16 384 caractères par chaîne/clé, 128 caractères par nombre. Il devra compter
l’arbre entier V3, exiger toutes les clés (null explicite compris), rejeter
inconnues/dupliquées, enums inconnus, BOM/UTF-8 invalide et contenu tronqué/suffixé,
conserver les lexèmes Float et appliquer les limites à la sortie également.
Ces contraintes syntaxiques ne sont pas prétendues implémentées ici.

Les codecs actuels restent V1/V2 et rejettent le numéro 3. Aucun sélecteur,
launcher SAF ou appel Room ne consomme `DomainArchiveV3`.

## Vérification et étapes suivantes

80 nouveaux tests : 76 de reconstruction métier et 4 de compatibilité des codecs
existants. Ils couvrent round-trip complet, profils classique/poulies 18/70 m,
3/6/autre/FREE, vide/provisoire/validé/terminé, origines, 15 paires de catégories
UUID, héritage/propriétaire, dates civiles/UTC/nanos, décisions/X/M et plusieurs
traces, Unicode/bits Float, immutabilité, budgets, connu et conversions sans perte.
Les golden V1 restent comparées octet par octet ; V2 garde ses octets et tous ses
champs après une conversion/reconstruction métier V3. Les sept coexistences #36
et toutes les anciennes suites restent présentes sans modification.

Exécuter `./scripts/dev-check.sh --rerun-tasks --console=plain`, inspecter XML,
SQLite natif disque, builds/lint, schémas 1/2, liens et `git diff --check`, puis
vérifier le Quality gate GitHub sur le commit publié de cette PR.
Aucun appareil, ADB, donnée utilisateur ou essai physique dans cette tâche.

Étapes séparées après validation du contrat : codec JSON V3 strict et golden,
choix du schéma Room successeur selon les versions réellement intégrées,
reconstruction/migration non destructives, collecte/import atomiques avec conflits,
rollback et commit incertain, puis seulement SAF/contrôleurs/UI producteurs.
Aucune de ces étapes n’est commencée automatiquement.

Restent ouverts : politiques sportives de remplissage/clôture, plafonds UX,
maximums planifiés et présentation des statistiques, correction rétroactive de
configuration et révisions post-clôture. Le contrat ne les décide pas implicitement.

### Résultat local du 21 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : **PASS**, rapports XML
inspectés. Modèle 135, scoring/reconstruction 407 (dont 76 nouveaux), codec 42
(dont 4 nouveaux), SQLite natif disque 180, Android JVM 228 debug + 228 release :
**1 220 exécutions**, zéro échec/erreur/test ignoré. Les variantes Android partagent
des suites ; ce nombre ne désigne pas autant de tests uniques.

Assemblages debug et lint app/database : PASS, zéro erreur lint et cinq
avertissements GradleDependency préexistants. Les messages de compatibilité
KMP/AGP ne sont pas masqués et aucune dépendance n’est actualisée.
250 liens documentaires locaux et `git diff --check` : PASS.
Les schémas 1/2 conservent leurs empreintes SHA-256
`917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62` et
`e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`.
Les codecs/DTO/golden V1/V2, stockage, coexistences #36 et Android sont inchangés.
Le Quality gate reste vérifié séparément sur le commit exact publié de cette PR.
