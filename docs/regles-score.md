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

### Intégrité numérique appliquée

`NormalizedPoint` rejette `NaN` et les infinis, y compris par `copy`. Une
coordonnée finie n'est pas bornée à `[-1, 1]` : `(1.1, 0)` reste un impact hors
cible valide. Les carrés et la racine sont calculés en `Double`, puis le rayon
est arrondi en `Float`, type public conservé. Cela évite les débordements et
sous-débordements intermédiaires des multiplications en `Float`.

Si le rayon en `Double` dépasse `Float.MAX_VALUE`, le calcul lève explicitement
`IllegalArgumentException`. Il n'est ni plafonné ni remplacé par une distance
inventée. `ScoreResult` et `ArrowImpact` exigent aussi une distance finie et
positive ou nulle lors d'une construction ou d'une copie. Un `ArrowImpact`
contextualisé dérive ses scores et indicateurs d'un résultat validé pour son point.

### Exemples

| Position | x | y | r | Zone |
|----------|---|---|---|------|
| Centre exact | 0.0 | 0.0 | 0.0 | 10 |
| Légèrement décalé | 0.05 | -0.08 | 0.094 | 10 |
| Zone 7 | 0.30 | 0.20 | 0.361 | 7 |
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

### Invariants des définitions de cible

Les constructeurs et les copies vérifient les règles suivantes :

- Chaque `TargetZone` a un score strictement positif et des rayons finis avec
  `0 <= innerRadiusNormalized < outerRadiusNormalized <= 1` ; `0` reste réservé
  au miss hors du disque.
- Une cible contient au moins une zone et couvre entièrement les rayons de `0`
  à `1`. Après classement du centre vers l'extérieur, les rayons voisins sont
  exactement égaux. Un trou ou un chevauchement, même d'un `Float` adjacent,
  est rejeté. Aucune tolérance tactile ne répare la définition d'une cible.
- `minScore >= 0`, `maxScore >= minScore` ; les scores des zones sont dans cet
  intervalle, strictement décroissants vers l'extérieur, et la zone centrale
  vaut `maxScore`. Il n'est pas imposé de zone pour chaque entier de l'intervalle :
  les scores réellement présents sont ceux des zones. `minScore = 0` peut
  inclure le miss dans la borne déclarée sans créer une zone de score zéro.
- Le rayon X est fini, positif ou nul, et ne dépasse pas le rayon extérieur de
  la zone centrale. S'il est activé, il est strictement positif. Désactiver le X
  peut conserver son rayon et son libellé pour une réactivation ultérieure.
- La liste des zones est copiée défensivement et exposée en lecture seule,
  y compris par `copy`. Son ordre fourni et l'égalité par contenu sont conservés.

Ces contrôles vérifient la cohérence structurelle, pas la conformité d'un profil
à une discipline ou à une catégorie d'arc. Les quatre fixtures classiques
40/60/80/122 cm restent inchangées. Voir
[ADR 009](adr/009-integrite-entrees-scoring.md).

### Zone X (10+) — Configurable via TargetType

En compétition, la zone centrale du 10 (X) sert à départager les égalités. Plutôt que de hardcoder `r <= 0.05`, la détection du X est configurable au niveau du `TargetType` :

| Champ | Type | Description |
|-------|------|-------------|
| `xRingEnabled` | `Boolean` | Active ou désactive la zone X pour ce type de cible |
| `xRingLabel` | `String` | Libellé affiché (ex. `"X"`, `"10+"`, `""` si désactivé) |
| `innerTenRadiusNormalized` | `Float` | Rayon normalisé de la zone X (`0.05` dans les fixtures classiques actuelles) |

Ces champs appartiennent à `TargetType`, qui possède aussi un `uuid` stable pour l'export/import. Les zones de score restent définies par les `TargetZone`.

Un impact est marqué `isX = true` uniquement si `xRingEnabled == true` **et** `r <= innerTenRadiusNormalized`.

#### Exemple — Cible WA classique

```
TargetType(
    xRingEnabled = true,
    xRingLabel = "X",
    innerTenRadiusNormalized = 0.05f
)
```

