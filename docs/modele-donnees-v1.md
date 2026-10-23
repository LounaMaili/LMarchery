# Modèle de données — LMarchery V1

> Définition des entités, attributs et relations pour la version 1

**État implémenté** : `TargetType`, `TargetZone`, `Session`, `End`, `ArrowImpact`
et les valeurs de scoring/temps sont des modèles Kotlin purs. `core:database`
persiste et met à jour des séances complètes en Room/SQLite. Le premier parcours
Compose les utilise via ViewModel, contrôleur et repository (ADR 018).
Les anciens IDs/FK numériques des tableaux sont des projections historiques,
absentes du schéma SQLite 3 qui utilise les UUID (section 4). `Bow` et `ArrowSet`
sont des valeurs Kotlin immuables persistées (ADR 025), consultables et modifiables
dans le catalogue Compose (ADR 030) ;
`SessionPreset` reste prévu. Le contrat typé de sauvegarde est
décrit dans [ADR 015](adr/015-sauvegarde-versionnee-reconstruction.md). L’export
JSON Android est opérationnel selon l’[ADR 021](adr/021-export-json-versionne-android.md) ;
l’import contrôlé sans écrasement suit l’[ADR 022](adr/022-import-json-controle-transactionnel.md),
sans changement de format JSON V1. La migration SQLite 1→2 ajoute uniquement le catalogue matériel ;
la migration 2→3 ajoute les configurations dérivées et leur lecture V3 (ADR 039).

---

## 1. Vue d'ensemble

### Diagramme des relations

```
SessionPreset ──────┐
                    │ préremplit
                    ▼
Session ──────────┬─── Bow
  │               ├─── ArrowSet
  │               │
  ├── End (volée) ────── ArrowImpact
  │                       ├── scoreCalculated
  │                       ├── scoreFinal
  │                       └── xNormalized, yNormalized
  │
  └── TargetType ──── TargetZone
                       ├── innerRadiusNormalized
                       └── outerRadiusNormalized
```

### Entités V1

| Entité | Rôle |
|--------|------|
| `Session` | Séance de tir à l'arc |
| `End` | Volée (ensemble de flèches) |
| `ArrowImpact` | Impact d'une flèche sur le blason |
| `TargetType` | Type de cible (définition officielle) |
| `TargetZone` | Zone de score d'un type de cible |
| `Bow` | Arc de l'utilisateur |
| `ArrowSet` | Jeu de flèches de l'utilisateur |
| `SessionPreset` | Configuration prédéfinie de séance |

### Entités futures (V2+)

Ces entités sont mentionnées pour préparer l'architecture, mais ne sont pas implémentées en V1 :

| Entité | Rôle | Version |
|--------|------|---------|
| `WeatherSnapshot` | Conditions météo d'une séance | V2 |
| `TargetCalibration` | Calibration d'un blason réel (photo) | V2/V3 |
| `PhotoReference` | Photo du blason (avant/après) | V2/V3 |
| `DetectionResult` | Résultat de reconnaissance automatique | V3 |
| `BowSettings` | Réglages détaillés d'un arc | V2 |
| `Location` | Localisation d'une séance | V2 |

---

### Champs communs des entités exportables

Le modèle V1 prévoit `TargetType`, `Session`, `End`, `ArrowImpact`, `Bow`, `ArrowSet`
et `SessionPreset`. Le fichier V1 exporte seulement les profils et séances,
avec volées/impacts imbriqués. Le [contrat V2](export-format-v2.md) ajoute les
fiches `Bow` et `ArrowSet` (ADR 026), via collecte/import Room atomiques (ADR 027)
et parcours SAF Android (ADR 029). Les références UUID restent opaques : une
archive ne doit pas fabriquer les fiches manquantes. `SessionPreset` reste futur.

| Champ | Règle |
|-------|-------|
| `id` (projection prévue) | Absent du schéma Room 3 actuel ; jamais une clé de fusion entre appareils. |
| `uuid` | UUID v4 stable, utilisé pour l'export/import, le dédoublonnage et le remapping des relations. |
| `createdAt` | Instant UTC de création des agrégats modifiables ; absent des profils immuables. |
| `updatedAt` | Instant UTC de dernière modification fonctionnelle, servant à comparer les révisions. |

À la création d'une entité modifiable, `createdAt` et `updatedAt` ont la même valeur. Toute modification utilisateur met à jour `updatedAt`.

Les profils `TargetType` n'ont ni `id`, ni `createdAt`, ni `updatedAt` dans le
domaine et le contrat de sauvegarde. Leur UUID désigne une définition complète
immuable ; l'édition éventuelle est une métadonnée séparée du snapshot. Les
versions du schéma et du scoring sont également distinctes. Une date de révision
ne permet jamais de déduire seule la causalité entre appareils.

