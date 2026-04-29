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

## 2. Définition détaillée des entités

### 2.1 TargetType

Le type de cible définit les règles officielles. Il est générique et extensible.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `name` | String | Nom affiché (ex. "Classique 10 zones") |
| `discipline` | String | Discipline (ex. "indoor", "outdoor", "field") |
| `diameterCm` | Int | Diamètre officiel en cm (40, 60, 80, 122) |
| `maxScore` | Int | Score maximum par flèche (10 pour classique) |
| `minScore` | Int | Score minimum (1, ou 0 si miss compté) |
| `defaultArrowCount` | Int | Nombre de flèches par volée par défaut |
| `defaultDistanceM` | Int | Distance de tir par défaut en mètres |
| `zones` | List\<TargetZone\> | Zones de score (relation 1:N) |
| `isBuiltIn` | Boolean | True si prédéfini (non modifiable par l'utilisateur) |

**Instances V1** :

- Classique 122 cm (extérieur)
- Classique 80 cm (extérieur / salle)
- Classique 60 cm (salle)
- Classique 40 cm (salle, jeunes)

---

### 2.2 TargetZone

Zone de score d'un type de cible.

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
| `date` | LocalDateTime | Date et heure de la séance |
| `title` | String | Titre / nom de la séance |
| `targetTypeId` | Long (FK) | Type de cible utilisé |
| `distanceM` | Int | Distance de tir en mètres |
| `arrowCountPerEnd` | Int | Nombre de flèches par volée |
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

---

### 2.4 End (Volée)

Une volée = un ensemble de flèches tirées avant d'aller marquer.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `sessionId` | Long (FK) | Référence vers Session |
| `index` | Int | Numéro de la volée dans la séance (1, 2, 3…) |
| `totalScore` | Int | Score total de la volée |
| `notes` | String? | Notes libres |
| `impacts` | List\<ArrowImpact\> | Impacts de la volée (relation 1:N) |

**Contrainte** : `index` est unique par session.

---

### 2.5 ArrowImpact

Impact individuel d'une flèche. C'est l'entité centrale du scoring.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `endId` | Long (FK) | Référence vers End |
| `index` | Int | Ordre de la flèche dans la volée (1, 2, 3…) |
| `xNormalized` | Float | Coordonnée X normalisée (-1.0 à 1.0, centre = 0) |
| `yNormalized` | Float | Coordonnée Y normalisée (-1.0 à 1.0, centre = 0) |
| `distanceFromCenter` | Float | Distance au centre = sqrt(x² + y²) |
| `scoreCalculated` | Int | Score calculé automatiquement depuis les coordonnées |
| `scoreFinal` | Int | Score validé par l'utilisateur (peut différer si cordon) |
| `isX` | Boolean | True si l'impact est dans la zone X (centre du 10) |
| `isMiss` | Boolean | True si hors cible (r > 1.0) |
| `inputMode` | Enum | Mode de saisie : MANUAL / AUTOMATIC / CORRECTED |
| `isCordDecisionManual` | Boolean | True si l'utilisateur a tranché manuellement sur le cordon |
| `note` | String? | Note libre sur cet impact |

**En V1** : `inputMode` est toujours `MANUAL`. Les champs `isX`, `isMiss` et `isCordDecisionManual` sont calculés ou positionnés automatiquement sauf décision manuelle.

**Pourquoi stocker `scoreCalculated` ET `scoreFinal` ?**

- `scoreCalculated` = ce que l'algorithme a proposé (immuable après calcul)
- `scoreFinal` = ce que l'utilisateur a validé (modifiable)
- La différence entre les deux mesure la qualité du calcul automatique et, en V3+, de la reconnaissance

---

### 2.6 Bow

Arc de l'utilisateur.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `name` | String | Nom donné par l'utilisateur |
| `type` | String? | Type d'arc (recurve, compound, barebow, longbow, trad) |
| `drawWeightLbs` | Float? | Puissance en livres |
| `brand` | String? | Marque |
| `model` | String? | Modèle |
| `notes` | String? | Notes libres |
| `createdAt` | LocalDateTime | Date de création |

> Les réglages détaillés (viseur, berger button, band, palette, stabilisation) feront l'objet d'une entité `BowSettings` en V2. En V1, on se contente de notes libres.

---

### 2.7 ArrowSet

Jeu de flèches de l'utilisateur.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `name` | String | Nom donné par l'utilisateur |
| `spine` | String? | Spine des flèches |
| `length` | String? | Longueur |
| `pointWeight` | String? | Poids de la pointe |
| `nock` | String? | Type d'encoche |
| `vanes` | String? | Type de plumes/empennage |
| `material` | String? | Matériau (carbone, aluminium, carbone/aluminium, bois) |
| `notes` | String? | Notes libres |
| `createdAt` | LocalDateTime | Date de création |

---

### 2.8 SessionPreset

Configuration prédéfinie pour créer rapidement une séance.

| Attribut | Type | Description |
|----------|------|-------------|
| `id` | Long (PK) | Identifiant unique |
| `name` | String | Nom du preset (ex. "Salle 18m — 3 flèches") |
| `targetTypeId` | Long (FK) | Type de cible par défaut |
| `distanceM` | Int | Distance par défaut |
| `arrowCountPerEnd` | Int | Nombre de flèches par volée |
| `isIndoor` | Boolean | Intérieur par défaut |
| `scoringMode` | Enum | Mode de comptage par défaut |
| `bowId` | Long? (FK) | Arc par défaut |
| `arrowSetId` | Long? (FK) | Flèches par défaut |

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

### ScoringMode

```kotlin
enum class ScoringMode {
    TRAINING,        // Score de la zone donde se trouve l'impact
    COMPETITION,     // Cordon = score supérieur
    MANUAL_CORDON    // L'utilisateur tranche sur chaque cordon
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

Export en JSON contenant l'intégralité des données utilisateur :

```json
{
  "version": 1,
  "exportDate": "2026-04-29T10:30:00",
  "targetTypes": [...],
  "bows": [...],
  "arrowSets": [...],
  "sessionPresets": [...],
  "sessions": [
    {
      "id": 1,
      "date": "2026-04-28T18:00:00",
      "title": "Entraînement salle",
      "ends": [
        {
          "index": 1,
          "impacts": [
            {
              "index": 1,
              "xNormalized": 0.05,
              "yNormalized": -0.02,
              "scoreFinal": 10,
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
- Les IDs locaux sont préservés pour permettre la réimportation sur le même appareil
- Pour l'import sur un autre appareil, les IDs devront être remappés
- Les entités futures (WeatherSnapshot, Photo, DetectionResult) seront ajoutées au format dans les versions ultérieures

---

*Document version 1.0 — 29 avril 2026*