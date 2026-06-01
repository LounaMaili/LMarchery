# Plan technique V1 — LMarchery

> Passage de la documentation au premier code Android, en gardant la V1 centrée sur la saisie manuelle.

---

## 1. Principes directeurs

- Démarrer par le domaine pur Kotlin, sans dépendance Android, Compose, Room ou appareil.
- Implémenter et tester le moteur de score avant tout écran de blason interactif.
- Garder la V1 locale-first : aucune dépendance réseau obligatoire, aucun compte, aucun backend.
- Garder les impacts en coordonnées normalisées : centre `(0.0, 0.0)`, bord extérieur rayon `1.0`.
- Ne jamais utiliser les identifiants Room comme clés de fusion : les entités exportables portent des UUID v4.
- Documenter toute nouvelle décision structurante dans un ADR avant de l'implémenter.
- Ne pas ajouter CameraX, OpenCV, TFLite, ONNX ou reconnaissance automatique en V1.

---

## 2. Architecture cible

Architecture cible en couches :

```text
UI Compose
  -> ViewModel / état UI
  -> Cas d'usage domaine
  -> Repositories
  -> Room / export JSON / préférences locales
```

Le domaine ne dépend pas d'Android. Les règles de score, les modèles métier et les agrégats de séance doivent pouvoir être testés avec de simples tests unitaires JVM.

La couche UI ne calcule pas les scores. Elle convertit seulement les interactions écran en coordonnées normalisées, appelle le domaine, puis affiche les résultats.

La couche data ne contient pas de logique de scoring. Elle persiste les données Room, mappe les entités Room vers les modèles métier, et gère l'export/import JSON.

---

## 3. Modules proposés

### Phase 1 — Kotlin pur, avant Android

Créer d'abord un projet Gradle minimal avec des modules Kotlin/JVM :

| Module | Rôle | Dépendances attendues |
|--------|------|-----------------------|
| `core:model` | Modèles métier V1 et énumérations | Kotlin standard |
| `core:scoring` | Moteur de score, résultats, règles de cordon | `core:model` |

Ces deux modules doivent passer leurs tests avant la création de l'application Android.

### Phase 2 — Squelette Android

Après validation du moteur de score :

| Module | Rôle | Dépendances attendues |
|--------|------|-----------------------|
| `app` | Application Android, navigation, thème, injection simple | Modules `core` et `feature` |
| `core:database` | Room, entités SQL, DAO, migrations, seed des cibles | `core:model` |
| `core:data` | Repositories, mapping domain/data, orchestration locale | `core:model`, `core:database`, `core:scoring` |
| `core:export` | DTO JSON, export/import `.lmarchery.json`, fusion UUID | `core:model`, éventuellement `core:data` |

### Phase 3 — Fonctionnalités UI

| Module | Rôle |
|--------|------|
| `feature:sessions` | Accueil, création, cycle de vie des séances, historique |
| `feature:scoring` | Saisie de volée, blason interactif, tableau de volée |
| `feature:equipment` | Fiches arcs et jeux de flèches |
| `feature:presets` | Création et sélection de presets |
| `feature:settings` | Préférences V1, export/import |

Ne pas créer de module `camera`, `vision` ou `ml` en V1.

---

## 4. Packages proposés

Base package proposé : `com.lmarchery`.

```text
com.lmarchery.core.model
com.lmarchery.core.model.target
com.lmarchery.core.model.session
com.lmarchery.core.model.equipment
com.lmarchery.core.scoring
com.lmarchery.core.scoring.fixtures
com.lmarchery.core.database
com.lmarchery.core.database.entity
com.lmarchery.core.database.dao
com.lmarchery.core.data
com.lmarchery.core.data.mapper
com.lmarchery.core.export
com.lmarchery.feature.sessions
com.lmarchery.feature.scoring
com.lmarchery.feature.equipment
com.lmarchery.feature.presets
com.lmarchery.feature.settings
```

Le package `core.scoring` doit rester utilisable sans Android. Les conversions écran/pixels vers coordonnées normalisées appartiennent à `feature.scoring` ou à un helper UI, pas au moteur de score.

---

## 5. Ordre des branches de développement

1. `v1/00-gradle-kotlin-scoring`
   - Créer le minimum Gradle/Kotlin pour compiler des modules JVM.
   - Ajouter `core:model`, `core:scoring` et les tests unitaires.
   - Aucune app Android à ce stade.

2. `v1/01-scoring-engine`
   - Implémenter `TargetType`, `TargetZone`, `ScoreResult`, `calculateScore`.
   - Ajouter les fixtures des cibles classiques 40, 60, 80, 122 cm.
   - Couvrir limites de zones, miss, X, cordon et modes de comptage.

