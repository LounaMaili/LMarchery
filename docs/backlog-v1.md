# Backlog V1 — LMarchery

> Tickets de travail organisés par épic, directement actionnables pour le développement

---

## Epic 1 — Moteur de score

> Brique centrale. Doit être testable indépendamment de l'UI Android.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 1.1 | Créer l'entité `TargetType` (Room) | ☐ | Haute |
| 1.2 | Créer l'entité `TargetZone` (Room) | ☐ | Haute |
| 1.3 | Pré-remplir les types de cible par défaut (40, 60, 80, 122 cm) | ☐ | Haute |
| 1.4 | Implémenter `calculateScore(targetType, xNormalized, yNormalized)` | ☐ | Haute |
| 1.5 | Implémenter la détection du cordon (`isNearCordon`) | ☐ | Haute |
| 1.6 | Implémenter la zone X / inner 10 (`isX`) configurable via `TargetType` | ☐ | Moyenne |
| 1.7 | Implémenter le miss (`isMiss` quand r > 1.0) | ☐ | Haute |
| 1.8 | Tests unitaires : limites de zone (9↔10, 8↔9, etc.) | ☐ | Haute |
| 1.9 | Tests unitaires : cordon en mode entraînement / compétition / manuel | ☐ | Haute |
| 1.10 | Tests unitaires : miss et zone X | ☐ | Moyenne |

**Critère de sortie** : le moteur calcule correctement un score pour toute position d'impact, avec ou sans cordon, avec ou sans zone X.

---

## Epic 2 — Blason interactif

> Écran central de l'application. Valider par prototype Compose dès que possible.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 2.1 | Afficher un blason circulaire avec les zones colorées | ☐ | Haute |
| 2.2 | Toucher pour placer un impact (coordonnées normalisées) | ☐ | Haute |
| 2.3 | Afficher le score calculé en temps réel après placement | ☐ | Haute |
| 2.4 | Glisser pour déplacer un impact existant | ☐ | Haute |
| 2.5 | Appui long ou bouton pour supprimer un impact | ☐ | Haute |
| 2.6 | Tap sur impact pour voir/corriger le score (modal cordon) | ☐ | Haute |
| 2.7 | Afficher le nombre d'impacts placés vs attendus | ☐ | Moyenne |
| 2.8 | Zoom et panoramique sur le blason | ☐ | Basse |
| 2.9 | Afficher les impacts de la volée en cours | ☐ | Haute |
| 2.10 | Test terrain : saisie rapide entre deux volées réelles | ☐ | Haute |

**Critère de sortie** : un archer peut saisir 3 impacts en quelques secondes, les corriger, et voir le score.

---

## Epic 3 — Séances et sauvegarde

> Gestion du cycle de vie d'une séance de tir.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 3.1 | Créer l'entité `Session` (Room) avec status DRAFT/IN_PROGRESS/COMPLETED | ☐ | Haute |
| 3.2 | Créer l'entité `End` (Room) avec status EDITING/VALIDATED | ☐ | Haute |
| 3.3 | Créer l'entité `ArrowImpact` (Room) | ☐ | Haute |
| 3.4 | Écran « Créer une séance » (formulaire) | ☐ | Haute |
| 3.5 | Ajouter une volée à la séance en cours | ☐ | Haute |
| 3.6 | Valider une volée (EDITING → VALIDATED) | ☐ | Haute |
| 3.7 | Calcul et affichage du score total et du score max possible | ☐ | Haute |
| 3.8 | Terminer une séance (IN_PROGRESS → COMPLETED) | ☐ | Haute |
| 3.9 | Reprendre une séance en cours (DRAFT / IN_PROGRESS) | ☐ | Haute |
| 3.10 | Historique des séances (liste chronologique) | ☐ | Haute |
| 3.11 | Détail d'une séance archivée | ☐ | Moyenne |
| 3.12 | Supprimer une séance | ☐ | Basse |
| 3.13 | Résistance à l'interruption (verrouillage, changement d'appli) | ☐ | Haute |

**Critère de sortie** : l'application survit à une vraie séance d'entraînement du début à la fin.

---

## Epic 4 — Matériel et presets

> Enrichir le suivi et accélérer la création de séances.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 4.1 | Créer l'entité `Bow` (Room) | ☐ | Moyenne |
| 4.2 | Écran « Fiche arc » (nom, type, puissance, notes) | ☐ | Moyenne |
| 4.3 | Créer l'entité `ArrowSet` (Room) | ☐ | Moyenne |
| 4.4 | Écran « Fiche flèches » (nom, spine, longueur, notes) | ☐ | Moyenne |
| 4.5 | Association arc + flèches lors de la création de séance | ☐ | Moyenne |
| 4.6 | Créer l'entité `SessionPreset` (Room) | ☐ | Moyenne |
| 4.7 | Écran « Créer un preset » | ☐ | Moyenne |
| 4.8 | Écran « Choisir un preset » (sélection rapide) | ☐ | Moyenne |
| 4.9 | Préremplir les champs de séance depuis un preset | ☐ | Moyenne |
| 4.10 | Presets par défaut (salle 18m, extérieur 30m, 50m, 70m) | ☐ | Basse |

**Critère de sortie** : un utilisateur peut créer un preset et lancer une séance en 2 taps.

---

## Epic 5 — Export / Import

> Ne pas enfermer les données de l'utilisateur.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 5.1 | Définir le schéma JSON d'export (avec UUID) | ☐ | Haute |
| 5.2 | Implémenter l'export JSON (intent de partage Android) | ☐ | Haute |
| 5.3 | Implémenter l'import JSON (sélection de fichier) | ☐ | Haute |
| 5.4 | Gestion des doublons et fusion par UUID | ☐ | Moyenne |
| 5.5 | Écran Paramètres (section Export/Import) | ☐ | Moyenne |

**Critère de sortie** : l'utilisateur peut exporter vers un fichier et réimporter sur un autre appareil.

---

## Epic 6 — Paramètres et UX

> Finitions nécessaires pour une V1 propre.

| # | Ticket | Statut | Priorité |
|---|--------|--------|-----------|
| 6.1 | Écran d'accueil (séances récentes + bouton créer) | ☐ | Haute |
| 6.2 | Tableau des scores par volée | ☐ | Haute |
| 6.3 | Résumé de séance (score total, %, 10/X/M) | ☐ | Haute |
| 6.4 | Paramètres : mode de comptage par défaut | ☐ | Moyenne |
| 6.5 | Paramètres : règle du cordon par défaut | ☐ | Moyenne |
| 6.6 | Thème sombre (optionnel V1) | ☐ | Basse |

**Critère de sortie** : l'application est utilisable du premier lancement au stockage en historique sans blocage.

---

## Dépendances entre epics

```
Epic 1 (moteur de score)
  └──→ Epic 2 (blason interactif)
         └──→ Epic 3 (séances)
                ├──→ Epic 4 (matériel/presets)
                └──→ Epic 5 (export/import)
Epic 6 (paramètres/UX) — transversal
```

**Jalon 1** : Epic 1 + 2 terminés → placement + score + sauvegarde volée
**Jalon 2** : Epic 3 terminé → séance complète de bout en bout
**Jalon 3** : Epic 4 + 5 + 6 terminés → MVP V1

---

*Dernière mise à jour : 29 avril 2026*