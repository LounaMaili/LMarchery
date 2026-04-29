# ADR 006 — OpenCV + IA + correction manuelle (approche hybride)

## Contexte

La reconnaissance automatique des flèches sur un blason est techniquement difficile : lumière variable, angle de prise de vue, blasons usés, flèches de couleurs différentes, anciens impacts. Aucune approche seule ne suffit.

## Décision

La reconnaissance utilisera une **approche hybride en trois couches** :

| Couche | Technologie | Rôle |
|--------|-------------|------|
| Géométrie et calibration | OpenCV | Détection du blason, correction de perspective, contours, couleurs, prétraitements |
| Détection des flèches | Modèle IA (TFLite/ONNX) | Reconnaissance des impacts dans des conditions réelles |
| Validation | Correction manuelle | L'utilisateur valide ou corrige les propositions |

OpenCV est utilisé comme **brique de vision géométrique**, pas comme solution complète. Le modèle IA détecte les impacts. L'utilisateur a toujours le dernier mot.

## Conséquences

- La reconnaissance est tolérante aux erreurs : l'utilisateur corrige toujours
- OpenCV peut être prototypé rapidement (notebooks Python)
- Le modèle IA sera entraîné sur des données réelles collectées par l'application
- Les corrections utilisateur alimentent le dataset pour améliorer le modèle
- L'approche est progressive : V2 (photo), V3 (OpenCV), V4 (IA)

## Alternatives écartées

- **OpenCV seul** : trop fragile en conditions variables. Ne gère pas bien les blasons usés, la lumière, les flèches différentes.
- **IA seule** : sans la calibration géométrique (OpenCV), l'IA ne peut pas correctement interpréter la position sur le blason.
- **API cloud de vision** : incompatible avec local-first et le fonctionnement hors réseau.
- **ML Kit seul** : conçu pour la classification d'objets standards, pas pour la détection fine de points d'impact.

---

*29 avril 2026*