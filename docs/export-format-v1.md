# Format d'export V1 — LMarchery

> Schéma JSON pour l'export et l'import des données, incluant les UUID stables

---

## 1. Principe

L'export permet à l'utilisateur de sauvegarder ses données hors de l'application. L'import permet de restaurer ou de fusionner sur un autre appareil.

### Clés d'identification

Chaque entité exportable racine (`TargetType`, `Bow`, `ArrowSet`, `SessionPreset`, `Session`, `End`, `ArrowImpact`) possède deux identifiants :

| Champ | Type | Rôle |
|-------|------|------|
| `id` | Long | Clé primaire locale (Room / SQLite). Utilisée en interne, **non stable** entre appareils. |
| `uuid` | String (UUID v4) | Identifiant universel stable. Utilisé pour la fusion, le dédoublonnage et l'import multi-appareil. |

`TargetZone` n'a pas de `uuid` indépendant en V1 : ses zones sont exportées dans leur `TargetType` parent.

Le champ `id` peut être présent dans un export pour compatibilité locale ou diagnostic, mais les exemples de ce document l'omettent volontairement. Un import doit toujours ignorer `id` pour la fusion et reconstruire les relations avec les `uuid`.

Lors de l'import :
- Si un `uuid` existe déjà localement → l'entité est mise à jour
- Si le `uuid` n'existe pas → l'entité est créée avec un `id` local généré
- Le champ `id` n'est **jamais** utilisé comme clé de fusion

### Horodatage de fusion

Les entités exportables modifiables portent `createdAt` et `updatedAt`.

- `createdAt` est fixé à la création et ne sert pas à résoudre les conflits.
- `updatedAt` est mis à jour à chaque modification fonctionnelle.
- En cas d'import d'un même `uuid`, l'entité dont `updatedAt` est le plus récent gagne.
- Les `TargetType` built-in (`isBuiltIn = true`) ne sont jamais écrasés par un import, même si le fichier importé contient un `updatedAt` plus récent.
- Pour une séance, toute modification d'une volée ou d'un impact met aussi à jour `Session.updatedAt`, afin de résoudre le conflit au niveau de la séance complète.

### Cohérence des instantanés de séance

Les données reconstruites doivent respecter `createdAt <= updatedAt`, une création
d'enfant postérieure ou égale à celle du parent et un `updatedAt` parent couvrant
tous ses enfants. La validation d'une volée couvre ses impacts ; la fin d'une
séance couvre ses volées. Les dates de début, fin et validation ne dépassent pas
le `updatedAt` de leur objet.

Les modèles rejettent un instantané incohérent au lieu de corriger silencieusement
ses dates. Lors de la modification d'une valeur existante, `copy` et les méthodes
métier interdisent aussi la régression de `updatedAt` et conservent `createdAt`.
L'intégration d'un nouvel enfant plus ancien peut conserver la date du parent ;
une révision obsolète d'un enfant déjà présent est rejetée. La future résolution
des conflits doit sélectionner la révision avant son intégration, sans antidater
la séance ni écraser une révision plus récente d'un descendant.

Ces règles ne constituent pas une implémentation d'import/export. Les types du
domaine restent provisoirement `LocalDateTime` ; les offsets des exemples JSON
ne définissent pas encore une convention UTC/fuseau de conversion. Cette décision
est réservée à une tâche distincte avant la persistance. Voir
[ADR 008](adr/008-integrite-horodatages-domaine.md).

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

