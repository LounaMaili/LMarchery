# Modèle de données — LMarchery V1

> Définition des entités, attributs et relations pour la version 1

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

En V1, les entités exportables racines sont `TargetType`, `Session`, `End`, `ArrowImpact`, `Bow`, `ArrowSet` et `SessionPreset`.

| Champ | Règle |
|-------|-------|
| `id` | Clé primaire locale Room. Elle n'est jamais utilisée comme clé de fusion entre appareils. |
| `uuid` | UUID v4 stable, utilisé pour l'export/import, le dédoublonnage et le remapping des relations. |
| `createdAt` | Date de création locale ou de définition initiale pour les données built-in. |
| `updatedAt` | Date de dernière modification fonctionnelle, utilisée pour résoudre les conflits d'import. |

À la création d'une entité modifiable, `createdAt` et `updatedAt` ont la même valeur. Toute modification utilisateur met à jour `updatedAt`.

Pour les agrégats de séance, une modification d'un `ArrowImpact` met aussi à jour `updatedAt` de son `End` et de sa `Session`. Une modification d'un `End` met aussi à jour `updatedAt` de sa `Session`. Cela permet de comparer une séance complète pendant l'import même si les volées et impacts sont exportés en structure imbriquée.

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

`LocalDateTime` est conservé provisoirement. La convention UTC/fuseau et une
éventuelle migration vers `Instant` seront décidées avant la persistance dans une
tâche distincte. Aucun ajustement automatique de fuseau n'est effectué ici.

`TargetZone` n'est pas une entité exportable racine en V1 : elle est exportée dans son `TargetType` parent et identifiée par ce parent et son score / rayon. Elle ne porte donc pas de `uuid` indépendant en V1.

---

## 2. Définition détaillée des entités

### 2.1 TargetType

Le type de cible définit les règles officielles. Il est générique et extensible.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
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
| `createdAt` | LocalDateTime | Date de création ou de définition initiale |
| `updatedAt` | LocalDateTime | Date de dernière modification ; les built-in ne sont pas écrasés par l'import |

**Instances V1** :

- Classique 122 cm (extérieur)
- Classique 80 cm (extérieur / salle)
- Classique 60 cm (salle)
- Classique 40 cm (salle, jeunes)

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
| `date` | LocalDateTime | Date et heure de la séance |
| `status` | Enum | Statut de la séance (DRAFT / IN_PROGRESS / COMPLETED) |
| `startedAt` | LocalDateTime? | Heure de début effectif (renseigné quand DRAFT → IN_PROGRESS) |
| `endedAt` | LocalDateTime? | Heure de fin (renseigné quand IN_PROGRESS → COMPLETED) |
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
| `createdAt` | LocalDateTime | Date de création |
| `updatedAt` | LocalDateTime | Date de dernière modification |
| `ends` | List\<End\> | Volées de la séance (relation 1:N) |

**Règle de `maxPossibleScore`** :

- Si `arrowCountMode = FIXED` et `plannedEndCount` est renseigné, `maxPossibleScore = plannedEndCount × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = FIXED` sans `plannedEndCount`, `maxPossibleScore = nombre de volées enregistrées × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = FREE`, `maxPossibleScore = nombre total d'impacts enregistrés × targetType.maxScore`. Le mode libre ne suppose jamais 3 ou 6 flèches par volée.

---

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
| `createdAt` | LocalDateTime | Date de création de la volée |
| `updatedAt` | LocalDateTime | Date de dernière modification de la volée ou de ses impacts |
| `validatedAt` | LocalDateTime? | Date de validation (renseigné quand EDITING → VALIDATED) |
| `impacts` | List\<ArrowImpact\> | Impacts de la volée (relation 1:N) |

**Contrainte** : `index` est unique par session.

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
| `createdAt` | LocalDateTime | Date de création de l'impact |
| `updatedAt` | LocalDateTime | Date de dernière modification de l'impact |

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

La future reconstruction Room/export doit d'abord retrouver le profil, résoudre
la demande structurée et vérifier les assertions stockées. La version du profil,
la migration d'anciens instantanés sans provenance et la convention UTC/fuseau
restent à décider avant Room. Aucun adaptateur de stockage n'est ajouté ici.
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

Arc de l'utilisateur.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `name` | String | Nom donné par l'utilisateur |
| `type` | String? | Type d'arc (recurve, compound, barebow, longbow, trad) |
| `drawWeightLbs` | Float? | Puissance en livres |
| `brand` | String? | Marque |
| `model` | String? | Modèle |
| `notes` | String? | Notes libres |
| `createdAt` | LocalDateTime | Date de création |
| `updatedAt` | LocalDateTime | Date de dernière modification |

> Les réglages détaillés (viseur, berger button, band, palette, stabilisation) feront l'objet d'une entité `BowSettings` en V2. En V1, on se contente de notes libres.

---

