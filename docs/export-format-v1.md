# Format d'export V1 — LMarchery

> Schéma JSON pour l'export et l'import des données, incluant les UUID stables

---

## 1. Principe

L'export permet à l'utilisateur de sauvegarder ses données hors de l'application. L'import permet de restaurer ou de fusionner sur un autre appareil.

### Clés d'identification

Chaque entité possède deux identifiants :

| Champ | Type | Rôle |
|-------|------|------|
| `id` | Long | Clé primaire locale (Room / SQLite). Utilisée en interne, **non stable** entre appareils. |
| `uuid` | String (UUID v4) | Identifiant universel stable. Utilisé pour la fusion, le dédoublonnage et l'import multi-appareil. |

Lors de l'import :
- Si un `uuid` existe déjà localement → l'entité est mise à jour
- Si le `uuid` n'existe pas → l'entité est créée avec un `id` local généré
- Le champ `id` n'est **jamais** utilisé comme clé de fusion

---

## 2. Schéma JSON

### Structure générale

```json
{
  "version": 1,
  "appName": "LMarchery",
  "exportDate": "2026-04-29T10:30:00+02:00",
  "deviceInfo": "Samsung Galaxy S24 / Android 15",
  "targetTypes": [...],
  "bows": [...],
  "arrowSets": [...],
  "sessionPresets": [...],
  "sessions": [...]
}
```

### TargetType

```json
{
  "uuid": "a1b2c3d4-...",
  "name": "Classique 80 cm",
  "discipline": "indoor",
  "diameterCm": 80,
  "maxScore": 10,
  "minScore": 1,
  "defaultArrowCount": 3,
  "defaultDistanceM": 18,
  "isBuiltIn": true,
  "xRingEnabled": true,
  "xRingLabel": "X",
  "innerTenRadiusNormalized": 0.05,
  "zones": [
    {
      "score": 10,
      "innerRadiusNormalized": 0.0,
      "outerRadiusNormalized": 0.1,
      "colorName": "jaune"
    }
  ]
}
```

### Bow

```json
{
  "uuid": "e5f6g7h8-...",
  "name": "Mon recurve",
  "type": "recurve",
  "drawWeightLbs": 34.0,
  "brand": "Hoyt",
  "model": "Formula Xi",
  "notes": "Viseur Shibuya Ultima",
  "createdAt": "2026-03-15T14:00:00+01:00"
}
```

### ArrowSet

```json
{
  "uuid": "i9j0k1l2-...",
  "name": "ACE 520",
  "spine": "520",
  "length": "28.5",
  "pointWeight": "100",
  "nock": "Pin nock",
  "vanes": "Spinwing",
  "material": "carbone",
  "notes": "",
  "createdAt": "2026-03-15T14:05:00+01:00"
}
```

### SessionPreset

```json
{
  "uuid": "m3n4o5p6-...",
  "name": "Salle 18m — 3 flèches",
  "targetTypeUuid": "a1b2c3d4-...",
  "distanceM": 18,
  "arrowCountPerEnd": 3,
  "isIndoor": true,
  "scoringMode": "TRAINING",
  "bowUuid": "e5f6g7h8-...",
  "arrowSetUuid": "i9j0k1l2-..."
}
```

### Session (avec ends et impacts)

```json
{
  "uuid": "q7r8s9t0-...",
  "date": "2026-04-28T18:00:00+02:00",
  "title": "Entraînement salle",
  "targetTypeUuid": "a1b2c3d4-...",
  "distanceM": 18,
  "arrowCountPerEnd": 3,
  "isIndoor": true,
  "scoringMode": "COMPETITION",
  "bowUuid": "e5f6g7h8-...",
  "arrowSetUuid": "i9j0k1l2-...",
  "locationName": "Club archerie Ville",
  "notes": "Bon groupement ce soir",
  "status": "COMPLETED",
  "startedAt": "2026-04-28T18:02:00+02:00",
  "endedAt": "2026-04-28T19:15:00+02:00",
  "plannedEndCount": 20,
  "totalScore": 248,
  "maxPossibleScore": 600,
  "createdAt": "2026-04-28T18:00:00+02:00",
  "updatedAt": "2026-04-28T19:15:00+02:00",
  "ends": [
    {
      "uuid": "u1v2w3x4-...",
      "index": 1,
      "status": "VALIDATED",
      "totalScore": 24,
      "notes": "",
      "createdAt": "2026-04-28T18:05:00+02:00",
      "validatedAt": "2026-04-28T18:06:00+02:00",
      "impacts": [
        {
          "uuid": "y5z6a7b8-...",
          "index": 1,
          "xNormalized": 0.05,
          "yNormalized": -0.02,
          "distanceFromCenter": 0.054,
          "scoreCalculated": 10,
          "scoreFinal": 10,
          "isX": true,
          "isMiss": false,
          "inputMode": "MANUAL",
          "isCordDecisionManual": false,
          "note": ""
        }
      ]
    }
  ]
}
```

---

## 3. Règles d'import

### Fusion par UUID

1. Lire le fichier JSON
2. Pour chaque entité, chercher si le `uuid` existe déjà localement
3. Si oui → mise à jour des champs modifiables (ne pas écraser `id` local)
4. Si non → création avec un `id` local auto-généré
5. Les références entre entités utilisent les `uuid` (ex. `session.targetTypeUuid`)

### Résolution des conflits

- En cas de conflit sur une entité (même `uuid`, données différentes) → **version la plus récente gagne** (comparaison `updatedAt`)
- Les entités built-in (`isBuiltIn = true`) ne sont pas modifiées lors de l'import

### Ordre d'import

1. `TargetType` (référencés par toutes les autres entités)
2. `Bow` et `ArrowSet` (référencés par presets et sessions)
3. `SessionPreset` (référence types de cible, arcs, flèches)
4. `Session` (référence tout le reste)

---

## 4. Format de fichier

- **Extension** : `.lmarchery.json`
- **Encodage** : UTF-8
- **MIME type** : `application/json`
- **Partage** : via intent Android (fichier, email, cloud, etc.)

---

*Dernière mise à jour : 29 avril 2026*