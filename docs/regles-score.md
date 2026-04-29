# Règles de score — LMarchery V1

> Définition des règles de calcul des scores pour les différents types de cibles

---

## 1. Système de coordonnées normalisées

L'application utilise un système de coordonnées normalisées indépendant de la résolution d'affichage ou de la taille du blason à l'écran.

### Convention

- **Centre du blason** : `(0.0, 0.0)`
- **Bord extérieur du blason** : rayon `1.0`
- Les coordonnées sont stockées en `xNormalized` et `yNormalized`
- La distance au centre est calculée par : `r = sqrt(x² + y²)`

### Exemples

| Position | x | y | r | Zone |
|----------|---|---|---|------|
| Centre exact | 0.0 | 0.0 | 0.0 | 10 |
| Légèrement décalé | 0.05 | -0.08 | 0.094 | 10 |
| Zone 8 | 0.30 | 0.20 | 0.360 | 8 |
| Bord de cible | 0.90 | 0.40 | 0.985 | 1 |
| Hors cible | 1.10 | 0.0 | 1.10 | M (miss) |

### Avantages

- Indépendant de la taille d'affichage
- Compatible avec les futures photos (calibration pixels → normalisé)
- Permet les calculs de groupement et dispersion
- Le même impact reste valide même si le blason affiché change de taille

---

## 2. Cible classique (V1)

### Description

Cible multicolore réglementaire World Archery, utilisée en salle et en extérieur.

### Diamètres officiels

| Diamètre du blason | Usage typique |
|--------------------|---------------|
| 122 cm | Extérieur (longues distances) |
| 80 cm | Extérieur (courtes distances) / Salle |
| 60 cm | Salle, certaines distances extérieur |
| 40 cm | Salle (courtes distances, jeunes) |

### Zones de score

Le blason classique comporte **10 zones concentriques** numérotées de 1 à 10, du bord vers le centre.

| Zone | Score | Couleur | Rayon intérieur normalisé | Rayon extérieur normalisé |
|------|-------|---------|--------------------------|---------------------------|
| 1 | 1 | Blanc | 0.90 | 1.00 |
| 2 | 2 | Blanc | 0.80 | 0.90 |
| 3 | 3 | Noir | 0.70 | 0.80 |
| 4 | 4 | Noir | 0.60 | 0.70 |
| 5 | 5 | Bleu | 0.50 | 0.60 |
| 6 | 6 | Bleu | 0.40 | 0.50 |
| 7 | 7 | Rouge | 0.30 | 0.40 |
| 8 | 8 | Rouge | 0.20 | 0.30 |
| 9 | 9 | Jaune | 0.10 | 0.20 |
| 10 | 10 | Jaune | 0.0 | 0.10 |

### Zone X (10+) — Configurable via TargetType

En compétition, la zone centrale du 10 (X) sert à départager les égalités. Plutôt que de hardcoder `r <= 0.05`, la détection du X est configurable au niveau du `TargetType` :

|| Champ | Type | Description |
|-------|------|-------------|
| `xRingEnabled` | `Boolean` | Active ou désactive la zone X pour ce type de cible |
| `xRingLabel` | `String` | Libellé affiché (ex. `"X"`, `"10+"`, `""` si désactivé) |
| `innerTenRadiusNormalized` | `Float` | Rayon normalisé de la zone X (défaut WA : `0.05`) |

Un impact est marqué `isX = true` uniquement si `xRingEnabled == true` **et** `r <= innerTenRadiusNormalized`.

#### Exemple — Cible WA classique

```
TargetType(
    xRingEnabled = true,
    xRingLabel = "X",
    innerTenRadiusNormalized = 0.05f
)
```

|| Zone | Score | Rayon normalisé |
|------|-------|-----------------|
| X | 10 | 0.0 – 0.05 |
| 10 | 10 | 0.05 – 0.10 |

#### Exemple — Cible sans zone X

```
TargetType(
    xRingEnabled = false,
    xRingLabel = "",
    innerTenRadiusNormalized = 0.0f
)
```

> Le X ne change pas le score mais est enregistré pour les futures statistiques et les départages.

### Miss (flèche hors cible)

Si `r > 1.0`, la flèche est comptée comme **M** (miss), score = 0.

---

## 3. Calcul du score — Algorithme

### Fonction principale

```
fun calculateScore(
    targetType: TargetType,
    xNormalized: Float,
    yNormalized: Float
): ScoreResult

data class ScoreResult(
    val score: Int,           // 0 (M) à 10
    val zone: TargetZone,     // Zone correspondante
    val distanceFromCenter: Float,  // r
    val isX: Boolean,         // true si zone X
    val isMiss: Boolean,      // true si hors cible
    val isNearCordon: Boolean, // true si proche d'une limite de zone
    val cordonZoneAbove: Int?  // Score de la zone supérieure si proche cordon
)
```

### Étapes

1. Calculer `r = sqrt(xNormalized² + yNormalized²)`
2. Parcourir les zones du centre vers l'extérieur
3. Si `r <= zone.outerRadiusNormalized` → l'impact est dans cette zone
4. Retourner le score, la zone, et les informations de cordon

### Détection du cordon — Assistance UX uniquement

Un impact est considéré **proche du cordon** si sa distance au centre est dans une zone de tolérance autour de la limite entre deux zones.

