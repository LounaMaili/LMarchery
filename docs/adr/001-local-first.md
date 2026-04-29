# ADR 001 — Local-first

## Contexte

LMarchery est une application de scoring au tir à l'arc, utilisée en salle et sur terrain, potentiellement sans réseau. L'archer doit pouvoir compter sur l'application pendant une compétition ou un entraînement, là où la connexion n'est pas garantie.

## Décision

La V1 est **100% locale**. Aucun compte utilisateur, aucune synchronisation cloud, aucune dépendance réseau pour le fonctionnement de base.

L'export/import en JSON permet la sauvegarde et le transfert des données.

## Conséquences

- L'application fonctionne intégralement hors ligne
- Aucun serveur à maintenir pour la V1
- Les données appartiennent à l'utilisateur (format ouvert)
- Le cloud sync pourra être ajouté en V2/V3 sans casser l'existant

## Alternatives écartées

- **Cloud-first** : nécessite un backend, un compte, du réseau. Trop risqué pour la V1.
- **Sync temps réel** : complexité élevée (conflits, réseau instable). Pas justifié pour un usage individuel.
- **Firebase** : ajoute une dépendance Google et un lock-in. Pas nécessaire pour une app locale.

---

*29 avril 2026*