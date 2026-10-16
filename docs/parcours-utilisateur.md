# Parcours utilisateur — LMarchery V1

> Description des parcours utilisateur principaux et alternatifs

---

## Parcours effectivement implémenté (PR 16 à 18)

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
   Avant validation, sélectionner une flèche depuis son bouton numéroté permet
   de la repositionner, y compris lorsque les trois flèches sont présentes.
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
Les suppressions, matériel/presets, export et autres modes
restent hors de ce parcours. Les données sont locales, sans export utilisateur.
Voir [ADR 018](adr/018-integration-android-room.md) et le
[protocole téléphone](tests-terrain-v1.md#protocole-android-persistant-pr-16).

### Repositionner avant validation (PR 17)

1. Ouvrir une séance en cours et sa volée en saisie. Choisir « Sélectionner
   flèche #… » dans la liste, même si des impacts se superposent sur le blason.
2. Choisir « Repositionner », puis toucher la nouvelle position. Ce mode n'ajoute
   aucune flèche. L'anneau orange conserve la position enregistrée ; la croix
   violette montre le candidat non enregistré. Les deux positions et scores sont
   affichés distinctement. Toucher à nouveau change seulement le candidat.
3. « Annuler le repositionnement » quitte sans écrire. Le retour demande de
   confirmer l'abandon avant de quitter. La validation, la clôture et le changement
   de volée attendent la fermeture de la sélection/correction.
4. Pour confirmer : saisir un motif si l'ancienne décision de score était manuelle
   (facultatif sinon), puis « Confirmer le repositionnement ». L'ancienne décision
   sera invalidée et conservée dans les traces ; le score reprend la nouvelle
   géométrie. Un point identique ou une horloge incohérente donne un diagnostic.
5. Attendre l'acquittement. Jusque-là, positions, scores et totaux enregistrés
   restent ceux du dernier succès. Après succès, fermer la sélection ou commencer
   un autre repositionnement. Une erreur conserve l'action selon la politique
   décrite plus haut ; aucun abandon silencieux ni retry avec une nouvelle base.

Une rotation conserve le candidat avec le ViewModel. Après arrêt complet du
processus, seule la dernière position réellement persistée revient. Une volée
validée ou une séance terminée reste non modifiable. Pas de glisser-déposer,
suppression dans ce parcours. La décision de score possède un parcours distinct ci-dessous.
Voir [ADR 019](adr/019-selection-repositionnement-android.md).

### Décider du score sans déplacer (PR 18)

1. Dans une séance en cours, sélectionner une flèche de la volée en saisie puis
   « Corriger le score ». Le blason reste en consultation. Score géométrique,
   score final enregistré et valeur proposée sont distingués.
2. Pour un jugement : choisir explicitement une limite suggérée puis « Côté
   intérieur » ou « Côté extérieur ». Chaque limite donne ses valeurs réelles,
   son type, son rayon et son écart radial. « Choisir une autre limite réelle du
   profil » donne accès aux autres frontières, même hors tolérance tactile.
   Une suggestion ne prouve jamais le contact physique du tube avec le trait.
3. Pour une correction exceptionnelle : choisir l'action secondaire correspondante,
   une valeur légale et un motif non blanc. Les quatre profils indoor n'offrent
   aucun X. Cette correction est distincte d'un jugement de trait.
4. « Annuler la décision de score » n'écrit rien avant soumission. « Confirmer
   la décision de score » attend l'acquittement Room avant de changer score et
   totaux affichés. UUID, coordonnées, création et traces restent identiques.
   La provenance courante et sa date UTC sont consultables dans la sélection.
   Même une confirmation conservant le score numérique reste manuelle.
5. Les erreurs, conflits, retry identiques, navigation et rotation suivent les
   protections décrites plus haut. Après mort du processus, seul le stocké revient.
   Le repositionnement ultérieur d'une décision manuelle exige un motif et en
   conserve l'invalidation. Aucune modification après validation/clôture.

Une nouvelle décision à position constante remplace la décision courante, sans
journal exhaustif des jugements successifs. Voir [ADR 020](adr/020-decisions-score-android.md).

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
