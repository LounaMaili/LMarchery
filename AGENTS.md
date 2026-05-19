# AGENTS.md - LMarchery

## Contexte du projet

LMarchery est une application Android de gestion des scores au tir à l'arc. Le dépôt est actuellement en phase de cadrage fonctionnel : il contient la documentation de référence, mais pas encore le squelette Android.

Stack prévue :

- Langage : Kotlin.
- UI : Jetpack Compose.
- Base locale : Room / SQLite.
- Architecture : couches UI -> domaine -> données.
- Caméra : CameraX à partir de V2.
- Vision : OpenCV puis TFLite/ONNX à partir des versions futures.

## Source de vérité

Avant toute modification, lire les documents utiles dans cet ordre :

1. `docs/cahier-des-charges-v1.md`
2. `docs/parcours-utilisateur.md`
3. `docs/regles-score.md`
4. `docs/modele-donnees-v1.md`
5. `docs/backlog-v1.md`
6. `docs/export-format-v1.md`
7. Les ADR dans `docs/adr/`

## Règles produit V1

- La V1 est une application Android locale/offline.
- Ne pas ajouter de compte utilisateur, cloud sync, backend, Firebase ou dépendance réseau obligatoire.
- Ne pas implémenter la reconnaissance automatique en V1.
- Ne pas ajouter CameraX, OpenCV, TFLite ou ONNX dans la V1 sans demande explicite.
- Le moteur de score doit être isolé du code UI et testable indépendamment.
- Les impacts utilisent toujours des coordonnées normalisées : centre `(0.0, 0.0)`, bord extérieur rayon `1.0`.
- Distinguer `scoreCalculated` et `scoreFinal`.
- Stocker les entités exportables avec UUID v4. Ne jamais utiliser les IDs Room comme clé de fusion entre appareils.
- Toute nouvelle décision structurante doit être documentée dans un ADR.

## Règles Android futures

Quand le projet Android sera créé :

- Garder une architecture claire : `ui`, `domain`, `data`.
- Utiliser Room pour les données relationnelles.
- Préférer Compose pour les écrans et composants.
- Ajouter des tests unitaires pour le moteur de score avant de le connecter au blason interactif.
- Ne pas coupler le calcul de score à la taille d'écran, aux pixels ou à Compose.
- Garder les exports JSON lisibles, versionnés et compatibles avec `docs/export-format-v1.md`.

## Commandes attendues

Le dépôt ne contient pas encore Gradle. Quand le squelette Android existera, les commandes de référence devront être documentées ici, par exemple :

- `./gradlew test`
- `./gradlew assembleDebug`
- `./gradlew lint`

## Sécurité et fichiers à ne pas committer

- Ne pas committer de keystores, fichiers de signature, `local.properties`, captures locales, exports utilisateur ou datasets de vision.
- Ne pas afficher ni committer de données personnelles issues d'une séance réelle.
- Les fichiers `.lmarchery.json` exportés par l'application sont des données utilisateur et ne doivent pas être versionnés.
