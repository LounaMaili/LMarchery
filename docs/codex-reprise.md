# Reprise Codex - LMarchery

## État au démarrage

Le dépôt est en phase 0 de cadrage fonctionnel. Il ne contient pas encore d'application Android, de fichiers Gradle, de code Kotlin, de tests ou de configuration d'environnement.

La branche de travail prévue pour la configuration Codex est `codex-setup`.

## Lecture recommandée

1. `README.md`
2. `docs/README.md`
3. `docs/cahier-des-charges-v1.md`
4. `docs/parcours-utilisateur.md`
5. `docs/regles-score.md`
6. `docs/modele-donnees-v1.md`
7. `docs/backlog-v1.md`
8. `docs/export-format-v1.md`
9. `docs/adr/*.md`

## Règles non négociables V1

- Application Android locale-first, utilisable sans réseau.
- Pas de compte, pas de cloud, pas de backend obligatoire.
- Pas de reconnaissance automatique en V1.
- Saisie manuelle fiable avant toute ambition IA.
- Coordonnées d'impacts normalisées : centre `(0.0, 0.0)`, bord rayon `1.0`.
- Moteur de score générique basé sur `TargetType` et `TargetZone`.
- `scoreCalculated` est recalculé depuis la position ; `scoreFinal` est la décision validée.
- Export/import JSON avec UUID v4 pour fusion et dédoublonnage.
- Room / SQLite pour les données locales structurées.

## Commandes de reprise

Aujourd'hui, aucune commande build/test/lint n'existe dans le dépôt.

Quand le projet Android sera créé, documenter et utiliser au minimum :

```bash
./gradlew test
./gradlew assembleDebug
./gradlew lint
```

## Priorité de développement V1

1. Moteur de score testable indépendamment de l'UI.
2. Blason interactif Compose avec coordonnées normalisées.
3. Cycle de vie d'une séance : brouillon, en cours, terminée.
4. Historique local.
5. Matériel, presets, export/import.
6. Tests terrain dès que le blason interactif fonctionne.

## Points sensibles

- Ne pas transformer la V1 en projet IA ou reconnaissance photo.
- Ne pas introduire de dépendance réseau obligatoire.
- Ne pas stocker les impacts en pixels.
- Ne pas utiliser les IDs Room comme identifiants d'export.
- Ne pas committer de fichiers de signature Android, exports utilisateur, captures personnelles, datasets ou modèles IA.
- Toute décision technique durable doit passer par un ADR.

## Validation minimale future

- Moteur de score : tests unitaires sur limites de zones, miss, zone X et cordon.
- UI blason : test manuel sur téléphone réel dès le premier prototype utilisable.
- Persistance : tests sur interruption, verrouillage téléphone et changement d'application.
- Export/import : tests de fusion par UUID et non par ID local.
