# ADR 038 — Codec JSON V3 strict et autonome

> Suite : l’[ADR 039](039-room-v3-migration-lecture.md) ajoute la migration Room
> 2→3 et la lecture V3, sans écriture de séance mixte, import ou SAF V3.

Statut : implémenté dans `core:export`, sans connexion Room, SAF ou Android V3.
Base exacte #37 : `300389654daf659eae66cd58d2216f78da94bb82`.
PR en brouillon vers `codex/configured-archive-v3-contract`, sans fusion/auto-merge.

## Contrat et versions indépendantes

Le contrat métier de l’[ADR 037](037-contrat-archive-seances-configurables-v3.md)
est conservé. Le codec ne modifie ni scoring, ni provenance, ni politique sportive.
Les [ADR 015](015-sauvegarde-versionnee-reconstruction.md) et
[026](026-contrat-sauvegarde-json-v2.md) continuent de régir les formats historiques.

| Version | Valeur |
|---|---|
| Archive JSON de ce codec | `schemaVersion = 3` |
| Scoring | `scoringContractVersion = 1` |
| SQLite | Room 2, schémas 1/2 et migration 1→2 inchangés |
| Définitions de cible | UUID, définition complète et édition d’origine conservés |

[`ArchiveV3JsonCodec`](../../core/export/src/main/kotlin/com/lmarchery/core/export/ArchiveV3JsonCodec.kt)
possède seulement `encode(DomainArchiveV3): ByteArray` et
`decode(ByteArray): ArchiveV3JsonRead`. Les budgets du constructeur peuvent être
resserrés, jamais agrandis au-delà des plafonds de cette décision.

- `Accepted(snapshot, archive)` fournit le transfert V3 et le domaine complet
  reconstruit par `reconstructArchiveV3()` ; aucun résultat partiel.
- `Rejected(issue)` conserve code, chemin JSON et détail. `issue.kind` distingue
  `JSON`, `DOMAIN` et `ROUND_TRIP`. Une erreur du reconstructeur conserve aussi
  **l’`ArchiveV3Issue` original**, son enum, son chemin DTO et son détail ; son
  chemin JSON est le même préfixé de `$.`, sans remplacement par une erreur générique.
- L’encodage rejeté lève `ArchiveV3JsonException` avec le même diagnostic structuré.
  Il ne retourne aucun octet comme export réussi.

Ce lecteur n’est pas un import ni une autorisation de remplacement. Il n’accepte
pas de magasin connu et ne résout aucun conflit de révision. La future restauration
devra comparer les identités/contenus connus sous une transaction appropriée.

## Représentation exacte et ordre déterministe

