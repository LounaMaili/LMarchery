# ADR 005 — Export / Import JSON

## Contexte

L'application est 100% locale. L'utilisateur doit pouvoir sauvegarder ses données et les transférer sur un autre appareil sans dépendre d'un service cloud.

## Décision

L'export et l'import utilisent le format **JSON** avec des **UUID v4** comme identifiants stables.

- Chaque entité possède un `uuid` (UUID v4) pour la fusion et le dédoublonnage
- Le `id` local (Room) n'est pas utilisé comme clé de fusion
- L'export contient l'intégralité des données de l'utilisateur
- Le fichier a l'extension `.lmarchery.json`

## Conséquences

- Format lisible et debuggable
- Compatible avec l'intent de partage Android
- Les UUID garantissent des imports fiables sur plusieurs appareils
- Le format est extensible pour les futures entités (météo, photos, détections)
- L'utilisateur n'est pas enfermé dans l'application

## Alternatives écartées

- **Binaire sérialisé** : pas lisible, pas debuggable, fragile entre versions.
- **CSV** : pas adapté pour des données relationnelles imbriquées (sessions → ends → impacts).
- **SQLite dump** : trop bas niveau, dépendant du schéma interne, pas portable.
- **Protobuf** : performant mais pas lisible. Overkill pour le volume de données d'un archer.

---

*29 avril 2026*