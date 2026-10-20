# ADR 032 — Intégration de la visée précise et du matériel Android

Statut : implémenté en branche d’intégration, PR en brouillon, essais physiques
sur la combinaison NON TESTÉS. Aucune fusion des PR sources ni de `main`.

## Sources et portée

La branche part de la tête de #30, `d7a916bf1bf21b187c2b2dba64460c0d803a53f1`,
et incorpore #22, `c9675a257e1459daf2577220b0c0d0bcd9b3ed6e`, sans déplacer leurs
branches. Elle rassemble les contrats des ADR [024](024-visee-tactile-precise-candidat.md),
[025](025-fondation-catalogue-materiel.md), [026](026-contrat-sauvegarde-json-v2.md),
[027](027-archives-room-v2-atomiques.md), [029](029-saf-android-archives-v2.md),
[030](030-catalogue-materiel-android.md) et [031](031-associations-materiel-seances.md).
L’[audit de #31](https://github.com/LounaMaili/LMarchery/blob/b2399834ecf6490d82fc433efee40fdfa6fd5379/docs/audit-integration-android-pr22-materiel.md)
sert de référence ; sa conception de séances personnalisables n’est pas intégrée.

Aucun changement de scoring, modèle, codec, DAO, migration, dépendance ou format.
Room reste en version 2 avec la migration 1→2 existante ; les archives restent
V1/V2 et le contrat de scoring 1. Aucune donnée du téléphone n’est utilisée.

## Composition des états et des commandes

Le contrôleur/ViewModel conserve les deux familles d’état temporaire : visée
active/candidat relâché/repositionnement, et choix de matériel à la création ou
révision d’associations. La disponibilité doit vérifier **toutes** les exclusions :

- `canNavigate` exige `ready`, aucune sélection d’impact, aucun éditeur
  d’associations et aucune proposition de visée.
- `canEdit` exclut l’éditeur d’associations, puis applique les restrictions
  historiques de statut, configuration et volée. Les gardes de visée existantes
  s’appliquent ensuite à l’ajout, validation, clôture et sélection.
- `canEditEquipment` dépend de `canNavigate` et d’une séance ouverte. Une séance
  terminée garde le seul droit de modifier ses associations, sans droit de score.
- `acquireExport` (V1 et V2) exclut les trois familles de candidats et un
  `PendingSave`. L’import et la route catalogue depuis l’accueil utilisent le
  même verrou. Un refus n’efface aucun candidat pour rendre le verrou disponible.

Le retour résout explicitement une sauvegarde non confirmée, une correction ou
une proposition précise. L’éditeur d’associations peut être annulé avant
soumission. Abandon explicite, relecture réussie et acquittement SQL nettoient les
états des deux parcours. Une erreur de sauvegarde garde `expected`, `proposed`,
UUID, horodatage et candidat exacts. Aucune relance sur une nouvelle baseline.

Les tokens et contextes séance/volée/impact de l’ADR 024 restent obligatoires.
Un callback ancien ne crée pas de flèche après une navigation, ne quitte pas
l’éditeur de matériel et n’annule pas une nouvelle visée. Le relâchement ne
persiste rien. Seule la confirmation alloue l’UUID et soumet la révision une fois.

## Conflits résolus

| Fichier | Résolution |
|---|---|
| `README.md` | Présentation de la visée et de SAF V2 conservée ; pas de compte d’ADR périmé |
| `LMarcheryApp.kt` | Launchers SAF racine et route catalogue conservés, avec panneau de visée et retour explicite |
| `SessionController.kt` | Refus SAF cumulés, nettoyages complets et conservation des deux candidats en cas d’échec |
| `SessionUiState.kt` | États et gardes composés, aucune substitution complète d’une version conflictuelle |

Le renderer, la loupe et le détecteur tactile sont repris de #22. Leur absence de
conflit textuel n’est pas une preuve ergonomique. Les retours humains antérieurs
sur #22 ne valident pas automatiquement cette combinaison.

## Preuves automatisées et limites

Dix tests croisés du contrôleur complètent les tests de #22 : exclusion réciproque,
annulation/navigation/catalogue, callbacks obsolètes, double confirmation,
acquittement retardé, conflit avec association concurrente, erreur/réessai exact,
interruption de coroutine, jugement/repositionnement/correction puis association,
et export confirmé V1/V2 sans collecte pendant un candidat. Les repositories de
ces tests sont des doubles contrôlés ; ils ne sont pas présentés comme SQLite.

Deux tests SQLite natifs supplémentaires repartent de la DDL/hash V1 figée et
peuplée, jamais d’une V2 dont le numéro aurait été abaissé :

1. Migration conservant les huit tables et octets V1, puis ajout confirmé,
   jugement, repositionnement motivé, correction exceptionnelle, association,
   validation/clôture, réouverture et restauration V1/V2 dans deux autres fichiers.
   Comparaison intégrale des dix tables pour la restauration V2 ; V1 conserve les
   références opaques sans inventer de matériel. La seconde séance initiale reste
   inchangée. Scores, X/M, UUID, dates et traces sont vérifiés.
2. Association concurrente par deux magasins, refus de la proposition obsolète,
   erreur SQL sur ajout d’une trace, égalité de toutes les lignes après refus,
   puis réessai explicite du couple exact et réouverture avec historique complet.

Les suites existantes restent exécutées : golden V1, bits Float/nanosecondes,
identités globales, reconstruction stricte, idempotence, rollback après INSERT
réel et perte d’acquittement après COMMIT réel. Les tests de gestes sont JVM ;
les previews Compose sont compilées, sans rendu ni essai humain revendiqué.

Les erreurs de fournisseur SAF, le dispatch Android, le multitouch, les rotations
réelles, le clavier et l’ergonomie de l’ensemble attendent le
[protocole Pixel 8 / Android 17](../validation-integration-visee-materiel.md).
Ne pas rétrograder une base Room 2 avec l’ancien APK #22 limité à Room 1.

## Fiabilité de la fixture de corruption en CI

Le premier run de la combinaison échoue dans `physical bow with missing registry`
(ancienne suite de lecture V2), sur la connexion SQL brute qui prépare une corruption
synthétique. Contrairement au helper `rows`, ce helper n’avait aucune attente de
verrou. Un nouveau test tient un vrai verrou écrivain et reproduit avant correction
`SQLITE_BUSY / database is locked` : la corruption échouait immédiatement, avant
que le lecteur d’archive puisse vérifier son diagnostic.

Le helper **de test uniquement** reçoit le même `busy_timeout = 5000` borné que les
autres fixtures. Le test exige que l’opération ne termine pas pendant le verrou,
relâche celui-ci, puis vérifie la corruption effective, son diagnostic exact et
l’absence de réparation. Aucun retry de suite, skip, délai global, changement
Room/DAO/transaction ou assouplissement d’assertion n’est ajouté. Treize cas sont
donc nouveaux pour l’intégration : dix Android et trois SQLite.