Pour les agrégats de séance, une modification d'un `ArrowImpact` met aussi à jour `updatedAt` de son `End` et de sa `Session`. Une modification d'un `End` met aussi à jour `updatedAt` de sa `Session`. La comparaison complète reste nécessaire ; un `updatedAt` plus récent ne résout aucun conflit ni ne prouve une filiation de révision.

### Intégrité temporelle des modèles de séance

Les règles suivantes sont vérifiées par `ArrowImpact`, `End` et `Session` :

- `createdAt <= updatedAt` ; les modifications et `copy` conservent `createdAt`.
- Un enfant ne peut pas être créé avant son parent. Son `updatedAt` ne peut pas
  dépasser celui du parent : impact → volée → séance.
- `validatedAt` couvre toutes les modifications des impacts de la volée et ne
  dépasse pas `End.updatedAt`. `startedAt <= endedAt <= Session.updatedAt` lorsque
  ces dates existent ; `endedAt` couvre toutes les modifications des volées.
- `start`, `complete`, `validate`, `withFinalScore` et `reposition` rejettent une date antérieure
  à la dernière modification déjà enregistrée. Une date égale est acceptée.
- `withImpact` et `withEnd` propagent une nouvelle révision avec
  `max(updatedAt du parent existant, date d'intégration)`. La date d'intégration
  doit couvrir le `updatedAt` de l'enfant reçu et ne pas précéder la création du
  parent. Par défaut, elle est le `updatedAt` de l'enfant.
- Un nouvel enfant peut être plus ancien que la dernière modification du parent :
  le parent conserve alors sa date la plus récente. En revanche, remplacer un
  enfant existant par une révision obsolète ou changer son `createdAt` est rejeté,
  y compris pour les impacts imbriqués dans une volée remplacée.

Les constructeurs servent à reconstruire des instantanés cohérents ; ils ne
réparent pas automatiquement leurs dates. `copy` représente une modification
d'une valeur existante et contrôle aussi son historique : aucune régression de
`updatedAt`, changement de `createdAt`, transition antidatée ou révision obsolète
d'un descendant. Les collections de descendants sont copiées et non modifiables,
pour empêcher une mutation externe de contourner ces contrôles.

Les modèles restent des valeurs immuables avec égalité par contenu, décomposition
et paramètres de copie conservés. La copie automatique des anciennes `data class`
est remplacée par une copie contrôlée ; voir
[ADR 008](adr/008-integrite-horodatages-domaine.md).

La propagation est explicite : modifier l'impact, intégrer sa nouvelle valeur
par `End.withImpact`, puis intégrer la volée par `Session.withEnd`. Une ancienne
séance reste inchangée ; construire ou copier un parent avec un enfant plus récent
sans mettre à jour la date du parent provoque une erreur.

Les décisions manuelles d'impact vérifient aussi
`createdAt <= decidedAt <= updatedAt`. Une nouvelle décision doit être au moins
aussi récente que la précédente révision de l'impact, y compris lors d'un
remplacement reconstruit dans les agrégats. Les copies de métadonnées conservent
la date de la décision déjà enregistrée. Voir [ADR 011](adr/011-decisions-score-explicites.md).

Les événements et révisions sont des `Instant`, décisions et traces comprises.
Le format d'export prévu est ISO 8601 UTC avec `Z`, sans perte de nanosecondes.
Les dates civiles restent distinctes : `Session.date: SessionDateTime` conserve
un `LocalDateTime`, un `ZoneId` IANA et l'offset valide choisi. Une heure inexistante
est rejetée ; une heure répétée exige un offset explicite. Cette date de séance
est indépendante de `startedAt` et n'est jamais remplacée par une date seule
ou par le fuseau de consultation. Voir [ADR 014](adr/014-convention-temporelle-utc.md).

Room utilise le couple entier `epochSecond`/`nano`, et le triplet civil
local/fuseau/offset ; le mapper rejette les couples partiels et les nanos invalides.
Voir [ADR 016](adr/016-persistance-locale-room.md). Une ancienne valeur sans contexte temporel reste
non résolue jusqu'à une décision de migration explicite ; elle n'est pas supposée UTC.

`TargetZone` n'est pas une entité exportable racine en V1 : elle est exportée dans son `TargetType` parent et identifiée par ce parent et son score / rayon. Elle ne porte donc pas de `uuid` indépendant en V1.

---

## 2. Définition détaillée des entités

### 2.1 TargetType

Le type de cible définit les règles officielles. Il est générique et extensible.