### 2.7 ArrowSet

Jeu de flèches de l'utilisateur.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `uuid` | String | Identifiant stable pour export/import (UUID v4) |
| `name` | String | Nom donné par l'utilisateur |
| `spine` | String? | Spine des flèches |
| `length` | String? | Longueur |
| `pointWeight` | String? | Poids de la pointe |
| `nock` | String? | Type d'encoche |
| `vanes` | String? | Type de plumes/empennage |
| `material` | String? | Matériau (carbone, aluminium, carbone/aluminium, bois) |
| `notes` | String? | Notes libres |
| `createdAt` | LocalDateTime | Date de création |
| `updatedAt` | LocalDateTime | Date de dernière modification |

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
| `createdAt` | LocalDateTime | Date de création |
| `updatedAt` | LocalDateTime | Date de dernière modification |

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

## 4. Relations Room (DAO)

### Clés étrangères

| Entité | FK vers | Attribut | On delete |
|--------|---------|----------|-----------|
| End | Session | `sessionId` | CASCADE |
| ArrowImpact | End | `endId` | CASCADE |
| Session | TargetType | `targetTypeId` | RESTRICT |
| Session | Bow | `bowId` | SET NULL |
| Session | ArrowSet | `arrowSetId` | SET NULL |
| TargetZone | TargetType | `targetTypeId` | CASCADE |
| SessionPreset | TargetType | `targetTypeId` | RESTRICT |
| SessionPreset | Bow | `bowId` | SET NULL |
| SessionPreset | ArrowSet | `arrowSetId` | SET NULL |

### Index recommandés

- `End`: index sur `(sessionId, index)` — unique
- `ArrowImpact`: index sur `(endId, index)` — unique
- `Session`: index sur `date` DESC — pour l'historique
- `TargetZone`: index sur `(targetTypeId, score)` — pour le calcul

---

## 5. Export / Import

### Format V1

Export en JSON contenant l'intégralité des données utilisateur. Le schéma complet fait foi dans `docs/export-format-v1.md`.

Les relations exportées utilisent les `uuid`, pas les `id` Room :

```json
{
  "version": 1,
  "exportDate": "2026-04-29T10:30:00",
  "targetTypes": [
    {
      "uuid": "a1b2c3d4-...",
      "name": "Classique 80 cm",
      "xRingEnabled": true,
      "xRingLabel": "X",
      "innerTenRadiusNormalized": 0.05,
      "createdAt": "2026-04-29T10:30:00+02:00",
      "updatedAt": "2026-04-29T10:30:00+02:00"
    }
  ],
  "bows": [...],
  "arrowSets": [...],
  "sessionPresets": [...],
  "sessions": [
    {
      "uuid": "q7r8s9t0-...",
      "date": "2026-04-28T18:00:00",
      "title": "Entraînement salle",
      "targetTypeUuid": "a1b2c3d4-...",
      "arrowCountMode": "FIXED",
      "arrowCountPerEnd": 3,
      "updatedAt": "2026-04-28T19:15:00+02:00",
      "ends": [
        {
          "uuid": "u1v2w3x4-...",
          "index": 1,
          "impacts": [
            {
              "uuid": "y5z6a7b8-...",
              "index": 1,
              "xNormalized": 0.05,
              "yNormalized": -0.02,
              "scoreFinal": 10,
              "scoreDecision": {
                "kind": "GEOMETRY",
                "decidedAt": null,
                "boundary": null,
                "reason": null
              },
              "inputMode": "MANUAL"
            }
          ]
        }
      ]
    }
  ]
}
```

### Considérations

- Les `TargetType` built-in sont inclus dans l'export
- Les `id` locaux peuvent être inclus pour compatibilité locale ou diagnostic, mais ne doivent jamais servir à la fusion
- **Identifiants stables (`uuid`)** : chaque entité exportable racine (`TargetType`, `Session`, `End`, `ArrowImpact`, `Bow`, `ArrowSet`, `SessionPreset`) possède un `uuid` (UUID v4). L'export utilise le `uuid` comme identifiant principal pour permettre :
  - L'import sur un autre appareil sans conflit d'IDs locaux (remapping automatique via `uuid`)
  - La fusion de données issues de plusieurs appareils (multi-appareil)
  - Le dédoublonnage fiable lors de l'import (recherche par `uuid`, mise à jour si existant)
- Les clés `id`, si elles sont présentes dans l'export, ne doivent pas être utilisées comme clé de fusion entre appareils
- Les conflits d'import sont résolus avec `updatedAt` : l'entité importée remplace l'entité locale uniquement si son `updatedAt` est plus récent, sauf pour les `TargetType` built-in qui ne sont pas écrasés par un fichier importé
- Les entités futures (WeatherSnapshot, Photo, DetectionResult) seront ajoutées au format dans les versions ultérieures

---

*Document version 1.4 — 15 octobre 2026*
