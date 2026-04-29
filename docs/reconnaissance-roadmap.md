# Roadmap reconnaissance — LMarchery

> Trajectoire progressive pour la reconnaissance d'image, de la photo simple à la détection automatique

---

## Philosophie

La reconnaissance n'est pas la V1. Elle viendra enrichir une application déjà utile en saisie manuelle. Chaque version apporte une valeur utilisateur immédiate, même si la reconnaissance n'est pas parfaite.

---

## V2 — Photo simple (assistance visuelle)

> Objectif : rendre la saisie plus confortable grâce à la photo

### Fonctionnalités

- Prise de photo du blason depuis l'application (CameraX)
- Import d'une photo depuis la galerie
- Recadrage manuel de la zone du blason
- Affichage du blason redressé
- Placement manuel des impacts sur la photo
- Association photo ↔ volée

### Pas de reconnaissance automatique à ce stade

L'utilisateur prend une photo, l'application l'affiche, l'utilisateur place les impacts à la main sur la photo. C'est déjà une aide visuelle.

### Technique

- CameraX en mode ImageCapture
- Canvas Compose pour le placement sur photo
- Stockage de la photo dans le répertoire applicatif
- Entité `PhotoReference` ajoutée au modèle

---

## V3 — Assistance OpenCV (reconnaissance semi-automatique)

> Objectif : l'application propose les positions, l'utilisateur valide ou corrige

### Fonctionnalités

- Détection automatique du blason (contour, centre, rayon)
- Correction de perspective (homographie)
- Calibration des zones de score sur la photo
- Mode avant/après : comparaison de deux photos pour détecter les nouveaux impacts
- Proposition automatique des positions d'impacts
- Validation/correction par l'utilisateur
- Mode de secours : l'utilisateur ajuste le centre et le rayon manuellement

### Technique

- OpenCV Android SDK pour le traitement géométrique
- Détection de cercles/ellipses (HoughCircles)
- Détection de couleurs (HSV pour cible classique)
- Homographie pour la correction de perspective
- Soustraction d'images pour le mode avant/après

### Données collectées

Chaque correction utilisateur est enregistrée :
- Position proposée par l'algorithme
- Position corrigée par l'utilisateur
- Photo associée
- Type de cible
- Conditions

Ces données alimentent le futur dataset d'entraînement.

### Entité ajoutée

- `DetectionResult` : positionProposed, positionCorrected, confidence, wasUserCorrected

---

## V4 — Modèle IA (reconnaissance automatique avancée)

> Objectif : détection fiable des flèches dans des conditions variées

### Fonctionnalités

- Détection des impacts/flèches par modèle IA
- Calcul automatique du score
- Gestion de blasons usés, lumière variable, flèches différentes
- Amélioration continue via les corrections utilisateur

### Approche hybride

| Composant | Rôle |
|-----------|------|
| OpenCV | Traitement géométrique, calibration, correction de perspective, prétraitements |
| Modèle IA | Détection des flèches/pointes dans des conditions réelles |
| Correction manuelle | Validation, correction, enrichissement du dataset |

### Technique

- Modèle de détection entraîné sur le dataset LMarchery
- Formats mobiles : TensorFlow Lite (LiteRT) ou ONNX Runtime Mobile
- Pipeline : collecte → annotation → entraînement (Python) → export → intégration Android

### Pipeline de développement

```
vision-lab/
├── datasets/
│   ├── raw/                ← Photos brutes collectées
│   ├── annotated/          ← Photos annotées (centre, zones, impacts)
│   └── exports/            ← Jeux de données formatés
├── notebooks/              ← Expérimentations Python
├── opencv-tests/           ← Tests et prototypes OpenCV
├── training/               ← Scripts d'entraînement
├── models/
│   ├── pytorch/            ← Modèles PyTorch
│   ├── onnx/               ← Export ONNX
│   └── tflite/             ← Export TFLite pour Android
├── evaluation/             ← Métriques et comparaisons
└── README.md
```

---

## Dataset — Étapes de constitution

### Phase expérimentale (V2-V3)

- Photos de blasons dans des conditions variées
- Annotations manuelles : centre, rayon, zones, positions d'impacts
- Corrections utilisateur enregistrées comme données d'entraînement

### Phase d'entraînement (V4)

- Annotation systématique avec un outil dédié
- Catégories à collecter :
  - Cibles neuves / cibles usées
  - Salle / extérieur
  - Soleil / ombre / pluie
  - Angles et distances différents
  - Flèches carbone / aluminium
  - Couleurs de tubes différentes
  - Plumes visibles ou non
  - Impacts groupés / isolés
  - Proches du centre / proches des cordons

### Cible de précision V4

- **V4-alpha** : au moins 80% des flèches détectées correctement en conditions simples
- **V4-beta** : reconnaissance fiable en conditions variées
- **Critère de réussite** : l'utilisateur gagne du temps même s'il doit corriger

---

## Ce qui est exclu de chaque version

| Version | Inclut | Exclut |
|---------|--------|--------|
| V1 | Saisie manuelle, blason interactif, séances, matériel, presets, export | Photo, reconnaissance, cloud, iOS, météo auto |
| V2 | Photo, placement manuel sur photo | Détection automatique |
| V3 | Détection blason, correction perspective, avant/après | Modèle IA |
| V4 | Modèle IA, détection fiable | Cloud sync |

---

*Dernière mise à jour : 29 avril 2026*