# Cahier des charges — LMarchery V1

> Application Android de gestion des scores au tir à l'arc  
> Version 1.0 — Saisie manuelle

---

## 1. Objectif de la V1

Créer une première version Android permettant à un archer de gérer ses séances de tir à l'arc en saisie manuelle, avec un blason interactif, un tableau de scores, un historique local et une base extensible pour les futures fonctions de reconnaissance.

La V1 ne doit pas impressionner par l'IA. Elle doit prouver que le scoring, la saisie et l'historique sont fiables.

---

## 2. Périmètre V1

### Fonctionnalités incluses

- Création d'une séance de tir
- Choix d'un type de cible classique
- Choix de la distance de tir
- Choix du nombre de flèches par volée (3, 6 ou libre)
- Saisie des impacts sur un blason interactif
- Calcul automatique du score depuis la position de l'impact
- Correction manuelle du score (règle du cordon, ajustement)
- Tableau des volées avec détail et totaux
- Score total de la séance
- Historique des séances passées
- Fiches simples arc / flèches
- Presets de séance (configurations prédéfinies)
- Export / import des données locales
- Mode de comptage paramétrable (entraînement / compétition / manuel)

### Modes de saisie

- **Manuel simple** : l'utilisateur place les impacts directement sur le blason interactif en touchant l'écran. C'est le mode par défaut et toujours disponible.

### Données

- 100 % local (base Room / SQLite)
- Aucun compte utilisateur obligatoire
- Export / import prévu dès le départ (JSON ou fichier compressé)
- Fonctionnement hors réseau garanti

---

## 3. Hors périmètre V1

Les éléments suivants sont **repoussés** (pas abandonnés) pour ne pas fragiliser la V1 :

- Reconnaissance automatique des flèches (IA / Computer Vision)
- Synchronisation cloud
- Compte utilisateur
- Météo automatique (API externe)
- Version iOS
- Cible campagne
- Statistiques avancées (graphiques, dispersion, comparaisons multi-critères)
- Partage social
- Photo du blason

---

## 4. Parcours utilisateur principal

```
L'utilisateur ouvre l'application
→ Crée une séance (ou choisit un preset)
→ Configure : distance, cible, nombre de flèches, arc, flèches
→ Démarre la séance
→ Pour chaque volée :
   ├── Place les impacts sur le blason interactif
   ├── Vérifie le tableau de scores
   └── Valide la volée
→ Termine la séance
→ Consulte le résumé dans l'historique
```

### Variantes

- **Depuis un preset** : l'utilisateur sélectionne un preset (ex. "Salle 18m — 3 flèches"), les champs sont préremplis, il lance directement.
- **Correction en cours de saisie** : l'utilisateur peut déplacer ou supprimer un impact, ou forcer le score d'un impact proche du cordon.

---

## 5. Règles de score V1

> Détail complet dans `regles-score.md`

- Cible classique uniquement en V1
- Score calculé selon la distance au centre (coordonnées normalisées)
- Distinction entre `scoreCalculated` (calcul automatique) et `scoreFinal` (validation utilisateur)
- Règle du cordon paramétrable :
  - **Entraînement simple** : score de la zone où se trouve l'impact
  - **Compétition** : si l'impact touche la ligne de la zone supérieure, score supérieur
  - **Décision manuelle** : l'application propose le score et l'utilisateur tranche
- Coordonnées normalisées : centre = (0, 0), bord extérieur = rayon 1.0

---

## 6. Modèle de données minimal

> Détail complet dans `modele-donnees-v1.md`

Entités principales :

| Entité | Rôle |
|--------|------|
| `Session` | Séance de tir |
| `End` | Volée (ensemble de flèches tirées) |
| `ArrowImpact` | Impact individuel d'une flèche |
| `TargetType` | Type de cible (règles officielles) |
| `TargetZone` | Zone de score d'un type de cible |
| `Bow` | Arc de l'utilisateur |
| `ArrowSet` | Jeu de flèches de l'utilisateur |
| `SessionPreset` | Configuration prédéfinie de séance |

