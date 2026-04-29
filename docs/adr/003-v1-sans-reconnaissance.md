# ADR 003 — V1 sans reconnaissance

## Contexte

La fonctionnalité la plus ambitieuse du projet est la reconnaissance automatique des flèches via la caméra. C'est aussi la plus risquée techniquement (conditions variables, lumière, blasons usés, flèches différentes).

## Décision

La **V1 ne contient aucune reconnaissance automatique**. Elle se concentre sur la saisie manuelle, le blason interactif, les scores, l'historique et le matériel.

Le modèle de données est conçu pour accueillir la reconnaissance future (champs `inputMode`, `scoreCalculated` vs `scoreFinal`, entités `PhotoReference` et `DetectionResult` documentées), mais aucune fonction de reconnaissance n'est implémentée en V1.

## Conséquences

- La V1 est plus rapide à développer et plus fiable
- L'application est utile même si la reconnaissance n'arrive jamais
- La correction manuelle est une brique centrale du futur système IA, pas un pis-aller
- Les corrections utilisateur en V1+ alimenteront le dataset d'entraînement

## Alternatives écartées

- **Reconnaissance dès la V1** : trop risqué. Allonge les délais, fragilise l'ensemble.
- **OpenCV seul comme reconnaissance** : fragile en conditions réelles. Mieux vaut une approche hybride (OpenCV + IA + correction).
- **API cloud pour la reconnaissance** : incompatible avec le fonctionnement local-first.

---

*29 avril 2026*