# Contrat de sauvegarde JSON V2 — LMarchery

> Contrat typé et codec disponibles. **La collecte/restauration Room V2 et le parcours
> Android ne sont pas encore implémentés. L'application utilise toujours JSON V1.**

Le [contrat V1](export-format-v1.md) conserve sa validité et ses octets d'export.
L'[ADR 026](adr/026-contrat-sauvegarde-json-v2.md) définit la séparation des types,
la lecture des deux versions et les préalables transactionnels avant Android.

## Racine V2

| Clé obligatoire | Valeur |
|-----------------|--------|
| `schemaVersion` | Entier `2` |
| `scoringContractVersion` | Entier `1`, indépendant du schéma |
| `profiles` | Tableau de profils complets, représentation V1 inchangée |
| `sessions` | Tableau de séances et descendants, représentation V1 inchangée |
| `bows` | Tableau de fiches d'arc complètes |
| `arrowSets` | Tableau de jeux de flèches complets |

Une liste absente ou `null` est rejetée ; `[]` est une liste explicitement vide.
Une archive uniquement matérielle ou entièrement vide peut être structurellement
valide. Cela n'autorise ni effacement, ni restauration partielle, ni modification
implicite d'une base locale. Les listes conservent leur ordre ; les clés sont
émises dans l'ordre ci-dessus avec indentation de deux espaces.

## Fiches matériel

Toutes les clés suivantes sont obligatoires, même lorsqu'elles admettent `null`.
Aucun ID Room n'est transmis.

| Clé | Bow | ArrowSet | Contraintes |
|-----|-----|----------|-------------|
| `uuid` | String | String | UUID v4 canonique, unique globalement |
| `name` | String | String | Non blanc, texte conservé sans trim |
| `type` | String ou null | — | Texte libre |
| `drawWeightLbs` | Nombre Float ou null | — | Si renseigné : fini et strictement positif ; zéro interdit |
| `brand` | String ou null | — | Texte libre |
| `model` | String ou null | — | Texte libre |
| `spine` | — | String ou null | Aucune déduction d'unité ou de système |
| `length` | — | String ou null | Aucune conversion d'unité |
| `pointWeight` | — | String ou null | Aucune conversion d'unité |
| `nock` | — | String ou null | Texte libre |
| `vanes` | — | String ou null | Texte libre |
| `material` | — | String ou null | Texte libre |
| `notes` | String ou null | String ou null | Vide, espaces, sauts de ligne et Unicode conservés |
| `createdAt` | String | String | Instant ISO 8601 avec contexte explicite ; émission UTC `Z` |
| `updatedAt` | String | String | Même règle, supérieur ou égal à `createdAt` |

L'ordre d'émission d'un arc est `uuid`, `name`, `type`, `drawWeightLbs`, `brand`,
`model`, `notes`, `createdAt`, `updatedAt`. Pour les flèches : `uuid`, `name`,
`spine`, `length`, `pointWeight`, `nock`, `vanes`, `material`, `notes`, `createdAt`,
`updatedAt`. Une chaîne optionnelle vide reste distincte de `null`.

Les nombres reprennent les règles V1 : nombres JSON finis, Float lus directement
sans conversion intermédiaire Double, bits de puissance conservés. Débordement
et sous-débordement non nul sont refusés. Les événements conservent leurs
nanosecondes ; aucune date naïve ou interprétation par le fuseau du téléphone.

## Identités et références

Les profils, séances, volées, impacts, arcs et jeux de flèches partagent un espace
d'identités unique. Tout doublon, même de contenu identique, est rejeté. Les
identités des profils publiés restent réservées et leurs définitions/éditions
sont contrôlées comme en V1.

Les références `Session.bowUuid` et `Session.arrowSetUuid` restent des UUID opaques.
Une fiche peut manquer, y compris dans une archive V2 ; la référence reste intacte,
sans création, association, vérification de type implicite ou remplacement par
`null`. Aucune migration ne fabrique de matériel depuis les références V1.

## API et garanties strictes

Les transferts `ArchiveV2Snapshot`, `BowSnapshot`, `ArrowSetSnapshot` ne sont pas
des valeurs métier validées. `reconstructArchiveV2` reconstruit tout ou rend
`NeedsIntervention(source, issue)`, avec code typé et chemin précis. Les profils,
décisions et traces conservent tous leurs contrôles V1. `DomainArchiveV2` possède
ses listes immuables et ne peut pas être transmis aux API Room V1.

`ArchiveJsonCodec` propose :

- `encode(DomainArchive)` : V1 inchangé, y compris octets historiques.
- `encode(DomainArchiveV2)` : V2 complet, sans rétrogradation implicite.
- `decode(bytes)` : V1 seulement, toujours utilisé par Android ; V2 est rejeté.
- `decodeVersioned(bytes)` : `VersionedArchiveJsonRead.V1`, `.V2` ou `.Rejected`.

`.V1` signifie « matériel non fourni dans cette version », jamais « catalogue
vide à appliquer ». `.V2` contient les deux listes explicites ; même vides, elles
ne demandent aucune suppression. Aucun résultat rejeté ne contient de domaine
partiellement accepté.

Le lecteur partage les règles strictes V1 : clés manquantes, inconnues et dupliquées,
mauvais types, versions inconnues, UTF-8/BOM/surrogates, contenu tronqué et suffixe
sont rejetés. Budgets : 16 MiB, profondeur 32, 200 000 valeurs, 20 000 éléments
par tableau, 64 propriétés par objet, 16 384 caractères par chaîne/clé et
128 caractères par nombre. Ces limites s'appliquent au document entier et à
l'encodage, sans troncature ni acceptation partielle.

## Limite opérationnelle

Room reste en version 2, le scoring en version 1. Les migrations et transactions
existantes ne changent pas. Aucun nouvel import/export SAF ni écran de catalogue
n'est ajouté. La prochaine étape devra apporter collecte cohérente de toute la
base, planification globale et import atomique sans écrasement avant de brancher
le V2 dans Android. Le codec seul ne constitue pas une restauration opérationnelle.
