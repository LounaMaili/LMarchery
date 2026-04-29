# ADR 002 — Coordonnées normalisées

## Contexte

Les impacts de flèches doivent être stockés et affichés de manière indépendante de la taille d'affichage, du type de blason, et de la résolution de l'écran ou de la photo.

## Décision

Les positions d'impacts sont stockées en **coordonnées normalisées** avec le convention suivante :
- Centre du blason = `(0.0, 0.0)`
- Bord extérieur du blason = rayon `1.0`
- Distance au centre = `sqrt(x² + y²)`

## Conséquences

- Les impacts sont exploitables quelle que soit la taille d'affichage
- Le score se calcule par simple comparaison de la distance au centre avec les rayons des zones
- Les futures photos (V2+) pourront mapper les pixels vers ces coordonnées via calibration
- Les statistiques de groupement et dispersion sont possibles sans conversion
- Le même système de coordonnées fonctionne pour la saisie manuelle et la reconnaissance automatique

## Alternatives écartées

- **Pixels absolus** : dépend de la taille d'affichage, inutilisable entre appareils.
- **Pourcentage (0-100)** : fonctionnel mais moins naturel pour les calculs de distance.
- **Coordonnées géographiques sur la cible** : surcomplexe pour le besoin.

---

*29 avril 2026*