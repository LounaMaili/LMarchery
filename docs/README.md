# Documentation LMarchery

## Ordre de lecture recommandé

1. **[Cahier des charges V1](cahier-des-charges-v1.md)** — Objectif, périmètre, hors périmètre, parcours utilisateur, critères de réussite
2. **[Parcours utilisateur](parcours-utilisateur.md)** — Flux détaillés de l'application (saisie, correction, historique, export…)
3. **[Règles de score](regles-score.md)** — Calcul des scores, zones, cordon, modes de comptage
4. **[Modèle de données V1](modele-donnees-v1.md)** — Entités, relations, attributs, énumérations, export/import
5. **[Backlog V1](backlog-v1.md)** — Tickets de travail par épic,可直接 actionnable
6. **[Tests terrain V1](tests-terrain-v1.md)** — Grilles de test pour les premiers retours réels
7. **[Contrat de sauvegarde V1](export-format-v1.md)** — JSON opérationnel, UUID, reconstruction et limites ; sans import
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

---

*Dernière mise à jour : 15 octobre 2026*
