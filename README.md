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
- [x] ADR (25 décisions techniques)
- [x] Moteur de score Kotlin/JVM
- [x] Domaine séance Kotlin/JVM
- [x] Squelette Android Compose
- [x] Premier parcours Compose / Room : création, saisie, validation, reprise et consultation
- [x] Historique chronologique, tableau des volées et résumé des scores validés
- [x] Sauvegardes JSON : export vérifié et import atomique sans écrasement
- [x] Fondation du catalogue arcs/flèches : modèles et SQLite 2, sans UI ; sauvegardes SAF V2
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
| [Format d'export V1](docs/export-format-v1.md) | JSON opérationnel avec UUID, décisions et traces ; import contrôlé |
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
  database/                 ← Room / SQLite, cibles Android et JVM, utilisé via le repository Android
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

Prérequis : **JDK 21 complet**, SDK Android **35** et Build Tools **34.0.0**.
Le [guide environnement et CI](docs/environnement-et-ci.md) décrit l'installation,
les variables, les rapports et la stratégie pour les PR empilées.

Commande de référence, également utilisée par GitHub Actions :

```bash
./scripts/dev-check.sh
```

Le script détecte un JDK 21 et le SDK installé sur Fedora, affiche les versions,
puis s'arrête au premier échec. Un `JAVA_HOME` explicite doit désigner un JDK 21.
Pour lancer les validations individuellement, préparer les variables :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew test
./gradlew :app:assembleDebug :core:database:assembleDebug
./gradlew :app:lintDebug :core:database:lintDebug
```

Le SDK est désigné par `ANDROID_HOME`. Les préférences Android utilisent
`ANDROID_USER_HOME`, isolé par défaut dans `/tmp/lmarchery-android-home` (ou sous
`TMPDIR`). `ANDROID_SDK_HOME` est une ancienne variable de préférences, pas le
chemin du SDK. En sandbox, définir aussi un `GRADLE_USER_HOME` accessible en écriture.

La V1 reste locale/offline : pas de permission réseau ni de caméra. Room permet
de créer, relire et mettre à jour une séance complète avec contrôle optimiste
et reconstruction validée. Compose utilise un ViewModel et un repository injectables ;
les séances sont redécouvertes dans SQLite après un arrêt complet du processus.
Les tests `:core:database:jvmTest` interrogent
SQLite natif sur disque, avec fermeture/réouverture ; ils sont inclus dans `test`.
Voir [ADR 016](docs/adr/016-persistance-locale-room.md) et
[ADR 017](docs/adr/017-mise-a-jour-seances-room.md) et
[ADR 018](docs/adr/018-integration-android-room.md). La préparation du build peut
nécessiter le réseau pour télécharger Gradle, le SDK et les dépendances.

Le workflow `.github/workflows/quality-gate.yml` est configuré pour les PR vers
toutes les branches, y compris les PR empilées. Sans module `:app`, il exécute
seulement les tests JVM. **Sa configuration ne prouve pas son exécution : attendre
un run GitHub réussi avant de déclarer la CI validée.**

## Premier parcours persistant sur téléphone

Choisir explicitement classique ou poulies, 40 cm/18 m ou 60 cm/25 m, puis créer
et démarrer. La saisie est limitée à trois flèches en entraînement. Attendre la
confirmation après chaque appui, valider la volée, puis en ouvrir une nouvelle.
Le total provisoire reste séparé du total des volées validées. Une volée partielle
bloque la clôture ; une volée vide peut rester dans une séance terminée.

Avant validation, sélectionner une flèche dans la liste puis « Repositionner »
permet de proposer une autre position, même avec trois flèches. L'aperçu reste
temporaire : annuler n'écrit rien, confirmer attend l'acquittement Room et conserve
l'ancienne position/décision dans les traces. Voir
[ADR 019](docs/adr/019-selection-repositionnement-android.md).

Depuis la même sélection, « Corriger le score » permet de choisir une véritable
limite et son côté, ou une « Correction exceptionnelle » avec valeur légale et
motif obligatoire. La géométrie reste intacte ; la proximité ne promeut jamais
le score. La décision manuelle, sa date UTC et sa limite ou son motif sont
enregistrés même si le score numérique ne change pas. Voir
[ADR 020](docs/adr/020-decisions-score-android.md). Les décisions successives à
position constante remplacent la décision courante : ce n'est pas un journal
complet. Un repositionnement ultérieur exige un motif et conserve la décision
manuelle invalidée dans sa trace.

Après relancement, ouvrir la séance depuis la liste pour retrouver ses impacts.
Une séance terminée reste en lecture seule. Les actions en échec sont conservées
pour une nouvelle tentative explicite ou une relecture avec confirmation d'abandon.
Les profils/configurations hors de ce premier parcours restent consultables.

L'APK debug est `app/build/outputs/apk/debug/app-debug.apk`. Suivre le
[protocole téléphone](docs/tests-terrain-v1.md#protocole-android-persistant-pr-16),
avec rotation et arrêt complet après deux impacts. **Les tests JVM, builds et lint
ne constituent pas des tests physiques sur téléphone.** Le
[protocole de repositionnement](docs/tests-terrain-v1.md#protocole-selection-et-repositionnement-pr-17)
distingue aussi automatisation ADB et essais au doigt. Le jugement et la correction
exceptionnelle sont décrits dans l’ADR 020. Glisser-déposer, suppressions et
matériel/presets restent hors de ce premier parcours.

## Historique et tableau des scores (PR 21)

L’accueil distingue les séances à reprendre, l’historique terminé et les entrées
nécessitant une intervention. Le tri utilise l’instant réel de la date de séance,
décroissant, puis l’UUID croissant ; les heures civiles et offsets d’origine restent
visibles. Les cartes affichent le score et le nombre de volées **validés**.

Dans une séance, le blason précède les informations secondaires. Le tableau donne
chaque flèche dans son ordre enregistré, M/X, le total et le statut provisoire ou
validé ; une volée vide reste explicitement vide. Choisir une ligne affiche son
blason, avec retour à la volée en cours. Une séance terminée reste sans commandes
d’édition. Consulter ne réécrit aucune donnée.

Le résumé utilise le calculateur métier : points, volées/flèches validées, 10, 9,
M, X si activé, moyennes (indisponibles sans données validées). Le maximum de
référence explique sa portée, sans pourcentage de réussite trompeur.
Import/export sont regroupés dans « Sauvegardes » sur l’accueil, après les séances.
Voir [ADR 023](docs/adr/023-historique-et-presentation-scores-android.md) et le
[protocole de consultation](docs/tests-terrain-v1.md#protocole-historique-et-scores-pr-21).

## Sauvegardes Android V2 et compatibilité V1

Depuis l’accueil, section « Sauvegardes », « Exporter la sauvegarde complète V2 »
prépare un seul instantané Room contenant tous les profils, séances, descendants,
arcs et jeux de flèches. Le matériel seul, les profils seuls ou une base vide sont
exportables. « Exporter V1 — sans catalogue matériel » conserve le format historique
(séances et profils associés ; une base sans séance reste refusée).

L’aller-retour JSON strict est vérifié avant le sélecteur Android ; le succès
exige écriture, fermeture et relecture exacte du document. Le message précise
version, comptes et octets. Le fichier en clair peut être personnel. Ne pas
utiliser un fichier non confirmé : il peut être incomplet. Aucune suppression ou
relance automatique. Le fournisseur doit autoriser la relecture.

« Importer une sauvegarde » lit au plus 16 MiB et distingue les versions.
V1 conserve son contrat historique ; l’absence de matériel ne vide jamais le
catalogue. V2 présente nouveaux/identiques/conflits séparément pour séances,
profils, arcs et flèches. Confirmer ajoute seulement l’absent dans une transaction
unique ; toute divergence bloque l’ensemble, sans remplacement ni réparation.
Une V2 vide ou identique donne une vérification sans écriture après confirmation.
Le fichier n’est pas rouvert à la confirmation. Un aperçu périmé exige une nouvelle
sélection. Un résultat SQL incertain exige une relecture, jamais un retry implicite.

Import, export et écritures de séance s’excluent. Résoudre les sauvegardes en
attente et fermer les corrections. Le candidat est conservé pendant la rotation,
pas après la mort du processus. Aucun écran de catalogue n’est encore ajouté.
Voir les [formats V1](docs/export-format-v1.md), [V2](docs/export-format-v2.md) et
l’[ADR 029](docs/adr/029-saf-android-archives-v2.md). Les essais SAF physiques
restent distincts des tests JVM/SQLite et de la compilation Android.

## Versions

- **V1** — Saisie manuelle : blason interactif, scores, séances, matériel, presets, export/import
- **V2** — Assistance visuelle : photo, correction perspective, détection blason
- **V3** — Reconnaissance semi-automatique : proposition impacts, validation utilisateur
- **V4** — Reconnaissance automatique avancée : détection fiable, modèle IA personnalisé

## Licence

© 2026 — Tous droits réservés. Code propriétaire, diffusion non autorisée sans accord écrit.