L'entité `ArrowImpact` stocke les coordonnées normalisées (`xNormalized`, `yNormalized`), le score calculé et le score final, le mode de saisie, et le flag de décision manuelle sur le cordon.

---

## 7. Écrans V1

| # | Écran | Description |
|---|-------|-------------|
| 1 | Accueil | Liste des séances récentes, bouton créer séance |
| 2 | Créer séance | Formulaire : date, distance, cible, nb flèches, arc, flèches, mode comptage |
| 3 | Choisir un preset | Liste des presets enregistrés, sélection rapide |
| 4 | Saisie de volée | Blason interactif + tableau de la volée en cours |
| 5 | Blason interactif | Toucher pour placer, glisser pour déplacer, supprimer, forcer score |
| 6 | Tableau des scores | Récapitulatif volée par volée, score total |
| 7 | Résumé de séance | Score final, moyenne, infos session |
| 8 | Historique | Liste chronologique des séances passées |
| 9 | Détail séance | Consultation d'une séance archivée |
| 10 | Matériel | Fiches arcs et flèches |
| 11 | Paramètres | Export/import, préférences, règle du cordon par défaut |

---

## 8. Critères de réussite V1

- Créer une séance en **moins d'une minute**
- Saisir une volée **rapidement** (placement de 3 impacts en quelques secondes)
- Corriger un impact **sans difficulté** (glisser, supprimer, forcer le score)
- Consulter le total **clairement** après chaque volée
- Retrouver une séance passée dans l'historique
- Exporter ses données
- Utiliser l'application **sans réseau**

---

## 9. Tests terrain V1

> Les tests terrain commencent dès la phase 2 (blason interactif), pas uniquement en fin de projet.

### Test 1 — Blason interactif
- Rapidité de saisie entre deux volées
- Lisibilité en plein soleil
- Précision du toucher
- Usage avec gants, doigts froids, fatigue
- Correction d'un impact
- Suppression d'un impact

### Test 2 — Séance complète
- Créer une séance réelle de bout en bout
- Saisir plusieurs volées
- Consulter le total en temps réel
- Corriger une erreur en cours de séance
- Reprendre la séance après verrouillage du téléphone

### Test 3 — Historique et export
- Retrouver une séance passée
- Consulter le détail d'une séance archivée
- Exporter les données
- Importer les données sur un autre appareil

### Test 4 — Cas limites
- Séance interrompue (appel entrant, changement d'appli)
- Nombre libre de flèches par volée
- Score proche du cordon avec règle compétition
- Saisie en conditions de luminosité difficiles

---

## 10. Préparation des futures phases

Même si la V1 n'inclut pas de reconnaissance, elle prépare les fondations :

- **Coordonnées normalisées** des impacts (exploitables par la reconnaissance future)
- **Distinction `scoreCalculated` / `scoreFinal`** (mesure de la qualité de la reconnaissance)
- **`inputMode`** (manual / automatic / corrected) — prêt pour la reconnaissance
- **Architecture prévue** pour `PhotoReference` et `DetectionResult`
- **Export des données** utilisable pour constituer un futur dataset d'entraînement

---

## 11. Stack technique V1

| Composant | Technologie |
|-----------|-------------|
| Langage | Kotlin |
| UI | Jetpack Compose |
| Caméra | CameraX (V2+, pas en V1) |
| Base locale | Room (SQLite) |
| Architecture | Couches (UI → Domaine → Données) |
| Build | Gradle / Android Studio |

---

## 12. Jalons

### Jalon 1 — Cœur du projet
Sur un téléphone Android : placer 3 impacts sur un blason classique → obtenir le score calculé → corriger si besoin → sauvegarder la volée.

### Jalon 2 — Séance complète
Créer une séance de bout en bout, y compris historique et correction.

### Jalon 3 — MVP V1
Application complète V1 : matériel, presets, export/import, paramètres.

---

*Document version 1.0 — 29 avril 2026*