| Attribut | Type | Description |
|----------|------|-------------|
| `uuid` | String | Identifiant stable pour export/import (UUID v4, stable aussi pour les cibles built-in) |
| `name` | String | Nom affiché (ex. "Classique 10 zones") |
| `discipline` | String | Discipline (ex. "indoor", "outdoor", "field") |
| `diameterCm` | Int | Diamètre officiel en cm (40, 60, 80, 122) |
| `maxScore` | Int | Score maximum par flèche (10 pour classique) |
| `minScore` | Int | Borne inférieure déclarée des scores de zone (0 peut inclure le miss hors zone) |
| `defaultArrowCount` | Int | Nombre de flèches par volée par défaut |
| `defaultDistanceM` | Int | Distance de tir par défaut en mètres |
| `zones` | List\<TargetZone\> | Zones de score (relation 1:N) |
| `isBuiltIn` | Boolean | True si prédéfini (non modifiable par l'utilisateur) |
| `xRingEnabled` | Boolean | Active ou désactive la zone X pour ce type de cible |
| `xRingLabel` | String | Libellé affiché de la zone X (ex. "X", "10+", ou vide si désactivée) |
| `innerTenRadiusNormalized` | Float | Rayon normalisé du X, contenu dans la zone maximale ; peut être conservé ou mis à zéro si désactivé |

**Fixtures historiques conservées** (elles ne sélectionnent pas un règlement indoor) :

- Classique 122 cm (extérieur)
- Classique 80 cm (extérieur / salle)
- Classique 60 cm (salle)
- Classique 40 cm (salle, jeunes)

**Profils indoor datés** : `WorldArcheryIndoorTargetProfiles` définit quatre
identités distinctes pour classique/poulies en 40 cm à 18 m et 60 cm à 25 m,
selon l'édition World Archery du 27 janvier 2026. Le choix est explicite et ne
se déduit pas du diamètre ni de `Session.isIndoor`. Le rayon du 10 vient de la
zone maximale (`0.10` classique, `0.05` poulies), indépendamment du rayon X.
Ces profils ont `xRingEnabled = false`, un libellé X vide et un rayon X nul.
Leur UUID identifie une définition publiée figée ; une évolution réglementaire
reçoit une nouvelle identité, sans migration implicite des anciennes séances.
Les UUID et limites sont consignés dans
[ADR 013](adr/013-profils-indoor-world-archery.md).

`SessionScoreSummary` ajoute `nineCount` aux compteurs existants : il compte
les valeurs finales égales à 9 dans les volées validées. Les compteurs de maximum,
X et M conservent leur sens générique ; sur ces profils le maximum est 10 et X
reste absent, y compris après jugement ou repositionnement.

**Invariants appliqués au domaine** : la liste des zones est une copie non
modifiable, validée au constructeur comme par `copy`. La cible couvre le disque
complet `[0, 1]`, sans trou ni chevauchement, avec des scores strictement
décroissants du centre vers l'extérieur, compris dans `minScore..maxScore`.
La zone centrale vaut `maxScore`. Le rayon X est fini, non négatif et contenu
dans cette zone ; il est strictement positif lorsqu'il est activé.

Les paramètres publics, la copie, l'égalité par contenu et la décomposition de
`TargetType` sont conservés, avec une classe immuable selon le même principe que
les agrégats de séance. Les rayons réduits et les multispots ne sont pas pris en
charge implicitement. Voir les [règles détaillées](regles-score.md#invariants-des-définitions-de-cible)
et [ADR 009](adr/009-integrite-entrees-scoring.md).

---

### 2.2 TargetZone

Zone de score d'un type de cible.

Le score est strictement positif ; les rayons sont finis et vérifient
`0 <= innerRadiusNormalized < outerRadiusNormalized <= 1`, y compris par copie.
Les limites partagées appartiennent à la zone intérieure au score supérieur.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `targetTypeId` | Long (FK) | Référence vers TargetType |
| `score` | Int | Valeur de score de cette zone |
| `innerRadiusNormalized` | Float | Rayon intérieur (normalisé 0.0–1.0) |
| `outerRadiusNormalized` | Float | Rayon extérieur (normalisé 0.0–1.0) |
| `colorName` | String | Nom de la couleur (blanc, noir, bleu, rouge, jaune) |
| `colorHex` | String? | Code couleur hex (pour affichage) |

**Exemple pour cible classique 80 cm** :

| Score | innerRadius | outerRadius | Couleur |
|-------|-------------|-------------|---------|
| 10 | 0.000 | 0.100 | Jaune |
| 9 | 0.100 | 0.200 | Jaune |
| 8 | 0.200 | 0.300 | Rouge |
| 7 | 0.300 | 0.400 | Rouge |
| 6 | 0.400 | 0.500 | Bleu |
| 5 | 0.500 | 0.600 | Bleu |
| 4 | 0.600 | 0.700 | Noir |
| 3 | 0.700 | 0.800 | Noir |
| 2 | 0.800 | 0.900 | Blanc |
| 1 | 0.900 | 1.000 | Blanc |

---

### 2.3 Session

Séance de tir à l'arc.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `date` | SessionDateTime | Date et heure civiles, fuseau IANA d’origine et offset validé |
| `status` | Enum | Statut de la séance (DRAFT / IN_PROGRESS / COMPLETED) |
| `startedAt` | Instant? | Heure de début effectif (renseigné quand DRAFT → IN_PROGRESS) |
| `endedAt` | Instant? | Heure de fin (renseigné quand IN_PROGRESS → COMPLETED) |
| `plannedEndCount` | Int? | Nombre de volées prévues (optionnel, ex. 10 pour competition) |
| `title` | String | Titre / nom de la séance |
| `targetTypeId` | Long (FK) | Type de cible utilisé |
| `distanceM` | Int | Distance de tir en mètres |
| `arrowCountMode` | Enum | Mode de nombre de flèches par volée (FIXED / FREE) |
| `arrowCountPerEnd` | Int? | Nombre de flèches par volée si `arrowCountMode = FIXED` ; `null` si `FREE` |
| `isIndoor` | Boolean | True si tir en salle |
| `scoringMode` | Enum | Mode de comptage (TRAINING / COMPETITION / MANUAL_CORDON) |
| `bowId` | Long? (FK) | Arc utilisé (optionnel) |
| `arrowSetId` | Long? (FK) | Jeu de flèches utilisé (optionnel) |
| `locationName` | String? | Nom du lieu (optionnel, V1 manuel) |
| `notes` | String? | Notes libres |
| `totalScore` | Int | Score total calculé |
| `maxPossibleScore` | Int | Score maximum possible |
| `createdAt` | Instant | Date de création |
| `updatedAt` | Instant | Date de dernière modification |
| `ends` | List\<End\> | Volées de la séance (relation 1:N) |

**Règle de `maxPossibleScore`** :

- Si `arrowCountMode = FIXED` et `plannedEndCount` est renseigné, `maxPossibleScore = plannedEndCount × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = FIXED` sans `plannedEndCount`, `maxPossibleScore = nombre de volées enregistrées × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = FREE`, `maxPossibleScore = nombre total d'impacts enregistrés × targetType.maxScore`. Le mode libre ne suppose jamais 3 ou 6 flèches par volée.

---

Les références réelles `bowUuid` et `arrowSetUuid` restent opaques et sans FK vers
le catalogue. `withEquipmentReferences(..., changedAt)` modifie uniquement ces
métadonnées et la révision, même après clôture, sans changement d’enfant, score
ou date de fin. Les fiches manquantes ne sont jamais remplacées par `null`.
Le parcours Android exige une sélection et confirmation explicites (ADR 031).

### 2.4 End (Volée)

Une volée = un ensemble de flèches tirées avant d'aller marquer.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `sessionId` | Long (FK) | Référence vers Session |
| `index` | Int | Numéro de la volée dans la séance (1, 2, 3…) |
| `totalScore` | Int | Score total de la volée |
| `notes` | String? | Notes libres |
| `status` | Enum | Statut de la volée (EDITING / VALIDATED) |
| `createdAt` | Instant | Date de création de la volée |
| `updatedAt` | Instant | Date de dernière modification de la volée ou de ses impacts |
| `validatedAt` | Instant? | Date de validation (renseigné quand EDITING → VALIDATED) |
| `impacts` | List\<ArrowImpact\> | Impacts de la volée (relation 1:N) |

**Contrainte** : `index` est unique par session.

---

### Fondation autonome : EndConfiguration

`core:model.session.EndConfiguration` conserve une configuration de prochaine
volée (profil complet/édition, distance, FIXED/FREE et nombre, indoor, mode de
score). Héritage, remplacement explicite, raccourcis 3/6/libre, diagnostics et
comparaison sont des opérations pures. Cette valeur n’est **pas une
propriété de Session ou End** ; les captures de `ConfiguredSession` sont stockées
en Room 3 et sauvegardées en JSON V3 (ADR 037–042). Le contrat homogène existant
reste en vigueur ; aucune ancienne séance n’est convertie implicitement.
Voir [ADR 033](adr/033-fondation-configurations-par-volee.md) pour les invariants,
les frontières de conformité réglementaire et les étapes futures.

### Agrégat autonome : ConfiguredSession

`core.scoring.configured.ConfiguredSession` possède une configuration initiale et
chaque `ConfiguredEnd` avec son propriétaire, son `End` existant, sa configuration
effective et son origine typée. Il n’enveloppe pas `Session` et ne modifie aucune
entité historique. Les transitions sont contrôlées, les décisions et traces
conservées, et le calcul délègue au moteur mixte de l’ADR 034.
La conversion explicite exige une `DomainArchive` validée fournissant le profil
historique complet et son édition ; une `Session` seule ne suffit pas.
Cet agrégat n’est pas exportable en JSON V1/V2. Sa consultation et son édition contrôlée par Compose suivent les ADR 042 et
[043](adr/043-editeur-android-seances-configurables.md). Room V3 reconstruit les
séances historiques sous cette forme mais leur représentation physique reste
distincte : seul `CONFIGURED` utilise le nouvel éditeur. Les captures historiques
ne sont pas converties implicitement.
Voir [ADR 035](adr/035-agregat-seances-configurations-mixtes.md) pour le contrat,
les limites de provenance et l’intégration future avec la branche Android #32.
Le contrat métier autonome `DomainArchiveV3` conserve ces agrégats, leurs captures
et le catalogue complet via un snapshot et une reconstruction stricte ; voir
[ADR 037](adr/037-contrat-archive-seances-configurables-v3.md). Le codec autonome
`ArchiveV3JsonCodec` est décrit dans l’[ADR 038](adr/038-codec-json-v3-strict.md),
avec lecture cohérente Room V3 selon l’[ADR 039](adr/039-room-v3-migration-lecture.md),
avec écritures locales selon l’[ADR 040](adr/040-ecritures-room-v3-configurables.md),
avec prévisualisation/restauration transactionnelle complète selon
l’[ADR 041](adr/041-restauration-archives-v3-atomique.md), avec parcours SAF Android V3
et consultation seule selon l’[ADR 042](adr/042-saf-v3-consultation-configurable.md).
La conversion V1 reste
un contenu partiel avec matériel non fourni, distinct d’une V3 complète.

---

### 2.5 ArrowImpact

Impact individuel d'une flèche. C'est l'entité centrale du scoring.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `endId` | Long (FK) | Référence vers End |
| `index` | Int | Ordre de la flèche dans la volée (1, 2, 3…) |
| `xNormalized` | Float | Coordonnée X finie, centre = 0 ; peut dépasser ±1 pour un miss |
| `yNormalized` | Float | Coordonnée Y finie, centre = 0 ; peut dépasser ±1 pour un miss |
| `distanceFromCenter` | Float | Distance finie et non négative ; calcul intermédiaire en Double, erreur explicite si le rayon dépasse Float.MAX_VALUE |
| `scoreCalculated` | Int | Score courant calculé depuis x/y (recalculé si déplacement, **pas immuable**) |
| `scoreFinal` | Int | Valeur finale acceptée géométriquement ou par une décision manuelle validée |
| `isX` | Boolean | Distinction X de la valeur finale, exigeant X activé et score maximal |
| `isMiss` | Boolean | Indicateur final dérivé de `scoreFinal == 0` |
| `inputMode` | Enum | Mode de saisie : MANUAL / AUTOMATIC / CORRECTED |
| `isCordDecisionManual` | Boolean | Dérivé du type de provenance `BOUNDARY_JUDGMENT` |
| `finalScoreDecision` | FinalScoreDecision | Résultat validé pour le profil et le point, avec géométrie, valeur finale et provenance |
| `repositions` | List\<ImpactReposition\> | Traces immuables des corrections de position, avec anciennes décisions invalidées |
| `note` | String? | Note libre sur cet impact |
| `createdAt` | Instant | Date de création de l'impact |
| `updatedAt` | Instant | Date de dernière modification de l'impact |

**En V1** : la position est saisie avec `InputMode.MANUAL`. La provenance de la
décision est distincte : `GEOMETRY`, `BOUNDARY_JUDGMENT` ou
`EXCEPTIONAL_CORRECTION`. Une confirmation du même score reste manuelle. Les
variantes manuelles conservent leur date, la référence de limite ou le motif
exceptionnel non blanc. Le maximum ne signifie pas automatiquement X et M exclut X.

Le modèle en mémoire exige un `TargetType` explicite. Les scores, la distance
et les indicateurs sont dérivés du résultat validé. Les anciennes assertions
scalaires du constructeur contextualisé doivent coïncider avec ce résultat ;
elles ne servent pas à corriger un score. `copy` conserve le résultat pour les
métadonnées et rejette les changements scalaires de scoring. Une modification
manuelle passe par `withFinalScore(targetType, request, updatedAt)`. Les anciennes
entrées sans contexte rejettent l'opération. La décomposition et l'égalité par
contenu sont conservées. Les impacts d'une volée partagent le même profil ;
la séance exige le profil exact de ses impacts.

Toute correction de position utilise `reposition(newPoint, changedAt, reason)`.
Cette opération conserve UUID, création, profil et origine `InputMode` et remet
la décision finale à la géométrie recalculée, X/M compris. `copy` ne déplace plus
aucun impact et ne change pas son origine. Un motif non blanc est obligatoire
si la décision courante est manuelle ; un point inchangé est rejeté.

Chaque `ImpactReposition` conserve le contexte d'identité, l'ancien résultat
validé `previousDecision` (donc point, score et provenance), `previousUpdatedAt`,
`newPoint`, `changedAt` et `reason`. `invalidatedManualDecision` distingue les
jugements/corrections effectivement invalidés des anciennes valeurs géométriques.
Les traces s'ajoutent sans écrasement ; elles ne journalisent pas toutes les
décisions de score. `withFinalScore` et les copies de métadonnées les conservent.

Les agrégats vérifient les révisions par UUID, y compris à travers des volées
reconstruites différentes ; les UUID d'impacts sont uniques dans une séance.
Une trace existante ne peut être réécrite, tronquée ou supprimée en retirant son
impact par copie. Une volée validée garde ses impacts et sa validation ; une
séance clôturée garde ses volées et sa clôture. Aucun parcours de réouverture
ou suppression des traces n'est ajouté. Voir
[ADR 012](adr/012-repositionnement-explicite-impacts.md).

La reconstruction typée résout d'abord le profil complet et sa définition
canonique si connue, puis rejoue les décisions et repositionnements et vérifie
les assertions stockées. Les conflits de révision restent non résolus ; aucune
provenance manquante n'est inventée. Voir l'ADR 015. La convention UTC est définie
par l'ADR 014. Aucun adaptateur de stockage n'est ajouté ici.
Voir [ADR 011](adr/011-decisions-score-explicites.md).

**Clarification sur `scoreCalculated`** :

- `scoreCalculated` = score courant calculé depuis `xNormalized`/`yNormalized`. Contrairement à une version précédente de ce document, **il n'est pas immuable** : il est recalculé à chaque déplacement de l'impact sur le blason.
- `scoreFinal` = valeur acceptée géométriquement ou par un jugement/correction explicite validé.
- La différence entre les deux mesure la qualité du calcul automatique et, en V3+, de la reconnaissance.

> **V3+ — Champs réservés pour la reconnaissance automatique** (non implémentés en V1) :
>
> | Attribut | Type | Description |
> |----------|------|-------------|
> | `detectionScoreSuggested` | Int? | Score proposé par le modèle de détection |
> | `detectionConfidence` | Float? | Confiance du modèle (0.0–1.0) |
> | `wasUserCorrected` | Boolean? | True si l'utilisateur a corrigé la proposition automatique |

---

### 2.6 Bow

Arc de l'utilisateur. Modèle implémenté dans `core:model.equipment` et table `bows`,
avec fiche Compose de création/édition (ADR 030). Puissance facultative finie et strictement
positive ; type, marque, modèle et notes libres conservés tels quels.

| Attribut | Type | Description |
|----------|------|-------------|
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `name` | String | Nom non blanc, conservé tel quel |
| `type` | String? | Type d'arc (recurve, compound, barebow, longbow, trad) |
| `drawWeightLbs` | Float? | Puissance en livres |
| `brand` | String? | Marque |
| `model` | String? | Modèle |
| `notes` | String? | Notes libres |
| `createdAt` | Instant | Date de création |
| `updatedAt` | Instant | Date de dernière modification |

> Les réglages détaillés (viseur, berger button, band, palette, stabilisation) feront l'objet d'une entité `BowSettings` en V2. En V1, on se contente de notes libres.

---

### 2.7 ArrowSet

Jeu de flèches de l'utilisateur. Modèle implémenté dans `core:model.equipment` et
table `arrow_sets`, avec fiche Compose (ADR 030). Les champs facultatifs restent textuels : aucune
unité de longueur, de spine ou de poids n’est déduite ni convertie.

Pour les deux fiches : UUID v4 canonique et création immuables ; `createdAt <= updatedAt`
et copies/révisions non antidatées. L’égalité couvre tous les champs.
Voir [ADR 025](adr/025-fondation-catalogue-materiel.md).

| Attribut | Type | Description |
|----------|------|-------------|
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `name` | String | Nom non blanc, conservé tel quel |
| `spine` | String? | Spine des flèches |
| `length` | String? | Longueur |
| `pointWeight` | String? | Poids de la pointe |
| `nock` | String? | Type d'encoche |
| `vanes` | String? | Type de plumes/empennage |
| `material` | String? | Matériau (carbone, aluminium, carbone/aluminium, bois) |
| `notes` | String? | Notes libres |
| `createdAt` | Instant | Date de création |
| `updatedAt` | Instant | Date de dernière modification |

---

### 2.8 SessionPreset

Configuration prédéfinie pour créer rapidement une séance.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `name` | String | Nom du preset (ex. "Salle 18m — 3 flèches") |
| `targetTypeId` | Long (FK) | Type de cible par défaut |
| `distanceM` | Int | Distance par défaut |
| `arrowCountMode` | Enum | Mode de nombre de flèches par volée (FIXED / FREE) |
| `arrowCountPerEnd` | Int? | Nombre de flèches par volée si `FIXED` ; `null` si `FREE` |
| `isIndoor` | Boolean | Intérieur par défaut |
| `scoringMode` | Enum | Mode de comptage par défaut |
| `bowId` | Long? (FK) | Arc par défaut |
| `arrowSetId` | Long? (FK) | Flèches par défaut |
| `createdAt` | Instant | Date de création |
| `updatedAt` | Instant | Date de dernière modification |

**Presets suggérés (créés par défaut)** :

| Preset | Cible | Distance | Flèches | Lieu |
|--------|-------|----------|---------|------|
| Salle 18m — 3 flèches | Classique 40cm | 18 | 3 | Indoor |
| Salle 18m — 6 flèches | Classique 40cm | 18 | 6 | Indoor |
| Extérieur 30m | Classique 80cm | 30 | 6 | Outdoor |
| Extérieur 50m | Classique 80cm | 50 | 6 | Outdoor |
| Extérieur 70m | Classique 122cm | 70 | 6 | Outdoor |

L'utilisateur peut créer, modifier et supprimer ses propres presets.

---

## 3. Énumérations

### ArrowCountMode

```kotlin
enum class ArrowCountMode {
    FIXED,           // Nombre de flèches par volée connu (ex. 3 ou 6)
    FREE             // Nombre libre, le score max dépend des impacts réellement saisis
}
```

### ScoringMode

```kotlin
enum class ScoringMode {
    TRAINING,        // Sans demande explicite : valeur géométrique
    COMPETITION,     // Sans demande explicite : valeur géométrique
    MANUAL_CORDON    // Modification par demande typée, sans confirmation imposée pour chaque suggestion
}
```

### InputMode

```kotlin
enum class InputMode {
    MANUAL,          // Saisie manuelle (V1 uniquement)
    AUTOMATIC,       // Proposition automatique (V3+)
    CORRECTED        // Correction d'une proposition auto (V3+)
}
```

### SessionStatus

```kotlin
enum class SessionStatus {
    DRAFT,           // Séance créée mais pas encore démarrée
    IN_PROGRESS,     // Séance en cours de tir
    COMPLETED        // Séance terminée
}
```

### EndStatus

```kotlin
enum class EndStatus {
    EDITING,         // Volée en cours de saisie
    VALIDATED        // Volée validée par l'utilisateur
}
```

---

## 4. Schéma Room / SQLite 3 effectivement implémenté

Les huit tables historiques, conservées sans modification depuis V1, sont
`entity_identities`, `target_profiles`, `target_zones`,
`sessions`, `ends`, `impacts`, `score_decisions`, `impact_repositions`.
Profils, séances, volées et impacts ont leur UUID comme clé primaire et figurent
dans le registre global des identités. Zones et traces ont des clés composites
parent/position ; les décisions ont une clé impact/révision.

Toutes les FK utilisent `RESTRICT` ; aucune suppression fonctionnelle n'est
implémentée. Les liens principaux sont `profileUuid`, `sessionUuid`, `endUuid`,
`impactUuid`. Les index métier et positions sont uniques dans chaque parent.
La position explicite conserve l'ordre des listes, avec un nombre d'enfants
attendu contrôlé à la lecture. Les décisions antérieures sont reliées à leurs
repositionnements et la décision courante occupe la révision -1.

`bows` et `arrow_sets` ajoutent les fiches avec UUID primaire et FK `RESTRICT`
vers le registre, types `BOW` et `ARROW_SET`. La migration explicite 1→2,
enregistrée dans les fabriques Android/JVM, ajoute seulement ces deux tables.
Le schéma 1 reste figé et les huit tables antérieures gardent toutes leurs données.
Aucun preset ni FK des séances vers le matériel : `bowUuid` et
`arrowSetUuid` restent opaques, y compris lorsqu’une fiche correspondante existe.
`RoomSessionStore.equipment` fournit création, lecture, découverte et
`update(expected, proposed)` avec comparaison complète et transaction atomique ;
aucune suppression, fusion ni réparation implicite. Les collisions d’identité
sont refusées entre tous les types, même si une ligne physique a perdu son
registre. Voir [ADR 025](adr/025-fondation-catalogue-materiel.md). Les anciennes
projections numériques des tableaux ci-dessus ne décrivent pas ce schéma réel et ne doivent pas servir de migration.
Les versions Room, archive et scoring sont séparées ; le profil garde son UUID
et son édition distincte. L'export de schéma versionné et les garanties
transactionnelles sont détaillés dans [ADR 016](adr/016-persistance-locale-room.md).

`update(expected: DomainArchive, proposed: DomainArchive)` compare séance et
profil complets dans une transaction écrivain. Le validateur pur
`validateSessionUpdate` contrôle la filiation locale : UUID, propriété des enfants,
création, chronologie, statuts, décisions et préfixes de traces. Aucun changement
de profil ni suppression n’est permis. Les mises à jour SQL sont suivies d’une
relecture avec égalité complète ; conflits et incohérences n’écrasent aucune donnée.
Voir [ADR 017](adr/017-mise-a-jour-seances-room.md).
La politique conservatrice de `reconstructArchive(snapshot, known)` reste distincte
de cette autorisation de révision locale.

La migration additive 2→3 ajoute `session_configurations` (clé `sessionUuid`) et
`end_configurations` (clé `endUuid`), sans nouvelle identité ni date de configuration.
Les dix tables précédentes et les schémas exportés 1/2 restent intacts.
Chaque capture conserve profil/édition, distance, nombre/mode de flèches, indoor,
mode de score et origine. La volée nomme son propriétaire et son éventuelle précédente.
Les lignes migrées sont marquées `LEGACY_HOMOGENEOUS` / `LEGACY_DERIVED`.
Leur projection suit le contrat homogène historique ; les colonnes de séance ne
sont jamais utilisées comme configuration effective d’une future volée mixte.
Les lignes `CONFIGURED` sont lues et révisées seulement avec leurs captures complètes.
Les API V1/V2 les refusent, y compris à l’import, sans omission ni homogénéisation.
`readArchiveV3()` lit tous profils, séances, captures, descendants et matériel dans
une transaction lecteur unique, puis appelle `reconstructArchiveV3()` ; aucune
écriture ni archive partielle. Voir [ADR 039](adr/039-room-v3-migration-lecture.md).
`createConfiguredSession` et `updateConfiguredSession(expected, proposed)` ajoutent
les écritures locales contrôlées : création `CREATION`, captures immuables, état
expected complet et validation de transition, transaction écrivain unique puis
égalité V3 et contrôle des lignes hors delta. Les acquittements incertains conservent
l’intention exacte ; aucune relance automatique. Voir
[ADR 040](adr/040-ecritures-room-v3-configurables.md). Le contrôleur Android dédié
les utilise selon l’[ADR 043](adr/043-editeur-android-seances-configurables.md). La restauration historique et mixte passe séparément par
`previewImportV3` / `importArchiveV3` (ADR 041) : aperçu de l’état complet, ajout
atomique sans remplacement, contrôle des anciennes lignes et acquittement explicite.
Les nouvelles racines V3 sont `CONFIGURED`, provenance historique conservée ;
les racines locales identiques gardent leur représentation.

`discover()` fournit les UUID triés des racines et des identités séance, y compris
orphelines. Chaque entrée est ensuite relue par `read` : la découverte ne prouve
pas sa validité. Les erreurs restent visibles dans la sélection Android. La
découverte des séances reste inchangée ; voir [ADR 018](adr/018-integration-android-room.md).

---

## 5. Export / Import

### Contrat typé actuellement pris en charge

Le [contrat de sauvegarde](export-format-v1.md) fait foi : `schemaVersion = 1`
et `scoringContractVersion = 1`, profils complets avec édition distincte,
séances/volées/impacts imbriqués, décisions explicites et traces rejouées.
Les UUID remplacent toute référence locale ; les profils immuables ne reçoivent
aucun horodatage artificiel. Les totaux et statistiques sont dérivés.

`reconstructArchive(snapshot, known?)` fournit un résultat accepté ou une donnée
nécessitant intervention avec son problème typé. La source contradictoire est
conservée ; aucune séance partielle n'est acceptée. Pour une révision existante,
le référentiel connu doit être fourni : toute divergence reste un conflit,
indépendamment de l'ordre des `updatedAt`. Les préfixes de traces connus ne
peuvent être supprimés ni réécrits. Sans référentiel, une restauration indépendante
ne peut pas découvrir un historique omis de toutes les données reçues.

Le JSON opérationnel conserve séances/profils, décisions et traces. Les fiches
matériel persistées ne sont pas incluses dans le V1 opérationnel.
Le [contrat V2](export-format-v2.md) les inclut dans une API typée distincte,
avec collecte/import Room atomiques ([ADR 027](adr/027-archives-room-v2-atomiques.md)).
Les écrans de catalogue et le parcours SAF V2 existent dans la branche Android.
Room V3 permet lecture, écritures locales configurables (ADR 040) et restauration
V3 avec aperçu (ADR 041). SAF V3 et la consultation configurable suivent
l’[ADR 042](adr/042-saf-v3-consultation-configurable.md). La création et l’édition
configurables sont raccordées selon l’[ADR 043](adr/043-editeur-android-seances-configurables.md),
avec état distinct, candidats exacts et réconciliation explicite sans écrasement.
Les presets et la résolution interactive des conflits restent à développer.
L’import V1 conserve son périmètre historique : séances absentes et profils
nécessaires (ADR 022). L’import V2 traite séparément séances, profils, arcs et jeux
de flèches (ADR 027). Dans les deux cas, les identiques ne sont pas réécrits et
toute divergence bloque l’ensemble sous une transaction unique. Aucun ancien
exemple documentaire incomplet n’est migré en inventant ses valeurs manquantes.
Voir [ADR 015](adr/015-sauvegarde-versionnee-reconstruction.md).

---

*Document version 1.10 — 15 octobre 2026*