La source de vérité reste
[`ArchiveV3Snapshot.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/archive/v3/ArchiveV3Snapshot.kt).
Le mapping est explicite, sans sérialisation réflexive ni déduction depuis les
champs privés des objets métier. Le JSON est UTF-8 sans BOM, indenté de deux
espaces, sans date d’export, tri implicite, normalisation des textes ou fin de
ligne ajoutée. Les listes conservent leur ordre significatif.

Chaque ligne du tableau suivant donne l’ordre **d’émission** des propriétés.
L’ordre de réception d’un objet est libre ; ses clés doivent correspondre
exactement à cette liste, une fois chacune. Toutes les propriétés sont
obligatoires, **même celles dont la valeur peut être `null`**.

| Objet | Propriétés dans l’ordre |
|---|---|
| Racine | `schemaVersion`, `scoringContractVersion`, `profiles`, `sessions`, `bows`, `arrowSets` |
| Séance configurable | `uuid`, `metadata`, `initialConfiguration`, `initialConfigurationOrigin`, `status`, `startedAt`, `endedAt`, `ends`, `createdAt`, `updatedAt` |
| Métadonnées | `date`, `title`, `plannedEndCount`, `bowUuid`, `arrowSetUuid`, `locationName`, `notes` |
| Configuration | `targetTypeUuid`, `regulatoryEdition`, `distanceM`, `arrowCountMode`, `arrowCountPerEnd`, `isIndoor`, `scoringMode` |
| Volée configurée | `sessionUuid`, `end`, `configuration`, `origin` |
| Origine de volée | `kind`, `previousEndUuid` |
| Profil | `definition`, `regulatoryEdition` |
| Définition cible | `uuid`, `name`, `discipline`, `diameterCm`, `maxScore`, `minScore`, `defaultArrowCount`, `defaultDistanceM`, `isBuiltIn`, `xRingEnabled`, `xRingLabel`, `innerTenRadiusNormalized`, `zones` |
| Zone | `score`, `innerRadiusNormalized`, `outerRadiusNormalized`, `colorName`, `colorHex` |
| Date civile | `localDateTime`, `zoneId`, `offset` |
| Volée historique | `uuid`, `index`, `status`, `notes`, `createdAt`, `updatedAt`, `validatedAt`, `impacts` |
| Impact | `uuid`, `index`, `targetTypeUuid`, `point`, `inputMode`, `note`, `createdAt`, `updatedAt`, `decision`, `repositions` |
| Point | `x`, `y` |
| Décision | `kind`, `selected`, `decidedAt`, `boundary`, `reason`, `assertions` |
| Valeur de limite | `score`, `isX` |
| Référence de limite | `type`, `radiusNormalized` |
| Assertions | `distanceFromCenter`, `scoreCalculated`, `scoreFinal`, `geometricIsX`, `isX`, `isMiss`, `isCordDecisionManual` |
| Repositionnement | `impactUuid`, `impactCreatedAt`, `inputMode`, `previousPoint`, `previousDecision`, `previousUpdatedAt`, `newPoint`, `changedAt`, `reason` |
| Arc | `uuid`, `name`, `type`, `drawWeightLbs`, `brand`, `model`, `notes`, `createdAt`, `updatedAt` |
| Jeu de flèches | `uuid`, `name`, `spine`, `length`, `pointWeight`, `nock`, `vanes`, `material`, `notes`, `createdAt`, `updatedAt` |

Les quatre tableaux racines sont explicites et non nuls, même vides. Vide,
matériel seul et profils seuls sont admis par le contrat métier. Les origines
initiales sont `CREATION` ou `LEGACY_DERIVED`. Les origines de volée sont `INITIAL`,
`INHERITED`, `EXPLICIT`, `LEGACY_DERIVED` ; `previousEndUuid` vaut `null` sauf pour
l’héritage, qui exige l’identité de la précédente volée et sa configuration exacte.
Il n’existe ni nouvelle identité ni instant artificiel de choix de configuration.

Les représentations communes profil/date/volée/impact/décision/trace/matériel
réutilisent les adaptateurs V1/V2. Le seul refactoring historique est le passage
de quatre fonctions de mapping matériel de `private` à `internal`, sans changement
de corps. Les séances configurables ont leur propre mapping ; aucune fausse
séance homogène ne sert de conteneur.

## Nombres, textes et temps sans perte

Les entiers sont des littéraux décimaux dans la plage `Int`, sans fraction ni
exposant. Les Float sont lus depuis leurs lexèmes, sans passage par `Double` ;
l’écriture emploie directement la représentation JVM `Float.toString()`.
`-0.0f` conserve ses bits, y compris via un exposant négatif zéro reçu.
NaN, infinis, débordement et sous-débordement non nul sont refusés.

**Politique de précision V3** : la valeur décimale reçue doit être équivalente
(comparaison `BigDecimal`, sans conversion `Double`) à la représentation décimale
`Float.toString()` de la valeur obtenue. `0.1`, `0.1000` et `1e-1` sont admissibles ;
`0.10000000000000001` est rejeté avec `FLOAT_PRECISION` au champ concerné.
Une expansion décimale plus longue qui tomberait sur le même Float par arrondi
n’est donc pas acceptée silencieusement. Ce contrat n’exige pas que `0.1` soit une
fraction binaire exacte ; il exige une valeur déclarée sérialisable sans arrondi
décimal supplémentaire. Cette politique est nouvelle et **limitée à V3** ; le
parseur numérique publié V1/V2 n’est pas modifié. Des exposants illisibles par
la vérification décimale donnent aussi un rejet typé, sans exception brute.

Tous les Float du schéma sont vérifiés : coordonnées, rayons de cible/zone/limite,
distance assertée et puissance. Une puissance présente doit aussi respecter le
modèle `Bow` (finie et strictement positive).

Unicode valide, accents composés ou décomposés, espaces, chaînes facultatives
vides et unités libres restent inchangés. Les surrogates isolés, même échappés,
sont refusés. Aucun remplacement UTF-8, trim ou conversion d’unité.
Les événements émis utilisent `Z`, nanosecondes conservées ; un offset reçu doit
être explicite et valide. Le triplet civil conserve sa zone IANA et son offset
effectif, sans utiliser le fuseau actuel du téléphone ou de la JVM.

## Lecture stricte et budgets de lecture/écriture

`StrictJson` et `JsonFields` sont réutilisés **sans modification** : grammaire
stricte, clés inconnues/absentes/dupliquées (après décodage des échappements),
types incorrects, enums inconnus, UTF-8 invalide, BOM, surrogates isolés,
commentaires, troncature et contenu après le document sont refusés.
L’adaptateur valide chaque clé nullable comme présente avant de lire sa valeur.

| Budget JSON | Plafond inclusif |
|---|---|
| Octets UTF-8 | 16 MiB |
| Profondeur | 32 |
| Valeurs JSON, arbre entier | 200 000 |
| Éléments d’un tableau | 20 000 |
| Propriétés d’un objet | 64 |
| Caractères UTF-16 d’une chaîne ou clé | 16 384 |
| Caractères d’un lexème numérique | 128 |

Le transfert doit aussi respecter `ArchiveV3Limits` : 20 000 éléments par
collection métier, 200 000 éléments cumulés (zones et traces incluses),
16 384 caractères UTF-16 par texte. Ces budgets métier ne remplacent pas les
budgets JSON, qui comptent aussi les valeurs scalaires de chaque objet.

Le contrôle de sortie est obligatoire :

1. Capturer le snapshot complet depuis le domaine immuable.
2. Exécuter la reconstruction V3 et ses budgets métier.
3. Écrire le JSON en UTF-8 strict dans une sortie bornée en octets.
4. Relire ces octets avec le même codec et les mêmes budgets JSON/métier.
5. Exiger l’égalité du snapshot **et** du domaine avec la source avant retour.

Une limite atteinte échoue, sans troncature ou résultat partiel. Un échec de
relecture est propagé ; une divergence donne `ROUND_TRIP`. Les objets émis ont au
plus 13 propriétés et les lexèmes Float JVM moins de 128 caractères ; ces deux
plafonds ne peuvent pas être dépassés par une valeur métier valide. Les autres
budgets, notamment octets et nombre total de valeurs, peuvent limiter une archive
métier pourtant valide : les tests exercent ces cas réels à l’encodage.

## Compatibilité explicite et frontière opérationnelle

`ArchiveJsonCodec` conserve toutes ses API et uniquement V1/V2 dans
`decodeVersioned()`. Un fichier V3 y produit `UNSUPPORTED_VERSION` avant tout
import. Le décodeur V1 direct refuse aussi V3. Le nouveau codec refuse V1/V2,
même si une conversion métier explicite serait possible séparément.

Les encodeurs V1/V2 n’acceptent pas `DomainArchiveV3`. Le nouvel encodeur n’accepte
ni V1/V2, ni `LegacyV1ConfiguredContent` partiel, ni `ConfiguredSession` seul.
Aucun fallback ou encodeur sélectionné par un booléen. La distinction matériel
non fourni V1 / catalogues explicitement vides V2/V3 reste celle de l’ADR 037.
Les références opaques non résolues sont préservées, sans fiche inventée ou `null`.

La disponibilité de ce codec ne fournit ni collecte cohérente V3 depuis Room,
ni transaction de restauration, planification, résolution de conflits, ni SAF V3.
Le parcours Android d’intégration reste V1/V2. Aucun appel applicatif au codec V3.
Ces archives ne sont pas des preuves signées ; une histoire absente de toutes
les sources ne peut pas être découverte par le parseur.

## Tests et suites conservées

Le [golden synthétique V3](../../core/export/src/test/resources/v3-rich-golden.json)
fige l’ordre et les octets : séances mixtes et homogènes historiques, éditions
WA/historiques, 18/70 m, FIXED 3/6/5 et FREE, vide/provisoire/validé, statuts
brouillon/en cours/terminé, toutes origines, X/M, jugements, correction et plusieurs
invalidations motivées, catalogue associé/non associé, Unicode et nanosecondes.

Les tests couvrent chaque forme d’objet et chaque propriété obligatoire, les
doublons et inconnues, mauvais types/enums/identités/propriétaires/héritages,
traces/chronologies/profils contradictoires, absence de domaine partiel, tous
budgets, Float extrêmes/adjacents/signés et un échantillon reproductible,
exposants extrêmes et mutations de fichiers à graine fixe. Les deux golden V1 et
douze empreintes historiques restent inchangés ; une empreinte V2 déterministe
est aussi figée sans changement des adaptateurs publiés. Les 80 tests #37,
coexistences #36 et tests SQLite/migrations précédents sont conservés.

Validation attendue : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
inspection XML, builds/lint app et database, SQLite natif disque, empreintes Room
1/2 et golden historiques, liens locaux, `git diff --check`, puis Quality gate
sur le commit publié. Aucun appareil/ADB/essai physique.

La suite exige des PR distinctes : choix du schéma de stockage réellement intégré,
collecte V3 atomique, planification/restauration avec conflits et rollback, puis
intégration SAF/Android. Aucune de ces étapes ni politique sportive ouverte
(remplissage/clôture, révisions rétroactives, statistiques mixtes) n’est engagée ici.

### Résultat local du 21 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : PASS, trois étapes
`BUILD SUCCESSFUL`. XML inspectés : modèle 135, scoring/reconstruction 407,
export 92 (dont 50 nouveaux : 35 codec et 15 budgets), SQLite natif disque 180,
Android JVM 228 debug + 228 release. **1 270 exécutions**, zéro échec, erreur ou
test ignoré ; les variantes Android partagent des suites, ce ne sont pas autant
de tests uniques. Builds et lint app/database : PASS, zéro erreur lint et cinq
avertissements de dépendances préexistants. Messages KMP/AGP non masqués,
aucune dépendance actualisée.

259 liens documentaires locaux et `git diff --check` : PASS.
Schémas Room 1/2 inchangés (SHA-256 `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`
et `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`).
Golden V1 inchangés ; sortie V2 figée : SHA-256
`ffde0b4cc134bedaf36eb837eac3c0868803d957003e1d5a9ebc63a36931bf1c`.
Le Quality gate GitHub est vérifié séparément sur le commit exact publié.

*21 octobre 2026*
