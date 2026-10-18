# Parcours utilisateur — LMarchery V1

> Description des parcours utilisateur principaux et alternatifs

---

## Parcours effectivement implémenté (PR 16 à 21)

Les parcours V1 détaillés plus bas restent la cible produit. Le premier parcours
Android livré est limité à :

1. Accueil : séances à reprendre (brouillons/en cours), historique terminé et
   entrées nécessitant une intervention avec UUID/diagnostic. Chaque section lisible
   est triée par date de séance réelle décroissante (`toInstant()`), puis UUID
   croissant. Les cartes affichent date civile/fuseau/offset, profil, distance,
   statut, volées et points validés. « Reprendre » ou « Consulter » ouvre la séance.
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
Les suppressions, matériel/presets et autres modes restent hors de ce parcours.
Les données sont locales ; l’export global est décrit ci-dessous.
Voir [ADR 018](adr/018-integration-android-room.md) et le
[protocole téléphone](tests-terrain-v1.md#protocole-android-persistant-pr-16).

### Consulter les scores et l’historique (PR 21)

Le tableau distinct du blason affiche chaque volée, son statut EDITING/VALIDATED,
ses flèches dans l’ordre conservé, M/X et son total provisoire ou validé. Une volée
vide n’est pas un zéro validé ; trois flèches ne valident jamais automatiquement.
« Voir le blason » sélectionne par UUID ; « Revenir à la volée en cours » retrouve
la saisie après consultation d’une ancienne volée. Les volées validées et séances
terminées restent non modifiables ; les configurations non éditables restent
consultables. Aucune écriture n’est demandée par cette navigation.

Le résumé reprend `summarizeSessionScore` : total, volées/flèches validées, nombres
de 10, 9, M et X si activé, moyennes par flèche/volée. Sans données validées, les
moyennes sont indisponibles. Le maximum de référence indique s’il porte sur les
volées prévues/enregistrées (même vides ou provisoires), ou sur les flèches
enregistrées en mode libre ; aucun pourcentage de réussite n’est affiché.

La saisie présente titre/statut, scores puis blason et commandes avant les détails.
La rotation conserve la volée consultée et le défilement ; un acquittement de
sauvegarde ne réinitialise pas le défilement. Les diagnostics et PendingSave restent
accessibles, avec les mêmes confirmations d’abandon. Les panneaux import/export
restent dépliés sous « Sauvegardes » sur l’accueil, avec incidents et confirmations.
Voir [ADR 023](adr/023-historique-et-presentation-scores-android.md).

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

## 5. Parcours — Exporter ses données (PR 19)

1. Depuis l’accueil, section « Sauvegardes », choisir « Exporter la sauvegarde complète V2 » (ou « Exporter V1 — sans catalogue matériel »). Si une sauvegarde est en cours/non confirmée
   ou une correction ouverte, terminer ou résoudre cette action d’abord.
2. Attendre préparation puis validation : V2 lit tous les profils, séances et
   fiches matériel en une transaction et vérifie l’aller-retour JSON. Une base
   vide est exportable en V2 ; V1 exige une séance. Toute donnée illisible bloque
   l’ensemble ; aucun export partiel présenté comme complet.
3. Choisir la destination dans le sélecteur Android. Le nom proposé se termine
   par `.lmarchery.json`. L’annulation n’écrit aucun contenu.
4. Attendre écriture, fermeture et relecture. Le succès donne nombre de séances,
   nom si disponible et taille. Le fichier est en clair, potentiellement personnel.
5. En cas d’échec/interruption, ne pas utiliser le document potentiellement incomplet.
   Une nouvelle tentative prépare un nouveau choix ; aucune suppression automatique.
   Un fournisseur refusant la relecture ne permet pas de confirmer la sauvegarde.

Les séances en cours sont incluses ; aucune donnée Room n’est modifiée. La
rotation conserve la préparation du ViewModel, pas la mort du processus. L’import
contrôlé ci-dessous ajoute uniquement les absentes ; aucune fusion par UUID ou `updatedAt`.
Voir [ADR 021](adr/021-export-json-versionne-android.md).

---

## Importer une sauvegarde (PR 20)

1. Revenir à la liste, résoudre toute sauvegarde non confirmée et fermer une
   sélection/correction. Aucun export ou autre import ne doit être actif.
2. Dans la section « Sauvegardes », choisir « Importer une sauvegarde », puis sélectionner explicitement le fichier
   dans Android. Annuler ne demande aucune écriture. Nom/MIME ne prouvent rien :
   octets lus avec plafond de 16 MiB, décodage strict puis comparaison transactionnelle.
3. Lire la version et les comptes : V2 sépare séances, profils, arcs et flèches,
   chacun en nouveaux/identiques/conflits. V1 conserve son aperçu de séances. Toute
   divergence/collision bloque l’ensemble avec UUID et diagnostic. Aucun choix
   d’écrasement. V1 refuse une archive vide ou un profil non référencé ; V2 accepte
   matériel seul, profils seuls ou une archive vide explicite sans effacement.
4. Fermer l’aperçu sans importer, ou confirmer l’import de la version affichée.
   Si tout existe à l’identique, aucune importation n’est nécessaire. Le fichier
   n’est jamais rouvert silencieusement à la confirmation.
5. Attendre l’acquittement SQL. Le plan est recontrôlé sous transaction écrivain ;
   aucune insertion partielle. Après succès, la liste est redécouverte et relue.
   Les séances terminées restent en lecture seule.
6. Un plan périmé exige une nouvelle sélection. Après erreur/interruption SQL non
   confirmée, relire et sélectionner à nouveau le fichier : les séances committées
   sont reconnues identiques, sans doublon. Aucun retry automatique. La rotation
   conserve le candidat ; la mort du processus exige une nouvelle sélection.

V1 ne fournit pas de matériel et ne modifie pas le catalogue. V2 restaure aussi les
fiches et profils non référencés. Pas de fusion, suppression ni résolution par date.
Voir [ADR 022](adr/022-import-json-controle-transactionnel.md) et
[ADR 029](adr/029-saf-android-archives-v2.md).

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