La définition ci-dessous couvre le blason complet : aucune zone intermédiaire
ne peut être omise à la reconstruction du modèle. Les rayons doivent respecter
les [invariants de cible](regles-score.md#invariants-des-définitions-de-cible).

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
  "createdAt": "2026-04-29T10:30:00+02:00",
  "updatedAt": "2026-04-29T10:30:00+02:00",
  "zones": [
    {
      "score": 10,
      "innerRadiusNormalized": 0.0,
      "outerRadiusNormalized": 0.1,
      "colorName": "jaune"
    },
    {
      "score": 9,
      "innerRadiusNormalized": 0.1,
      "outerRadiusNormalized": 0.2,
      "colorName": "jaune"
    },
    {
      "score": 8,
      "innerRadiusNormalized": 0.2,
      "outerRadiusNormalized": 0.3,
      "colorName": "rouge"
    },
    {
      "score": 7,
      "innerRadiusNormalized": 0.3,
      "outerRadiusNormalized": 0.4,
      "colorName": "rouge"
    },
    {
      "score": 6,
      "innerRadiusNormalized": 0.4,
      "outerRadiusNormalized": 0.5,
      "colorName": "bleu"
    },
    {
      "score": 5,
      "innerRadiusNormalized": 0.5,
      "outerRadiusNormalized": 0.6,
      "colorName": "bleu"
    },
    {
      "score": 4,
      "innerRadiusNormalized": 0.6,
      "outerRadiusNormalized": 0.7,
      "colorName": "noir"
    },
    {
      "score": 3,
      "innerRadiusNormalized": 0.7,
      "outerRadiusNormalized": 0.8,
      "colorName": "noir"
    },
    {
      "score": 2,
      "innerRadiusNormalized": 0.8,
      "outerRadiusNormalized": 0.9,
      "colorName": "blanc"
    },
    {
      "score": 1,
      "innerRadiusNormalized": 0.9,
      "outerRadiusNormalized": 1.0,
      "colorName": "blanc"
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
  "createdAt": "2026-03-15T14:00:00+01:00",
  "updatedAt": "2026-03-15T14:00:00+01:00"
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
  "createdAt": "2026-03-15T14:05:00+01:00",
  "updatedAt": "2026-03-15T14:05:00+01:00"
}
```

### SessionPreset

```json
{
  "uuid": "m3n4o5p6-...",
  "name": "Salle 18m — 3 flèches",
  "targetTypeUuid": "a1b2c3d4-...",
  "distanceM": 18,
  "arrowCountMode": "FIXED",
  "arrowCountPerEnd": 3,
  "isIndoor": true,
  "scoringMode": "TRAINING",
  "bowUuid": "e5f6g7h8-...",
  "arrowSetUuid": "i9j0k1l2-...",
  "createdAt": "2026-03-15T14:10:00+01:00",
  "updatedAt": "2026-03-15T14:10:00+01:00"
}
```

En mode libre, `arrowCountMode` vaut `"FREE"` et `arrowCountPerEnd` vaut `null`.

### Session (avec ends et impacts)

```json
{
  "uuid": "q7r8s9t0-...",
  "date": "2026-04-28T18:00:00+02:00",
  "title": "Entraînement salle",
  "targetTypeUuid": "a1b2c3d4-...",
  "distanceM": 18,
  "arrowCountMode": "FIXED",
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
  "totalScore": 10,
  "maxPossibleScore": 600,
  "createdAt": "2026-04-28T18:00:00+02:00",
  "updatedAt": "2026-04-28T19:15:00+02:00",
  "ends": [
    {
      "uuid": "u1v2w3x4-...",
      "index": 1,
      "status": "VALIDATED",
      "totalScore": 10,
      "notes": "",
      "createdAt": "2026-04-28T18:05:00+02:00",
      "updatedAt": "2026-04-28T18:06:00+02:00",
      "validatedAt": "2026-04-28T18:06:00+02:00",
      "impacts": [
        {
          "uuid": "y5z6a7b8-...",
          "index": 1,
          "xNormalized": 0.05,
          "yNormalized": -0.02,
          "distanceFromCenter": 0.05385165,
          "scoreCalculated": 10,
          "scoreFinal": 10,
          "isX": false,
          "isMiss": false,
          "inputMode": "MANUAL",
          "isCordDecisionManual": false,
          "scoreDecision": {
            "kind": "GEOMETRY",
            "decidedAt": null,
            "boundary": null,
            "reason": null
          },
          "note": "",
          "createdAt": "2026-04-28T18:05:20+02:00",
          "updatedAt": "2026-04-28T18:05:20+02:00"
        }
      ]
    }
  ]
}
```

Règle pour `maxPossibleScore` :

- Si `arrowCountMode = "FIXED"` et `plannedEndCount` est renseigné : `plannedEndCount × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = "FIXED"` sans `plannedEndCount` : `nombre de volées enregistrées × arrowCountPerEnd × targetType.maxScore`.
- Si `arrowCountMode = "FREE"` : `nombre total d'impacts enregistrés × targetType.maxScore`. Le mode libre ne suppose jamais 3 ou 6 flèches par volée.

### Provenance de la décision finale d'un impact

Les champs racines `scoreFinal`, `isX` et `isMiss` décrivent la valeur **finale**.
`scoreCalculated` reste géométrique. `scoreDecision` représente la provenance
du résultat du domaine, séparément d'`inputMode`, qui décrit la position :

| `kind` | Contraintes |
|--------|-------------|
| `GEOMETRY` | Valeur géométrique ; date manuelle, limite et motif nuls |
| `BOUNDARY_JUDGMENT` | Date et référence `(type, radiusNormalized)` obligatoires ; motif nul |
| `EXCEPTIONAL_CORRECTION` | Date et motif non blanc obligatoires ; limite nulle |

`isCordDecisionManual` est dérivé du seul jugement de limite. Une correction
exceptionnelle a une provenance manuelle, mais ce booléen vaut faux. Un jugement
conservant le score numérique conserve sa date et son type manuels.

Exemple de jugement explicite 10/9, après un calcul géométrique de 9 :

```json
{
  "uuid": "b1c2d3e4-...",
  "index": 2,
  "xNormalized": 0.105,
  "yNormalized": 0.0,
  "distanceFromCenter": 0.105,
  "scoreCalculated": 9,
  "scoreFinal": 10,
  "isX": false,
  "isMiss": false,
  "inputMode": "MANUAL",
  "isCordDecisionManual": true,
  "scoreDecision": {
    "kind": "BOUNDARY_JUDGMENT",
    "decidedAt": "2026-04-28T18:05:25+02:00",
    "boundary": { "type": "SCORE_ZONES", "radiusNormalized": 0.1 },
    "reason": null
  },
  "note": "",
  "createdAt": "2026-04-28T18:05:20+02:00",
  "updatedAt": "2026-04-28T18:05:25+02:00"
}
```

Exemple de correction exceptionnelle d'un X géométrique en 9 :

```json
{
  "uuid": "c1d2e3f4-...",
  "index": 3,
  "xNormalized": 0.0,
  "yNormalized": 0.0,
  "distanceFromCenter": 0.0,
  "scoreCalculated": 10,
  "scoreFinal": 9,
  "isX": false,
  "isMiss": false,
  "inputMode": "MANUAL",
  "isCordDecisionManual": false,
  "scoreDecision": {
    "kind": "EXCEPTIONAL_CORRECTION",
    "decidedAt": "2026-04-28T18:05:25+02:00",
    "boundary": null,
    "reason": "Point initial mal positionné ; score constaté sur le blason"
  },
  "note": "",
  "createdAt": "2026-04-28T18:05:20+02:00",
  "updatedAt": "2026-04-28T18:05:25+02:00"
}
```

La future reconstruction doit retrouver le `TargetType` de la séance, résoudre
la demande typée, puis comparer les scores, indicateurs et distance stockés aux
valeurs dérivées. Les valeurs autorisées sont M/0 et les scores réellement
présents dans le profil. Une référence de limite est revérifiée dans celui-ci ;
les deux côtés d'un ancien `BoundaryHint` ne sont pas une preuve. La date manuelle
doit vérifier `createdAt <= decidedAt <= updatedAt`.

Ces exemples ne constituent aucun import/export opérationnel. La politique de
versionnement des profils et la migration d'anciens fichiers sans `scoreDecision`
restent à définir ; il ne faut pas inventer un jugement depuis le seul booléen
historique ou une différence de scores. `LocalDateTime` reste provisoire : les
offsets illustratifs n'arrêtent pas la convention UTC/fuseau. Voir
[ADR 011](adr/011-decisions-score-explicites.md).

### Traces des corrections de position (contrat à préserver avant Room)

Un export futur doit conserver `repositions`, vide à la création, dans son ordre
chronologique. Chaque entrée représente un déplacement explicite ; elle conserve
l'identité et l'origine de l'impact, l'ancienne position avec sa décision complète,
sa précédente date de révision, le nouveau point, la date et le motif du déplacement.
Le score courant ne suffit jamais à reconstruire cette invalidation. Par exemple,
après le jugement 10/9 ci-dessus, un déplacement vers `(0.25, 0)` donne 8 :

```json
{
  "xNormalized": 0.25,
  "yNormalized": 0.0,
  "distanceFromCenter": 0.25,
  "scoreCalculated": 8,
  "scoreFinal": 8,
  "isX": false,
  "isMiss": false,
  "isCordDecisionManual": false,
  "scoreDecision": { "kind": "GEOMETRY", "decidedAt": null, "boundary": null, "reason": null },
  "repositions": [{
    "impactUuid": "b1c2d3e4-...",
    "impactCreatedAt": "2026-04-28T18:05:20+02:00",
    "inputMode": "MANUAL",
    "previousPoint": { "xNormalized": 0.105, "yNormalized": 0.0 },
    "previousDecision": {
      "scoreCalculated": 9,
      "geometricIsX": false,
      "scoreFinal": 10,
      "isX": false,
      "scoreDecision": {
        "kind": "BOUNDARY_JUDGMENT",
        "decidedAt": "2026-04-28T18:05:25+02:00",
        "boundary": { "type": "SCORE_ZONES", "radiusNormalized": 0.1 },
        "reason": null
      }
    },
    "previousUpdatedAt": "2026-04-28T18:05:25+02:00",
    "newPoint": { "xNormalized": 0.25, "yNormalized": 0.0 },
    "changedAt": "2026-04-28T18:06:00+02:00",
    "reason": "Position initiale placée dans le mauvais anneau"
  }],
  "updatedAt": "2026-04-28T18:06:00+02:00"
}
```

Cet extrait complète le même impact : UUID, `createdAt`, profil et `inputMode`
racines restent ceux de l'exemple précédent. Le profil de la séance est aussi
celui des anciennes décisions ; rayon et indicateurs se revérifient par recalcul.
Une nouvelle décision ne doit pas précéder le déplacement qu'elle arbitre.
Les traces antérieures doivent rester présentes après de nouveaux déplacements
ou jugements. Un adaptateur devra les rejouer dans le contexte de cible, puis
comparer les révisions à l'agrégat connu ; un constructeur isolé ne connaît pas
un historique omis. Cet exemple décrit les informations à préserver, sans figer
un sérialiseur ou la migration des fichiers historiques. Voir
[ADR 012](adr/012-repositionnement-explicite-impacts.md).

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
- Si un ancien fichier d'export ne contient pas `updatedAt`, l'entité importée est considérée comme plus ancienne que l'entité locale, sauf si l'entité locale n'existe pas encore
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

*Dernière mise à jour : 15 octobre 2026*
