# Environnement Fedora et validations CI

## Versions du projet

| Outil / composant | Version attendue |
| --- | --- |
| JDK complet, avec `java` et `javac` | 21 (Temurin utilisé localement et en CI) |
| Gradle Wrapper | 8.10.2 |
| Plugin Kotlin JVM/Android et Compose Compiler | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Android compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Android SDK Build Tools | 34.0.0, version par défaut d'AGP 8.7 |
| Compose BOM / Activity Compose | 2024.12.01 / 1.9.3 |

Ces versions restent celles des configurations Gradle ; cette consolidation ne
met pas les dépendances à jour. `./gradlew --version` affiche aussi le Kotlin
embarqué dans Gradle (1.9.24), qui n'est pas le plugin Kotlin 2.0.21 du projet.
Les scripts et le JAR du wrapper sont générés par Gradle 8.10.2 ; le checksum
officiel de sa distribution est enregistré dans `gradle-wrapper.properties`.

La validation vérifiée sur Fedora utilise Temurin 21.0.12.1. Le `java` système peut
rester en version 25 : le script sélectionne JDK 21 pour ses propres processus,
sans modifier les alternatives du système.

## Préparer le poste

Installer un JDK 21 complet et les Android SDK Command-Line Tools (depuis Android
Studio ou le paquet officiel). Adapter les chemins à l'installation locale :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

java -version
javac -version
sdkmanager --version
sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
sdkmanager --licenses
```

Les licences doivent être acceptées sur le poste. Un émulateur, un appareil, un
compte applicatif et un keystore de release ne sont pas nécessaires pour ces
validations. Le build debug utilise la signature debug générée par Android.

Le premier build peut télécharger le wrapper et les dépendances : le réseau est
nécessaire pour préparer le build, pas pour utiliser l'application V1.

## Variables et détection

| Variable | Rôle / comportement du script |
| --- | --- |
| `JAVA_HOME` | Racine d'un JDK 21 complet. Si fourni et invalide, échec immédiat. |
| `ANDROID_HOME` | Racine de l'installation du SDK. |
| `ANDROID_SDK_ROOT` | Ancien alias du SDK, déprécié. Accepté en secours ; un conflit avec `ANDROID_HOME` provoque un échec. |
| `ANDROID_USER_HOME` | Dossier de préférences des outils Android. Respecté s'il est fourni ; sinon isolé sous `${TMPDIR:-/tmp}/lmarchery-android-home`. |
| `ANDROID_SDK_HOME` | Ancienne variable désignant le parent de `.android`, jamais la racine du SDK. Si elle existe sans `ANDROID_USER_HOME`, le script utilise son sous-dossier `.android`. |
| `GRADLE_USER_HOME` | Cache Gradle ; par défaut `$HOME/.gradle`. À rediriger vers un dossier writable si nécessaire. |

Sans `JAVA_HOME`, le script essaie `JAVA_HOME_21_X64`, le JDK du `PATH`, les
installations contenant `21` sous `/usr/lib/jvm`, puis `/tmp/jdk-21`.

Sans `ANDROID_HOME`, il essaie `ANDROID_SDK_ROOT`, puis `$HOME/Android/Sdk`, puis
`/tmp/android-sdk`. Il vérifie `platforms/android-35/android.jar` et les outils
`aapt2` / `d8` de Build Tools 34.0.0 avant de lancer Gradle. Il n'installe rien.
Si un `local.properties` existe pour Android Studio, son `sdk.dir` doit désigner
le même SDK ; ce fichier reste local et ne doit pas être committé.

Pour un environnement isolé :

```bash
GRADLE_USER_HOME=/tmp/lmarchery-gradle \
ANDROID_USER_HOME=/tmp/lmarchery-android-home \
./scripts/dev-check.sh
```

Un cache vide exige des téléchargements. Une restriction réseau ou un dossier
non writable est un échec d'environnement, pas une validation réussie des tests.

Références : [variables Android](https://developer.android.com/tools/variables),
[compatibilité AGP 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes),
[compatibilité Java/Gradle](https://docs.gradle.org/current/userguide/compatibility.html).

## Valider localement

Depuis le dépôt :

```bash
./scripts/dev-check.sh
```

Le script affiche la branche, les versions Java/Gradle et les chemins utilisés,
vérifie les permissions du manifeste V1 et les espaces du diff, puis exécute :

1. `./gradlew test` : tests JVM du domaine et tests locaux Android, sans appareil.
2. `./gradlew :app:assembleDebug` : compilation de l'APK debug.
3. `./gradlew :app:lintDebug` : analyse statique Android.

`set -euo pipefail` interrompt le script au premier échec. Les options données au
script sont transmises à chaque invocation Gradle. Pour forcer une nouvelle
exécution des tâches plutôt que réutiliser leurs résultats :

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
```

