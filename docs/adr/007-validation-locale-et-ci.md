# ADR 007 — Validation locale et CI commune

## Contexte

Le domaine Kotlin et le squelette Android sont développés dans des PR empilées.
`main` et la PR #2 ne contiennent pas encore `:app`. L'environnement de développement
doit sélectionner un JDK 21 même si Java 25 est le choix par défaut du système.

## Décision

`scripts/dev-check.sh` est le point d'entrée commun aux validations locales et à
GitHub Actions. Il affiche l'environnement, vérifie les prérequis et exécute les
tests, puis le build debug et le lint si le module Android existe. Le premier
échec interrompt la validation.

La CI utilise JDK 21, les composants du SDK correspondant à AGP 8.7.3 et le
Gradle Wrapper du dépôt. Elle se déclenche sur les PR sans filtre de branche de
base, utilise des actions épinglées par SHA, un seul cache Gradle et aucun secret
de publication ou de signature. Elle ne publie pas d'APK.

## Conséquences

- Les branches sans Android peuvent conserver une validation JVM seule.
- Les préférences Android (`ANDROID_USER_HOME`) sont distinctes du SDK (`ANDROID_HOME`).
- Les tests/build/lint restent visibles séparément dans le journal du script.
- La préparation de la CI utilise le réseau ; le fonctionnement de l'application reste offline.
- Un succès local ne valide pas GitHub Actions. Le workflow doit réussir sur GitHub
  avant d'être annoncé opérationnel ou rendu obligatoire dans les protections.

*14 octobre 2026*