3. `v1/02-session-domain`
   - Ajouter les modèles domaine `Session`, `End`, `ArrowImpact`.
   - Ajouter les calculs d'agrégats : score volée, score séance, `maxPossibleScore`, compteurs 10/X/M.
   - Couvrir le mode `FIXED` et le mode `FREE`.

4. `v1/03-android-skeleton`
   - Créer le squelette Android seulement après les tests du domaine.
   - Ajouter `app`, Compose minimal, navigation minimale, thème.
   - Connecter le domaine sans persistance dans un premier écran de démonstration si nécessaire.

5. `v1/04-room-schema`
   - Ajouter Room, entités SQL, DAO et seed des `TargetType` / `TargetZone`.
   - Ajouter les contraintes et index documentés.
   - Tester les DAO et les mappings domain/data.

6. `v1/05-manual-target-entry`
   - Implémenter le blason interactif Compose.
   - Convertir les touches écran en coordonnées normalisées.
   - Placer, déplacer, supprimer et corriger un impact.

7. `v1/06-session-flow`
   - Créer séance, démarrer, ajouter volées, valider, terminer.
   - Reprendre une séance `DRAFT` ou `IN_PROGRESS`.
   - Tester interruption, verrouillage et changement d'application.

8. `v1/07-history-summary`
   - Accueil, historique, détail séance, résumé, tableau des volées.

9. `v1/08-equipment-presets`
   - Arcs, jeux de flèches, presets et association aux séances.

10. `v1/09-export-import`
    - Export/import JSON `.lmarchery.json`.
    - Fusion par UUID, conflit par `updatedAt`, remapping des relations.

11. `v1/10-v1-hardening`
    - Tests terrain, corrections UX, cas limites, lint, release debug.

---

## 6. Premières entités Kotlin

Les premières entités doivent être des modèles domaine, pas des entités Room. Les annotations Room viennent plus tard dans `core:database`.

### Cibles

- `TargetType`
  - `uuid`
  - `name`
  - `discipline`
  - `diameterCm`
  - `maxScore`
  - `minScore`
  - `defaultArrowCount`
  - `defaultDistanceM`
  - `isBuiltIn`
  - `xRingEnabled`
  - `xRingLabel`
  - `innerTenRadiusNormalized`
  - `zones`

- `TargetZone`
  - `score`
  - `innerRadiusNormalized`
  - `outerRadiusNormalized`
  - `colorName`
  - `colorHex`

### Scoring

- `NormalizedPoint`
  - `x`
  - `y`

- `ScoreResult`
  - `score`
  - `zone`
  - `distanceFromCenter`
  - `isX`
  - `isMiss`
  - `isNearCordon`
  - `cordonZoneAbove`

- `ScoringMode`
  - `TRAINING`
  - `COMPETITION`
  - `MANUAL_CORDON`

### Séances

- `Session`
  - `uuid`
  - `date`
  - `status`
  - `startedAt`
  - `endedAt`
  - `plannedEndCount`
  - `title`
  - `targetType`
  - `distanceM`
  - `arrowCountMode`
  - `arrowCountPerEnd`
  - `isIndoor`
  - `scoringMode`
  - `bowUuid`
  - `arrowSetUuid`
  - `locationName`
  - `notes`
  - `ends`
  - `createdAt`
  - `updatedAt`

- `End`
  - `uuid`
  - `index`
  - `status`
  - `impacts`
  - `notes`
  - `createdAt`
  - `updatedAt`
  - `validatedAt`

- `ArrowImpact`
  - `uuid`
  - `index`
  - `point`
  - `distanceFromCenter`
  - `scoreCalculated`
  - `scoreFinal`
  - `isX`
  - `isMiss`
  - `inputMode`
  - `isCordDecisionManual`
  - `note`
  - `createdAt`
  - `updatedAt`

### Matériel et presets

- `Bow`
- `ArrowSet`
- `SessionPreset`
- `ArrowCountMode`
- `InputMode`
- `SessionStatus`
- `EndStatus`

---

## 7. Moteur de score attendu

API domaine attendue :

```kotlin
fun calculateScore(
    targetType: TargetType,
    point: NormalizedPoint,
    cordonToleranceNormalized: Float = 0.015f
): ScoreResult
```

Responsabilités :