Sans `app/build.gradle.kts` ou `app/build.gradle`, seule la validation JVM est
exécutée et aucun SDK Android n'est requis. Le script annonce explicitement
cette variante ; il ne faut pas la présenter comme un build/lint Android réussi.

Les rapports sont disponibles dans :

- `core/model/build/reports/tests/test/index.html` ;
- `core/scoring/build/reports/tests/test/index.html` ;
- `app/build/reports/tests/testDebugUnitTest/index.html` et `testReleaseUnitTest/index.html` ;
- `app/build/reports/lint-results-debug.html`.

L'APK est `app/build/outputs/apk/debug/app-debug.apk`. Rapports et APK restent
des sorties de build ignorées par Git.

## GitHub Actions

Le workflow `.github/workflows/quality-gate.yml` utilise `ubuntu-24.04`, prépare
Temurin 21 et, si `:app` existe, installe Platform Tools, la plateforme Android 35
et Build Tools 34.0.0. Il exécute le même script avec `--no-daemon --console=plain`.

Il écoute `pull_request` sans filtre de branche ni de chemin : les PR vers `main`
et les branches intermédiaires de la pile sont couvertes. Il teste le commit de
fusion préparé par GitHub avec la base de la PR. `workflow_dispatch` permet aussi
une exécution manuelle une fois le workflow disponible sur la branche par défaut.

Les actions maintenues sont épinglées par SHA : `actions/checkout` v7,
`actions/setup-java` v6, `android-actions/setup-android` v4 et
`gradle/actions/setup-gradle` v6.4.0. Avant toute future actualisation, vérifier
les releases et les SHA dans leurs dépôts officiels.

`setup-gradle` valide le wrapper et gère seul le cache Gradle. Les PR du dépôt
peuvent écrire dans leur cache, limité à leur référence de PR par GitHub ; les PR
de forks lisent seulement les caches disponibles. Ne pas ajouter en parallèle
le cache Gradle de `setup-java` ou un second `actions/cache`.

Si le contrôle du JAR échoue, ne pas le désactiver ni autoriser arbitrairement
son hash. Régénérer le wrapper pour la version déjà déclarée, puis comparer son
SHA-256 au [checksum officiel](https://services.gradle.org/distributions/gradle-8.10.2-wrapper.jar.sha256)
et relancer toutes les validations :

```bash
./gradlew wrapper --gradle-version 8.10.2 --distribution-type bin
sha256sum gradle/wrapper/gradle-wrapper.jar
./scripts/dev-check.sh --rerun-tasks --console=plain
```

Les variables Java/SDK doivent être préparées comme pour les autres commandes
Gradle directes. Vérifier aussi que le checksum de distribution officiel reste
présent dans `gradle/wrapper/gradle-wrapper.properties`.

Le workflow dispose uniquement de `contents: read`, ne conserve pas les
identifiants du checkout et ne demande aucun secret personnalisé. Il n'effectue
ni publication, ni upload d'APK, ni signature de release. Les exécutions dépassées
d'une même PR sont annulées ; le job est limité à 30 minutes.

Références : [événements GitHub Actions](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
[cache et validation du wrapper](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md).

## Introduire la CI dans la pile de PR

La pile existante est `main` ← PR #2 (`v1/02-session-domain`) ← PR #3
(`v1/03-android-skeleton`). `main` et #2 ne contiennent pas encore Android.

La consolidation utilise une PR supplémentaire de `chore/ci-quality-gate` vers
`v1/03-android-skeleton` : son diff ne contient que la consolidation CI/script/docs.
Après autorisation de publication, vérifier les tests, le build et le lint dans
son run GitHub. Si tous les contrôles sont exécutés et réussis, cette PR peut être
fusionnée par squash dans `v1/03-android-skeleton`, sans fusionner ni modifier
directement les PR #2 et #3. Synchroniser ensuite la branche cible locale avec
sa branche distante en fast-forward uniquement.

L'intégration ultérieure dans `main` nécessite une autorisation distincte et
conserve l'ordre #2 puis #3, avec actualisation des bases et vérification des
nouveaux runs. Ne pas contourner les protections, forcer un push ou fusionner
si une validation est absente, ignorée ou en échec.

Ajouter ce workflow dans la nouvelle PR ne lance pas rétroactivement la CI sur
les commits actuels de #2 et #3. Après son squash dans la branche cible, la PR #3
inclut les fichiers CI et peut déclencher sa propre validation. La PR #2 reste
inchangée ; y reprendre la CI serait un travail distinct nécessitant une
autorisation explicite. La variante JVM seule est prête pour ce cas.

Le check `Quality gate` pourra être rendu obligatoire dans les protections de
branche seulement après une exécution GitHub réussie et une décision explicite
du mainteneur. Le nom du workflow ne configure pas lui-même une protection.

Toujours distinguer les résultats : **tests locaux**, **compilation debug**,
**lint**, et **run CI GitHub**. Un workflow configuré ou une validation YAML
réussie ne prouve pas que la CI a fonctionné sur GitHub.
