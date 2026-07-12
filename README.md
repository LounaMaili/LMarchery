# 🎯 LMarchery

Application Android de gestion des scores au tir à l'arc.

## Philosophie

LMarchery est une application complète de suivi et d'analyse des scores au tir à l'arc, avec pour ambition une fonctionnalité de reconnaissance automatique des flèches via smartphone.

Le développement est progressif : **la V1 propose une saisie manuelle solide** avec un blason interactif, un tableau de scores et un historique. La reconnaissance automatique viendra enrichir l'application dans les versions ultérieures.

## État du projet

**V1 — socle local/offline** (en cours)

- [x] Cahier des charges V1
- [x] Règles de score
- [x] Modèle de données V1
- [x] Parcours utilisateur
- [x] Backlog V1
- [x] Tests terrain V1
- [x] Format d'export V1
- [x] Roadmap reconnaissance
- [x] ADR (6 décisions d'architecture)
- [x] Moteur de score Kotlin/JVM
- [x] Domaine séance Kotlin/JVM
- [x] Squelette Android Compose
- [ ] Blason interactif complet
- [ ] Premier test terrain

## Documentation

Voir [docs/README.md](docs/README.md) pour l'index complet et l'ordre de lecture.

| Document | Description |
| --- | --- |
| [Cahier des charges V1](docs/cahier-des-charges-v1.md) | Périmètre, parcours utilisateur, critères de réussite |
| [Règles de score](docs/regles-score.md) | Calcul des scores, zones, cordon, modes de comptage |
| [Modèle de données V1](docs/modele-donnees-v1.md) | Entités, relations, attributs, export/import |
| [Parcours utilisateur](docs/parcours-utilisateur.md) | 7 parcours détaillés de l'application |
| [Backlog V1](docs/backlog-v1.md) | 6 epics, ~40 tickets actionnables |
| [Tests terrain V1](docs/tests-terrain-v1.md) | 5 grilles de test pour les retours réels |
| [Format d'export V1](docs/export-format-v1.md) | Schéma JSON avec UUID, règles de fusion |
| [Roadmap reconnaissance](docs/reconnaissance-roadmap.md) | Trajectoire V2→V4 |

## Stack technique

| Composant | Technologie |
| --- | --- |
| Langage | Kotlin |
| UI | Jetpack Compose |
| Base locale | Room (SQLite) |
| Caméra | CameraX (V2+) |
| Reconnaissance | OpenCV + TFLite/ONNX (V3+) |
| Architecture | Couches (UI → Domaine → Données) |

## Structure prévue

```
docs/                       ← Documentation fonctionnelle
app/                        ← Application Android Compose
core/
  model/                    ← Modèles métier purs
  scoring/                  ← Moteur de score Kotlin/JVM
  database/                 ← Room, futur
feature/                    ← Fonctionnalités UI, futur
vision-lab/                 ← Laboratoire reconnaissance (Python)
  datasets/
  notebooks/
  opencv-tests/
  training/
  models/
  evaluation/
```

## Commandes de développement

```bash
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./scripts/dev-check.sh
```

La V1 reste locale/offline : pas de permission réseau, pas de caméra et pas de persistance tant que Room n'est pas introduit. En environnement sandbox, lancer les commandes avec un JDK 21 et un `GRADLE_USER_HOME` writable ; `./scripts/dev-check.sh` isole `ANDROID_SDK_HOME` dans `/tmp/lmarchery-android-home` par défaut et détecte `/tmp/android-sdk` si `ANDROID_HOME` n'est pas défini.

## Versions

- **V1** — Saisie manuelle : blason interactif, scores, séances, matériel, presets, export/import
- **V2** — Assistance visuelle : photo, correction perspective, détection blason
- **V3** — Reconnaissance semi-automatique : proposition impacts, validation utilisateur
- **V4** — Reconnaissance automatique avancée : détection fiable, modèle IA personnalisé

## Licence

© 2026 — Tous droits réservés. Code propriétaire, diffusion non autorisée sans accord écrit.
