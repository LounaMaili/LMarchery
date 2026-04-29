# 🎯 LMarchery

Application Android de gestion des scores au tir à l'arc.

## Philosophie

LMarchery est une application complète de suivi et d'analyse des scores au tir à l'arc, avec pour ambition une fonctionnalité de reconnaissance automatique des flèches via smartphone.

Le développement est progressif : **la V1 propose une saisie manuelle solide** avec un blason interactif, un tableau de scores et un historique. La reconnaissance automatique viendra enrichir l'application dans les versions ultérieures.

## État du projet

**Phase 0 — Cadrage fonctionnel** (en cours)

- [x] Cahier des charges V1
- [x] Règles de score
- [x] Modèle de données V1
- [ ] Wireframes / maquettes
- [ ] Moteur de score
- [ ] Blason interactif
- [ ] Premier test terrain

## Documentation

| Document | Description |
|----------|-------------|
| [Cahier des charges V1](docs/cahier-des-charges-v1.md) | Périmètre, parcours utilisateur, critères de réussite |
| [Règles de score](docs/regles-score.md) | Calcul des scores, zones, cordon, modes de comptage |
| [Modèle de données V1](docs/modele-donnees-v1.md) | Entités, relations, attributs, export/import |

## Stack technique

| Composant | Technologie |
|-----------|-------------|
| Langage | Kotlin |
| UI | Jetpack Compose |
| Base locale | Room (SQLite) |
| Caméra | CameraX (V2+) |
| Reconnaissance | OpenCV + TFLite/ONNX (V3+) |
| Architecture | Couches (UI → Domaine → Données) |

## Structure prévue

```
docs/                       ← Documentation fonctionnelle
android/                    ← Application Android
  app/
  core/
    model/
    scoring/
    database/
    vision/
  feature/
    sessions/
    scoring/
    equipment/
    presets/
    statistics/
    camera/
vision-lab/                 ← Laboratoire reconnaissance (Python)
  datasets/
  notebooks/
  opencv-tests/
  training/
  models/
  evaluation/
```

## Versions

- **V1** — Saisie manuelle : blason interactif, scores, séances, matériel, presets, export/import
- **V2** — Assistance visuelle : photo, correction perspective, détection blason
- **V3** — Reconnaissance semi-automatique : proposition impacts, validation utilisateur
- **V4** — Reconnaissance automatique avancée : détection fiable, modèle IA personnalisé

## Licence

© 2026 — Tous droits réservés. Code propriétaire, diffusion non autorisée sans accord écrit.