| Zone | Score | Rayon normalisé |
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
    point: NormalizedPoint,
    cordonToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED
): ScoreResult

data class ScoreResult(
    val score: Int,           // 0 (M) à 10
    val zone: TargetZone?,    // null pour un miss
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

### Suggestions symétriques de limites — API indépendante

```kotlin
fun findBoundaryHints(
    targetType: TargetType,
    point: NormalizedPoint,
    touchToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
): List<BoundaryHint>
```

Cette API recherche toutes les limites à proximité du point, depuis l'intérieur
comme depuis l'extérieur : chaque séparation entre zones réelles, le X/non-X
si activé et le bord du blason/M. Elle reçoit le point directement pour éviter
un état de géométrie ou un résultat de score incohérent fourni par l'appelant.

| Champ de `BoundaryHint` | Sens |
|-------------------------|------|
| `type` | `SCORE_ZONES`, `X_RING` (habituellement X/10) ou `TARGET_EDGE` |
| `radiusNormalized` | Rayon de la limite |
| `inner` / `outer` | Valeurs du côté du centre / du côté extérieur : `score`, `isX` et `isMiss` dérivé de `score == 0` |
| `distanceToBoundaryNormalized` | Écart radial absolu en `Double` |

Le X conserve le même score numérique, avec une distinction `isX`. Les scores
de part et d'autre sont ceux des zones définies, même s'ils ne sont pas
consécutifs ; au bord, la valeur extérieure est M/0. Deux limites coïncidentes
(par exemple X couvrant toute la zone centrale) restent distinctes par leur type
et décrivent leurs côtés réels, sans région intermédiaire fictive.

Le rayon du point est le même `Float` public que celui du calcul géométrique.
L'écart est obtenu en convertissant les rayons en `Double` avant soustraction.
Le seuil est **inclusif** : `écart <= touchToleranceNormalized`, sans epsilon
ajouté. La tolérance par défaut reste provisoirement `0.015f`. Une tolérance
nulle ne retient que l'égalité exacte ; les valeurs négatives, NaN et infinies
sont rejetées. Plusieurs limites peuvent être candidates ; elles sont triées
par distance, rayon puis type (`SCORE_ZONES`, `X_RING`, `TARGET_EDGE`).

Exemples sur les fixtures classiques actuelles, avec la tolérance par défaut :

| Rayon du point | Limite suggérée | Côté intérieur | Côté extérieur |
|----------------|-----------------|----------------|----------------|
| `0.095` ou `0.105` | `0.10`, `SCORE_ZONES` | 10, non-X | 9, non-X |
| `0.04` ou `0.06` | `0.05`, `X_RING` | 10, X | 10, non-X |
| `0.99` ou `1.01` | `1.00`, `TARGET_EDGE` | 1 | M/0 |

Une suggestion ne prouve jamais le contact physique du tube et ne sélectionne
aucun score final. La décision explicite ci-dessous retrouve ses propres limites
canoniques dans le profil ; elle n'exige pas qu'une limite soit dans la tolérance.
Voir [ADR 010](adr/010-suggestions-limites-score.md) et
[ADR 011](adr/011-decisions-score-explicites.md).

### Détection historique du cordon — Migration différée

Cette section décrit un comportement existant qui reste à revoir dans une PR
dédiée. La proximité actuellement calculée est unilatérale et ne couvre pas la
limite `1/M`. Elle ne constitue pas une règle de décision de cordon validée.

Le moteur marque un impact **proche du cordon** lorsque sa distance à la limite
intérieure de sa zone est inférieure à la tolérance et qu'une zone supérieure existe.

> ⚠️ **Cette tolérance est une assistance UX, pas une preuve réglementaire.**
> Une proximité issue du pointage tactile ne prouve jamais le contact physique
> du fût avec le trait. Le moteur actuel ne modélise pas ce contact ; sa tolérance
> ne peut donc pas servir de justification réglementaire à une promotion de score.

```
val cordonToleranceNormalized = 0.015  // Tolérance UX actuelle, pas une mesure du fût
val isNearCordon = zoneAbove != null &&
    abs(r - zone.innerRadiusNormalized) < cordonToleranceNormalized
```

`isNearCordon` et `cordonZoneAbove` restent exposés par `calculateScore` pour
compatibilité. Leurs calculs sont conservés, mais ils ne sont plus consultés
pour choisir le score final. Le moteur de suggestions reste distinct.

---

## 4. Modes de comptage

Sans demande explicite, **TRAINING, COMPETITION et MANUAL_CORDON conservent tous
la valeur géométrique**. À `r = 0.105` sur les fixtures classiques : géométrie 9,
final 9, et suggestion possible 10/9. L'ancienne promotion automatique en
`COMPETITION` est supprimée ; la tolérance tactile ne prouve jamais un contact.

La résolution Kotlin pure est contextualisée :

```kotlin
fun resolveFinalScore(
    targetType: TargetType,
    point: NormalizedPoint,
    scoringMode: ScoringMode,
    request: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry,
): FinalScoreDecision
```

`ScoreDecisionRequest` distingue trois situations :

| Demande | Contrôles et résultat |
|---------|-----------------------|
| `AcceptGeometry` | Accepte score et X géométriques, sans jugement de limite |
| `JudgeBoundary` | Retrouve la référence `(type, rayon)` dans le profil, vérifie un des deux côtés réels et conserve la date manuelle |
| `ExceptionalCorrection` | Vérifie la valeur légale et exige un motif non blanc et une date |

La date des variantes manuelles est obligatoire. Une confirmation conservant
le score numérique reste manuelle, notamment entre 10 et 10X. Les valeurs sont
M/0 et les scores **présents dans les zones** : 6 est invalide sur une cible
10/8/5, même s'il appartient à l'intervalle des bornes déclarées. X exige un
profil avec X activé et le score maximal ; il n'ajoute aucun point et n'est pas
automatique sur un maximum. Les jugements n'imposent pas de scores consécutifs.

Une limite arbitraire fournie par un appelant n'est jamais acceptée comme preuve :
la référence est retrouvée et les valeurs sont reconstruites depuis `TargetType`.
Un jugement humain est possible hors de la tolérance UX. Une demande commencée
avec `selected = null` est rejetée, sans repli vers la géométrie. La présence de
suggestions n'impose aucune confirmation individuelle avant `End.validate`.

Exemple : `JudgeBoundary(SCORE_ZONES, rayon 0.1, valeur 10 non-X, date)` peut
produire géométrie 9, final 10. La référence X à `0.05` permet de distinguer
10/X et 10/non-X ; la référence de bord à `1` distingue le score périphérique
réel et M. Aucun écran de jugement n'est introduit ici.

---

## 5. Stockage du score

L'entité `ArrowImpact` stocke deux valeurs de score :

| Champ | Description |
|-------|-------------|
| `scoreCalculated` | Score calculé automatiquement depuis les coordonnées |
| `scoreFinal` | Valeur géométrique acceptée ou valeur d'une décision manuelle validée |
| `isCordDecisionManual` | Dérivé du seul type `BOUNDARY_JUDGMENT`, même si le score numérique est inchangé |
| `finalScoreDecision.provenance` | Type `GEOMETRY`, `BOUNDARY_JUDGMENT` ou `EXCEPTIONAL_CORRECTION`, date manuelle, référence ou motif selon le cas |
| `inputMode` | `manual` (saisie manuelle), `automatic` (reconnaissance), `corrected` (correction d'une proposition auto) |

`InputMode` décrit la provenance de la **position**, distincte de la décision de
score. La V1 saisit les positions manuellement. `scoreCalculated` reste géométrique
lors d'une décision finale. Les indicateurs finaux sont dérivés de la valeur
finale : `isMiss == (scoreFinal == 0)` ; M exclut X. Un X géométrique corrigé en
9 ou M perd son X final ; un M corrigé valablement en 1 perd son indicateur M.

Le constructeur d'`ArrowImpact` exige un contexte de cible et un résultat validé
pour son point. Les assertions scalaires contradictoires et les modifications
de scoring par `copy` sont rejetées. La modification passe par
`withFinalScore(targetType, request, updatedAt)`. La provenance et `createdAt`
sont conservés lors des copies de métadonnées ; toute nouvelle décision manuelle
est au moins aussi récente que la révision précédente. Une provenance manuelle
ne peut être effacée silencieusement. Voir [ADR 011](adr/011-decisions-score-explicites.md).

### Correction explicite de position

Tous les déplacements passent par `impact.reposition(newPoint, changedAt, reason)` ;
`copy(point = …)` est rejeté, même sans jugement manuel. UUID, création, profil et
origine `InputMode` sont conservés. Le rayon et la géométrie sont recalculés ; le
score final et X/M prennent la nouvelle valeur géométrique sans réutiliser le
jugement précédent. Corriger la position reste distinct de corriger le score.

Si l'ancienne décision était manuelle, le motif doit être non blanc. La liste
immuable `repositions` conserve chaque ancienne position, décision invalidée et
date de déplacement, même après un nouveau jugement ou plusieurs déplacements.
La date ne peut précéder la révision courante ; un point identique est rejeté.
Seules les volées `EDITING` de séances non `COMPLETED` peuvent intégrer la nouvelle
révision. Copies et remplacements imbriqués contrôlent aussi l'historique, sans
réouverture des volées validées. Voir [ADR 012](adr/012-repositionnement-explicite-impacts.md).

---

## 6. Score d'une volée (End)

```
scoreEnd = somme des scoreFinal de chaque ArrowImpact de la volée
```

### Exemple — 3 flèches, mode TRAINING

| Flèche | x | y | r | Zone | scoreCalculated | scoreFinal |
|---------|---|---|---|------|-----------------|------------|
| 1 | 0.05 | -0.02 | 0.054 | 10 | 10 | 10 |
| 2 | 0.22 | 0.15 | 0.266 | 8 | 8 | 8 |
| 3 | 0.41 | -0.30 | 0.508 | 5 | 5 | 5 |

**Score volée : 23**

### Exemple — proximité géométrique, moteur actuel en COMPETITION

| Flèche | x | y | r | Zone | scoreCalculated | Suggestion sans jugement | scoreFinal |
|---------|---|---|---|------|-----------------|----------|------------|
| 1 | 0.0 | 0.0 | 0.0 | 10 | 10 | Non | 10 |
| 2 | 0.195 | -0.02 | 0.196 | 9 | 9 | Limite 9/8 | 9 |
| 3 | 0.85 | 0.50 | 0.986 | 1 | 1 | Limite 1/M | 1 |

**Score volée : 20** sans décision explicite. Le nouveau moteur de suggestions
signale les limites 9/8 et 1/M, mais n'attribue aucun autre score final.

---

## 7. Score total d'une séance (Session)

```
scoreSession = somme des scores de toutes les volées
```

Le score maximum utilise `targetType.maxScore` plutôt qu'une valeur hardcodée, même si les cibles classiques V1 valent 10 points par flèche.

### Score maximum possible

| Cas | Formule |
|-----|---------|
| Nombre de flèches fixe avec `plannedEndCount` | `plannedEndCount × arrowCountPerEnd × targetType.maxScore` |
| Nombre de flèches fixe sans `plannedEndCount` | `nombre de volées enregistrées × arrowCountPerEnd × targetType.maxScore` |
| Nombre de flèches libre | `nombre total d'impacts enregistrés × targetType.maxScore` |

En mode libre, l'application ne déduit jamais un score maximum à partir de 3 ou 6 flèches par volée. Le maximum progresse avec les impacts réellement saisis.

### Résumé de séance

| Indicateur | Formule |
|------------|---------|
| Score total | Somme des `scoreFinal` de tous les impacts |
| Score maximal possible | Voir les règles ci-dessus selon `arrowCountMode` |
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

Cela ne s'applique actuellement qu'aux profils concentriques complets, couvrant
`[0, 1]`. Une liste tronquée n'active pas implicitement un blason réduit.
Les futurs profils réduits devront définir le rayon de référence, la surface
active et la règle hors surface active ; les multispots devront aussi identifier
le spot et les contraintes de répartition des flèches. Leur support et les
spécificités réglementaires du 10 en salle restent des tâches distinctes.

---

*Document version 1.5 — 15 octobre 2026*
