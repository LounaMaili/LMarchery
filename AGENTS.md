# AGENTS.md - LMarchery

## Contexte du projet

LMarchery est une application Android de gestion des scores au tir à l'arc. Le dépôt contient maintenant un socle Gradle/Kotlin, le domaine pur et un premier squelette Android Compose local.

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

## Règles Android

- Garder une architecture claire : `ui`, `domain`, `data`.
- Utiliser Room pour les données relationnelles.
- Préférer Compose pour les écrans et composants.
- Ajouter des tests unitaires pour le moteur de score avant de le connecter au blason interactif.
- Ne pas coupler le calcul de score à la taille d'écran, aux pixels ou à Compose.
- Garder les exports JSON lisibles, versionnés et compatibles avec `docs/export-format-v1.md`.

## Commandes attendues

Lire aussi `docs/environnement-et-ci.md` avant toute modification du build, des
scripts ou de la CI.

La validation complète de référence est `./scripts/dev-check.sh`. Après toute
modification de code, de configuration Gradle, du script ou du workflow, l'exécuter
et inspecter le résultat de chaque validation. Les étapes exécutées sont :

- `./gradlew test`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`

Utiliser un **JDK 21 complet**, le SDK Android 35 et les Build Tools 34.0.0.
Le script détecte un JDK 21 installé ; un `JAVA_HOME` explicite incompatible
provoque un échec. Les commandes Gradle directes exigent de préparer `JAVA_HOME`.
Ne pas confondre le Kotlin embarqué affiché par `./gradlew --version` avec le
plugin Kotlin du projet, défini dans `build.gradle.kts`.

`ANDROID_HOME` désigne le SDK. `ANDROID_SDK_ROOT` est déprécié ; s'il existe,
il doit désigner le même SDK. `ANDROID_USER_HOME` désigne les préférences et est
isolé par défaut sous `/tmp/lmarchery-android-home` (ou `TMPDIR`). Ne pas utiliser
`ANDROID_SDK_HOME` comme chemin du SDK. Si le home utilisateur n'est pas accessible
en écriture, définir `GRADLE_USER_HOME` vers un dossier temporaire.

Le script et la CI adaptent les validations à la présence de `app/build.gradle.kts`
ou `app/build.gradle` : sans `:app` (état de `main` et de la PR #2 avant fusion de
#3), seuls les tests JVM sont exécutés. Ne jamais annoncer un build ou lint Android
réussi lorsqu'il a été ignoré.

Pour une modification de script/workflow, vérifier aussi `bash -n scripts/dev-check.sh`
et la syntaxe du workflow (avec `actionlint` si disponible). Tester les erreurs de
configuration concernées et la variante sans Android sans modifier les branches
de référence. Pour une modification uniquement documentaire, vérifier au minimum
les liens et `git diff --check` ; relancer le script si elle touche la procédure de validation.

Ne pas désactiver la validation du wrapper pour contourner un échec CI. Régénérer
les fichiers officiels pour la version Gradle déjà déclarée, vérifier le checksum
du JAR et conserver le checksum de distribution dans `gradle-wrapper.properties`.

Dans le compte rendu, distinguer tests locaux, compilation Android, lint et CI
GitHub. Une CI configurée localement n'est pas une CI validée : seul un run GitHub
réussi sur le commit concerné le confirme. Ne pas publier ou modifier les protections
de branche sans autorisation. Une PR CI ciblant `v1/03-android-skeleton` peut être
fusionnée dans cette branche après validation locale et CI, avant les PR parentes.
Ne pas modifier ni fusionner #2 ou #3 sans autorisation distincte ; leur future
intégration dans `main` conserve l'ordre #2 puis #3.

## Sécurité et fichiers à ne pas committer

- Ne pas committer de keystores, fichiers de signature, `local.properties`, captures locales, exports utilisateur ou datasets de vision.
- Ne pas afficher ni committer de données personnelles issues d'une séance réelle.
- Les fichiers `.lmarchery.json` exportés par l'application sont des données utilisateur et ne doivent pas être versionnés.
