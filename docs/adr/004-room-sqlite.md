# ADR 004 — Room / SQLite

## Contexte

L'application a besoin de stocker localement des données structurées : séances, volées, impacts, types de cible, arcs, flèches, presets. Il faut une base de données relationnelle avec des relations entre entités.

## Décision

Utiliser **Room** (couche d'abstraction au-dessus de SQLite) comme base de données locale.

## Conséquences

- Room est la solution recommandée par Android pour les données structurées locales
- Gestion des migrations intégrée pour les évolutions du schéma
- Support des relations, des clés étrangères et des index
- Observabilité via Flow/LiveData pour l'UI réactive
- Compatible avec l'approche offline-first

## Alternatives écartées

- **SQLite brut** : trop verbeux, pas de type safety, pas de observabilité intégrée.
- **SharedPreferences / DataStore** : adapté pour les préférences, pas pour des données relationnelles complexes.
- **Realm** : solution valide mais moins standard dans l'écosystème Android que Room.
- **Firebase Firestore** : cloud, incompatible avec local-first.

---

*29 avril 2026*