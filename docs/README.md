# Documentation LMarchery

## Ordre de lecture recommandé

1. **[Cahier des charges V1](cahier-des-charges-v1.md)** — Objectif, périmètre, hors périmètre, parcours utilisateur, critères de réussite
2. **[Parcours utilisateur](parcours-utilisateur.md)** — Flux détaillés de l'application (saisie, correction, historique, export…)
3. **[Règles de score](regles-score.md)** — Calcul des scores, zones, cordon, modes de comptage
4. **[Modèle de données V1](modele-donnees-v1.md)** — Entités, relations, attributs, énumérations, export/import
5. **[Backlog V1](backlog-v1.md)** — Tickets de travail par épic,可直接 actionnable
6. **[Tests terrain V1](tests-terrain-v1.md)** — Grilles de test pour les premiers retours réels
7. **[Contrat de sauvegarde V1](export-format-v1.md)** — JSON opérationnel, UUID, reconstruction, import contrôlé et limites ; [contrat V2 matériel](export-format-v2.md) avec stockage Room, catalogue et sauvegardes SAF Android sur la branche d'intégration
8. **[Roadmap reconnaissance](reconnaissance-roadmap.md)** — Trajectoire V2→V4 pour la reconnaissance d'image

Pour préparer le développement et les validations :
[Environnement Fedora et CI](environnement-et-ci.md).

## Décisions d'architecture (ADR)

| # | Titre | Fichier |
|---|-------|---------|
| 1 | Local-first | [adr/001-local-first.md](adr/001-local-first.md) |
| 2 | Coordonnées normalisées | [adr/002-coordonnees-normalisees.md](adr/002-coordonnees-normalisees.md) |
| 3 | V1 sans reconnaissance | [adr/003-v1-sans-reconnaissance.md](adr/003-v1-sans-reconnaissance.md) |
| 4 | Room / SQLite | [adr/004-room-sqlite.md](adr/004-room-sqlite.md) |
| 5 | Export / Import JSON | [adr/005-export-import-json.md](adr/005-export-import-json.md) |
| 6 | OpenCV + IA + correction manuelle | [adr/006-opencv-plus-ia-plus-correction.md](adr/006-opencv-plus-ia-plus-correction.md) |
| 7 | Validation locale et CI commune | [adr/007-validation-locale-et-ci.md](adr/007-validation-locale-et-ci.md) |
| 9 | Intégrité des entrées du scoring | [adr/009-integrite-entrees-scoring.md](adr/009-integrite-entrees-scoring.md) |
| 10 | Suggestions géométriques et décisions de cordon séparées | [adr/010-suggestions-limites-score.md](adr/010-suggestions-limites-score.md) |
| 11 | Décisions finales explicites et contextualisées | [adr/011-decisions-score-explicites.md](adr/011-decisions-score-explicites.md) |
| 12 | Repositionnement explicite des impacts | [adr/012-repositionnement-explicite-impacts.md](adr/012-repositionnement-explicite-impacts.md) |
| 13 | Profils indoor World Archery, édition 2026-01-27 | [adr/013-profils-indoor-world-archery.md](adr/013-profils-indoor-world-archery.md) |
| 14 | Instants UTC et date civile de séance | [adr/014-convention-temporelle-utc.md](adr/014-convention-temporelle-utc.md) |
| 15 | Sauvegarde versionnée et reconstruction contextualisée | [adr/015-sauvegarde-versionnee-reconstruction.md](adr/015-sauvegarde-versionnee-reconstruction.md) |
| 16 | Première persistance locale Room / SQLite | [adr/016-persistance-locale-room.md](adr/016-persistance-locale-room.md) |
| 17 | Mise à jour transactionnelle des séances Room | [adr/017-mise-a-jour-seances-room.md](adr/017-mise-a-jour-seances-room.md) |
| 18 | Premier parcours Android Compose / Room | [adr/018-integration-android-room.md](adr/018-integration-android-room.md) |
| 19 | Sélection et repositionnement dans Android | [adr/019-selection-repositionnement-android.md](adr/019-selection-repositionnement-android.md) |
| 20 | Jugement de limite et correction exceptionnelle dans Android | [adr/020-decisions-score-android.md](adr/020-decisions-score-android.md) |
| 21 | Export JSON versionné des séances Android | [adr/021-export-json-versionne-android.md](adr/021-export-json-versionne-android.md) |
| 22 | Import JSON contrôlé et transactionnel | [adr/022-import-json-controle-transactionnel.md](adr/022-import-json-controle-transactionnel.md) |
| 23 | Historique chronologique et présentation des scores Android | [adr/023-historique-et-presentation-scores-android.md](adr/023-historique-et-presentation-scores-android.md) |
| 24 | Visée tactile précise et candidat non destructif | [adr/024-visee-tactile-precise-candidat.md](adr/024-visee-tactile-precise-candidat.md) |
| 25 | Fondation du catalogue matériel et migration SQLite 1→2 | [adr/025-fondation-catalogue-materiel.md](adr/025-fondation-catalogue-materiel.md) |
| 26 | Contrat JSON V2 matériel et lecture compatible V1 | [adr/026-contrat-sauvegarde-json-v2.md](adr/026-contrat-sauvegarde-json-v2.md) |
| 27 | Archives Room V2 atomiques | [adr/027-archives-room-v2-atomiques.md](adr/027-archives-room-v2-atomiques.md) |
| 29 | Sauvegardes SAF Android V2 | [adr/029-saf-android-archives-v2.md](adr/029-saf-android-archives-v2.md) |
| 30 | Catalogue matériel Android | [adr/030-catalogue-materiel-android.md](adr/030-catalogue-materiel-android.md) |
| 31 | Associations matériel/séances | [adr/031-associations-materiel-seances.md](adr/031-associations-materiel-seances.md) |
| 32 | Intégration visée précise et matériel | [adr/032-integration-visee-materiel.md](adr/032-integration-visee-materiel.md) |
| 33 | Fondation des configurations par volée | [adr/033-fondation-configurations-par-volee.md](adr/033-fondation-configurations-par-volee.md) |
| 34 | Scoring par volée et statistiques mixtes | [adr/034-scoring-par-volee-statistiques-mixtes.md](adr/034-scoring-par-volee-statistiques-mixtes.md) |
| 35 | Agrégat des séances à configurations mixtes | [adr/035-agregat-seances-configurations-mixtes.md](adr/035-agregat-seances-configurations-mixtes.md) |
| 36 | Réconciliation Android et domaine configurable | [adr/036-reconciliation-android-configurations.md](adr/036-reconciliation-android-configurations.md) |
| 37 | Contrat métier d’archive V3 des séances configurables | [adr/037-contrat-archive-seances-configurables-v3.md](adr/037-contrat-archive-seances-configurables-v3.md) |
| 38 | Codec JSON V3 strict et autonome | [adr/038-codec-json-v3-strict.md](adr/038-codec-json-v3-strict.md) |
| 39 | Migration Room 2→3 et lecture des séances configurables | [adr/039-room-v3-migration-lecture.md](adr/039-room-v3-migration-lecture.md) |
| 40 | Écritures transactionnelles des séances configurables Room V3 | [adr/040-ecritures-room-v3-configurables.md](adr/040-ecritures-room-v3-configurables.md) |

Protocole de la combinaison : [Pixel 8 / Android 17](validation-integration-visee-materiel.md).

---

*Dernière mise à jour : 20 octobre 2026*