> ⚠️ **Cette tolérance est une assistance UX, pas une preuve réglementaire.**
> Elle agrège trois incertitudes : la précision du pointage tactile, le diamètre estimé de la flèche, et une marge d'ergonomie. Elle ne permet **pas** de garantir que la flèche touche réellement le trait. En cas de doute, **l'utilisateur tranche**.

```
val cordonToleranceNormalized = 0.015  // UX : précision tactile + Ø flèche + marge UX
val isNearCordon = abs(r - zone.innerRadiusNormalized) < cordonToleranceNormalized
```

Si `isNearCordon` est vrai, l'application signale visuellement la situation et propose les deux scores selon le mode de comptage. **La décision finale appartient toujours à l'utilisateur.**

---

## 4. Modes de comptage

### Mode Entraînement simple

- L'impact est dans la zone → score de cette zone
- Pas de considération de cordon
- C'est le mode par défaut

### Mode Compétition

- Si l'impact **touche la ligne** de la zone supérieure, le score de la zone supérieure est attribué
- Règle World Archery : le cordon est considéré comme faisant partie de la zone de score supérieure
- Dans l'application : si `isNearCordon` est vrai et que l'impact est dans la zone de tolérance supérieure, le score proposé est celui de la zone du dessus

### Mode Décision manuelle

- L'application propose le score calculé
- Si `isNearCordon` est vrai, l'application propose les deux scores possibles
- L'utilisateur choisit manuellement
- Le choix est enregistré dans `isCordDecisionManual = true`

---

## 5. Stockage du score

L'entité `ArrowImpact` stocke deux valeurs de score :

| Champ | Description |
|-------|-------------|
| `scoreCalculated` | Score calculé automatiquement depuis les coordonnées |
| `scoreFinal` | Score validé par l'utilisateur (peut différer en cas de cordon) |
| `isCordDecisionManual` | `true` si l'utilisateur a manuellement tranché sur un cordon |
| `inputMode` | `manual` (saisie manuelle), `automatic` (reconnaissance), `corrected` (correction d'une proposition auto) |

En V1, `inputMode` est toujours `manual` et `scoreCalculated == scoreFinal` sauf décision sur cordon.

---

## 6. Score d'une volée (End)

```
scoreEnd = somme des scoreFinal de chaque ArrowImpact de la volée
```

### Exemple — 3 flèches

| Flèche | x | y | r | Zone | scoreCalculated | scoreFinal |
|---------|---|---|---|------|-----------------|------------|
| 1 | 0.05 | -0.02 | 0.054 | 10 | 10 | 10 |
| 2 | 0.22 | 0.15 | 0.266 | 8 | 8 | 8 |
| 3 | 0.41 | -0.30 | 0.509 | 6 | 6 | 6 |

**Score volée : 24**

### Exemple — avec cordon

| Flèche | x | y | r | Zone | scoreCalculated | Cordon ? | scoreFinal |
|---------|---|---|---|------|-----------------|----------|------------|
| 1 | 0.0 | 0.0 | 0.0 | 10 | 10 | Non | 10 |
| 2 | 0.195 | -0.02 | 0.196 | 9 | 9 | Proche cordon 10 | 10 (compétition) |
| 3 | 0.85 | 0.50 | 0.986 | 2 | 2 | Non | 2 |

**Score volée : 22** (mode compétition)

---

## 7. Score total d'une séance (Session)

```
scoreSession = somme des scores de toutes les volées
maxPossible = nombreDeVolées × nombreDeFlèchesParVolée × 10
```

### Résumé de séance

| Indicateur | Formule |
|------------|---------|
| Score total | Somme des `scoreFinal` de tous les impacts |
| Score maximal possible | `volées × flèches/volée × 10` |
| Pourcentage | `scoreTotal / maxPossible × 100` |
| Moyenne par volée | `scoreTotal / nombreDeVolées` |
| Moyenne par flèche | `scoreTotal / nombreTotalImpact` |
| Nombre de 10 | Comptage des impacts avec `scoreFinal == 10` |
| Nombre de X | Comptage des impacts avec `isX == true` |
| Nombre de M (miss) | Comptage des impacts avec `isMiss == true` |

---

## 8. Cible campagne (future — V2+)

> Non implémentée en V1, mais documentée pour préparer le modèle.

### Description

Cible noire avec centre jaune, utilisée en tir campagne (field archery).

### Spécificités

- Zones noires (sans indication de score visuelle)
- Centre jaune
- Scoring différent selon les fédérations (WA Field, IFAA, etc.)
- Distance variable

### Modèle attendu

Le `TargetType` sera instancié avec `discipline = "field"` et des zones adaptées. Le moteur de score reste le même (calcul par distance au centre), seuls les rayons et les valeurs des zones changent.

---

## 9. Extensibilité du moteur

Le moteur de score est conçu pour être **générique** :

1. Un `TargetType` définit les zones (rayons, scores, couleurs)
2. Le calcul dépend uniquement de la distance normalisée au centre
3. Ajouter un nouveau type de cible = ajouter un `TargetType` avec ses `TargetZone`
4. Pas de logique hard-codée pour un type de cible spécifique

### Ajout futur d'un type de cible

```
1. Créer un TargetType (nom, discipline, diamètre)
2. Ajouter les TargetZone associées (rayons, scores, couleurs)
3. Le moteur de score l'utilise automatiquement
```

Aucune modification du code de calcul n'est nécessaire.

---

*Document version 1.0 — 29 avril 2026*