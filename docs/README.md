# Documentation LMarchery

## Ordre de lecture recommandé

1. **[Cahier des charges V1](cahier-des-charges-v1.md)** — Objectif, périmètre, hors périmètre, parcours utilisateur, critères de réussite
2. **[Parcours utilisateur](parcours-utilisateur.md)** — Flux détaillés de l'application (saisie, correction, historique, export…)
3. **[Règles de score](regles-score.md)** — Calcul des scores, zones, cordon, modes de comptage
4. **[Modèle de données V1](modele-donnees-v1.md)** — Entités, relations, attributs, énumérations, export/import
5. **[Backlog V1](backlog-v1.md)** — Tickets de travail par épic,可直接 actionnable
6. **[Tests terrain V1](tests-terrain-v1.md)** — Grilles de test pour les premiers retours réels
7. **[Format d'export V1](export-format-v1.md)** — Schéma JSON, UUID, fusion multi-appareil
8. **[Roadmap reconnaissance](reconnaissance-roadmap.md)** — Trajectoire V2→V4 pour la reconnaissance d'image

## Décisions d'architecture (ADR)

| # | Titre | Fichier |
|---|-------|---------|
| 1 | Local-first | [adr/001-local-first.md](adr/001-local-first.md) |
| 2 | Coordonnées normalisées | [adr/002-coordonnees-normalisees.md](adr/002-coordonnees-normalisees.md) |
| 3 | V1 sans reconnaissance | [adr/003-v1-sans-reconnaissance.md](adr/003-v1-sans-reconnaissance.md) |
| 4 | Room / SQLite | [adr/004-room-sqlite.md](adr/004-room-sqlite.md) |
| 5 | Export / Import JSON | [adr/005-export-import-json.md](adr/005-export-import-json.md) |
| 6 | OpenCV + IA + correction manuelle | [adr/006-opencv-plus-ia-plus-correction.md](adr/006-opencv-plus-ia-plus-correction.md) |

---

*Dernière mise à jour : 29 avril 2026*