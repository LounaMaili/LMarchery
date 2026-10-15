# Parcours utilisateur — LMarchery V1

> Description des parcours utilisateur principaux et alternatifs

---

## Parcours effectivement implémenté (PR 16)

Les parcours V1 détaillés plus bas restent la cible produit. Le premier parcours
Android livré est limité à :

1. Accueil : découverte SQLite et lecture validée de chaque séance, statut affiché ;
   les entrées illisibles restent visibles avec diagnostic.
2. Nouvelle séance : titre facultatif, choix explicite d'un des quatre profils
   indoor WA classique/poulies 40 cm/18 m ou 60 cm/25 m. Trois flèches, entraînement,
   date civile dans le fuseau du téléphone choisi à la création. La création est
   confirmée sur disque avant ouverture.
3. Toucher le blason : chaque impact est enregistré avant affichage comme durable.
   Pendant l'écriture, les commandes sont bloquées. Les coins hors cercle valent M.
4. Après trois impacts : valider la volée, puis créer la suivante. Le total de la
   volée en saisie est provisoire ; le total de séance ne compte que les validées.
5. Fermer totalement puis relancer : l'accueil redécouvre les séances sans pointeur
   de séance active. Ouvrir une séance retrouve aussi une volée partielle.
6. Terminer : interdit avec une volée éditable non vide, possible avec une volée
   vide qui reste enregistrée. La séance terminée est consultable en lecture seule.

Une erreur de sauvegarde conserve l'action tentée et le dernier état confirmé.
Une erreur technique permet de retenter exactement la même proposition. Un conflit
exige une action explicite : consulter la version stockée n'efface pas la proposition ;
« Abandonner l'action et recharger » prévient avant de l'abandonner. Aucune fusion
selon l'horodatage. Le bouton Retour demande aussi confirmation d'abandon si nécessaire.

La rotation conserve le ViewModel ; l'arrêt du processus conserve seulement ce qui
a réellement été committé dans Room. Aucun reset de démo ne supprime les séances.
Les corrections, déplacements, suppressions, matériel/presets, export et autres
modes restent hors de ce parcours. Les données sont locales, sans export utilisateur.
Voir [ADR 018](adr/018-integration-android-room.md) et le
[protocole téléphone](tests-terrain-v1.md#protocole-android-persistant-pr-16).

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