- Calculer `distanceFromCenter = sqrt(x² + y²)`.
- Retourner un miss si `distanceFromCenter > 1.0`.
- Parcourir les zones du centre vers l'extérieur.
- Déterminer `scoreCalculated`.
- Déterminer `isX` avec `targetType.xRingEnabled` et `innerTenRadiusNormalized`.
- Déterminer `isNearCordon` comme assistance UX.
- Proposer `cordonZoneAbove` sans forcer la décision utilisateur.

Le moteur ne doit pas :

- Manipuler des pixels, tailles écran, `Canvas`, `Dp` ou `Offset` Compose.
- Lire ou écrire Room.
- Connaitre les écrans, ViewModels ou événements tactiles.
- Gérer CameraX, photos, OpenCV, IA ou reconnaissance automatique.

Une seconde API de domaine peut appliquer le mode de comptage :

```kotlin
fun resolveFinalScore(
    result: ScoreResult,
    scoringMode: ScoringMode,
    userSelectedScore: Int? = null
): FinalScoreDecision
```

Cette résolution doit garder explicite la différence entre `scoreCalculated` et `scoreFinal`.

---

## 8. Stratégie de tests

### Tests unitaires JVM prioritaires

À écrire avant tout écran Android :

- Score au centre exact.
- Chaque limite de zone : 10/9, 9/8, 8/7, jusqu'à 2/1.
- Valeurs juste à l'intérieur et juste à l'extérieur d'une limite.
- Miss pour `r > 1.0`.
- Bord extérieur `r = 1.0`.
- Zone X activée, désactivée et rayon configurable.
- Cordon proche d'une limite avec tolérance `0.015`.
- Modes `TRAINING`, `COMPETITION`, `MANUAL_CORDON`.
- `scoreCalculated == scoreFinal` par défaut en V1 hors décision cordon.
- `maxPossibleScore` en mode `FIXED` avec et sans `plannedEndCount`.
- `maxPossibleScore` en mode `FREE`.

### Tests domaine séance

- Somme d'une volée depuis `scoreFinal`.
- Somme d'une séance depuis les volées validées.
- Compteurs de 10, X et M.
- Passage `DRAFT -> IN_PROGRESS -> COMPLETED`.
- Passage `EDITING -> VALIDATED`.
- Mise à jour de `updatedAt` d'un impact, d'une volée et d'une séance.

### Tests data après Room

- Seed des cibles classiques.
- Contraintes uniques `(sessionId, index)` et `(endId, index)`.
- Suppression en cascade `Session -> End -> ArrowImpact`.
- `SET NULL` pour `Bow` et `ArrowSet`.
- Mapping Room vers domaine et domaine vers Room.

### Tests export/import

- Export JSON lisible et versionné.
- Import d'une entité nouvelle par UUID.
- Mise à jour d'une entité existante par UUID.
- Ignorer les `id` Room pendant la fusion.
- Résolution de conflit par `updatedAt`.
- Non-écrasement des `TargetType` built-in.
- Import d'une séance avec remapping `targetTypeUuid`, `bowUuid`, `arrowSetUuid`.

### Tests UI et terrain

Après le blason interactif :

- Conversion pixel -> coordonnées normalisées.
- Placement de 3 impacts en quelques secondes.
- Déplacement et suppression d'impact.
- Correction manuelle près du cordon.
- Lisibilité en salle, extérieur ombre et plein soleil.
- Reprise après verrouillage téléphone et changement d'application.

---

## 9. Limites hors V1

Hors V1, même si certains champs préparent l'avenir :

- Pas de reconnaissance automatique des flèches.
- Pas de prise de photo du blason.
- Pas de CameraX.
- Pas d'OpenCV.
- Pas de TFLite, ONNX ou modèle IA.
- Pas de module `vision-lab` dans le build Android V1.
- Pas de cloud sync, backend, Firebase ou compte utilisateur obligatoire.
- Pas de météo automatique.
- Pas d'iOS.
- Pas de cible campagne en production V1.
- Pas de statistiques avancées au-delà du résumé prévu : total, maximum, pourcentage, moyenne, 10, X, M.

---

## 10. Critère de passage au premier code Android

Le squelette Android ne doit être créé qu'après validation de ces points :

- `core:model` compile.
- `core:scoring` compile.
- Les tests unitaires du moteur de score passent.
- Les règles `TargetType`, `TargetZone`, X, miss, cordon et `maxPossibleScore` sont couvertes.
- Aucune dépendance Android n'est présente dans le moteur de score.

Le premier code Android doit ensuite rester minimal : un `app` Compose vide ou quasi vide, branché sur les modules domaine déjà testés, sans Room ni UI complexe tant que la frontière domaine/UI n'est pas stable.

---

*Document initial — 30 mai 2026*
