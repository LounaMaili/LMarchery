# Parcours utilisateur — LMarchery V1

> Description des parcours utilisateur principaux et alternatifs

---

## 1. Parcours principal — Saisie d'une séance complète

```
L'utilisateur ouvre l'application
├── Écran d'accueil
│   ├── Liste des séances récentes
│   └── Bouton "Nouvelle séance"
│
├── Créer une séance
│   ├── Choisir un preset (optionnel)
│   ├── Ou configurer manuellement :
│   │   ├── Date (défaut : aujourd'hui)
│   │   ├── Type de cible (classique 40/60/80/122 cm)
│   │   ├── Distance (m)
│   │   ├── Nombre de flèches par volée (3, 6 ou libre)
│   │   ├── Intérieur / Extérieur
│   │   ├── Mode de comptage (entraînement / compétition / manuel)
│   │   ├── Arc utilisé (optionnel)
│   │   ├── Flèches utilisées (optionnel)
│   │   └── Notes (optionnel)
│   └── Bouton "Démarrer"
│
├── Saisie d'une volée
│   ├── Blason interactif
│   │   ├── Toucher pour placer un impact
│   │   ├── Glisser pour déplacer un impact
│   │   ├── Appui long pour supprimer un impact
│   │   ├── Tap sur un impact pour voir/corriger son score
│   │   ├── Zones colorées affichées
│   │   └── Tableau des flèches à côté
│   │
│   ├── Tableau de la volée
│   │   ├── Liste des flèches avec score
│   │   ├── Score total de la volée
│   │   └── Possibilité de corriger un score (cordon)
│   │
│   └── Bouton "Valider la volée"
│       └── Passage à la volée suivante
│
├── Résumé de séance
│   ├── Score total
│   ├── Score maximum possible
│   ├── Pourcentage
│   ├── Nombre de 10 / X / M
│   ├── Tableau récapitulatif des volées
│   └── Bouton "Terminer la séance"
│
└── Historique
    └── Séance enregistrée
```

---

## 2. Parcours — Utiliser un preset

```
L'utilisateur ouvre l'application
├── Appui sur "Nouvelle séance"
├── Sélection d'un preset existant (ex. "Salle 18m — 3 flèches")
│   └── Tous les champs sont préremplis
├── L'utilisateur peut modifier un champ si besoin
└── Bouton "Démarrer"
```

---

## 3. Parcours — Corriger un impact

```
Pendant la saisie d'une volée :
├── L'utilisateur voit un impact proche du cordon
├── Mode entraînement simple :
│   └── Score affiché = score de la zone
├── Mode compétition :
│   └── L'application propose le score supérieur
├── Mode manuel :
│   └── L'application affiche les deux scores possibles
│       └── L'utilisateur choisit
└── L'impact est marqué comme décision manuelle sur le cordon
```

---

## 4. Parcours — Consulter l'historique

```
L'utilisateur ouvre l'application
├── Liste des séances récentes sur l'accueil
├── Tap sur une séance
│   ├── Détail de la séance
│   │   ├── Date, distance, cible, arc, flèches
│   │   ├── Score total et pourcentage
│   │   ├── Tableau des volées
│   │   └── Blasons de chaque volée (consultation seule)
│   └── Possibilité de supprimer la séance
```

---

## 5. Parcours — Exporter ses données

```
L'utilisateur va dans Paramètres
├── Section "Export / Import"
├── Bouton "Exporter"
│   └── L'application génère un fichier JSON
│       └── Partage via intent Android (fichier, email, cloud…)
└── Bouton "Importer"
    └── L'utilisateur sélectionne un fichier JSON
        └── Les données sont fusionnées (gestion des doublons)
```

---

## 6. Parcours — Gérer le matériel

```
L'utilisateur va dans la section "Matériel"
├── Liste des arcs
│   ├── Ajouter un arc (nom, type, puissance, notes)
│   └── Modifier / Supprimer un arc
├── Liste des jeux de flèches
│   ├── Ajouter un jeu (nom, spine, longueur, notes)
│   └── Modifier / Supprimer un jeu
└── Associés aux séances lors de la création
```

---

## 7. Parcours — Créer un preset

```
L'utilisateur va dans les Presets (ou depuis la création de séance)
├── Bouton "Nouveau preset"
├── Configuration :
│   ├── Nom du preset
│   ├── Type de cible
│   ├── Distance
│   ├── Nombre de flèches par volée
│   ├── Intérieur / Extérieur
│   ├── Mode de comptage
│   ├── Arc par défaut
│   └── Flèches par défaut
├── Bouton "Enregistrer"
└── Le preset apparaît dans la liste de sélection rapide
```

---

*Document version 1.0 — 29 avril 2026*