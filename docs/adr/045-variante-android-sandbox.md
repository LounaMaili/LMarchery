# ADR 045 — Variante Android isolée de recette

- Date : 2026-11-07
- Statut : variante implémentée ; installation et recette physique conditionnelles
- Base : main `3f85eb442d082993b27d64f9f20aa3087d48a37d`
- Références : [ADR 018](018-integration-android-room.md),
  [042](042-saf-v3-consultation-configurable.md),
  [043](043-editeur-android-seances-configurables.md),
  [044](044-recette-integration-v3-et-consolidation.md)

## Besoin

La recette de l'application consolidée doit disposer d'un identifiant stable
et distinct, sans remplacer `com.lmarchery` ni réutiliser sa base réelle. Les
anciennes variantes ponctuelles ne sont pas supprimées par cette décision.
Leur présence sur le téléphone doit être mesurée, pas déduite du nombre d'APK
archivés ou d'icônes aperçues.

## Variante

Le build type `sandbox` est initialisé depuis `debug`, avec suffixe
`.sandbox` pour l'applicationId et `-sandbox` pour la version. Il utilise la
signature debug et un fallback `debug` pour la bibliothèque Room Android.
Les configurations de dépendances sandbox héritent explicitement des outils
debug ; `initWith` copie les réglages du build type, pas cette relation Gradle.
Aucune dépendance ou version n'est ajoutée. Le source set sandbox ne contient
qu'une ressource remplaçant le nom visible par **LMarchery TEST**.

| Propriété | Debug normal | Sandbox |
|---|---|---|
| ApplicationId | `com.lmarchery` | `com.lmarchery.sandbox` |
| Nom | LMarchery | LMarchery TEST |
| Activité | `com.lmarchery.app.MainActivity` | même classe, autre composant Android |
| Namespace Kotlin | `com.lmarchery` | inchangé |
| Room / scoring / JSON | 3 / 1 / V1,V2,V3 | identiques |
| Signature | développement | même configuration debug dans le même environnement |

Le namespace de classes n'est pas l'identité d'installation. La fabrique Room
continue à utiliser `context.applicationContext.getDatabasePath("sessions.db")` :
ce nom local identique désigne le répertoire privé de **chaque application**.
Aucun chemin global ou nouvelle base spéciale n'est codé. Les préférences et
états Android restent ceux du contexte et du processus concernés.

Android attribue normalement un UID et un sandbox à chaque application
([AOSP](https://source.android.com/docs/security/app-sandbox)). La préparation
contrôle l'absence de `sharedUserId`, d'affinité commune explicite et d'autorité
provider commune. L'UID concret, l'utilisateur Android et les répertoires réels
restent à vérifier sur appareil autorisé ; un manifeste correct ne prouve pas
qu'une installation ou une isolation physique a été testée.

## Vérification des APK et CI

`scripts/verify-sandbox-apk.py` inspecte les deux APK réellement construits avec
`apkanalyzer`, `aapt` et `apksigner`. Il exige identifiants/noms attendus, activité,
SDK 26/35, mode debug, backup automatique désactivé, namespaces de permissions
et providers propres à chaque package, signature valide et même certificat
pour les deux builds du même environnement. Il émet les SHA-256 publics des
certificats/APK et leurs tailles. Il ne lit aucun keystore, ne contacte aucun
appareil et n'installe rien. Fournir le debug normal à la place de sandbox
est un cas négatif qui doit être refusé.

Le Quality gate conserve le contrôle complet, puis assemble et linte sandbox
et exécute ce vérificateur, sans `continue-on-error`. Les tests JVM Android sont
également exécutés pour sandbox par `gradlew test`. Ces répétitions ne sont pas
de nouveaux cas métier. Le parcours normal et toutes ses validations demeurent.

Le certificat debug dépend du poste et du keystore utilisé ; il ne constitue
pas un certificat de distribution. `ANDROID_USER_HOME` doit être cohérent pendant
les deux compilations. Une première vérification a correctement refusé des APK
produits avec deux homes Android différents ; la reconstruction dans le même
home passe sans assouplir le contrôle. La clé ADB et la clé de signature APK
ont des fonctions différentes.

Préserver le keystore de développement hors Git pour de futures mises à jour.
Un certificat différent ou l'origine inconnue d'un sandbox déjà installé bloque
une mise à jour : ne pas désinstaller ou effacer ses données pour la contourner.
Aucun keystore, APK, export ou preuve privée n'est ajouté au dépôt.

## Frontières de la campagne

La découverte réseau, l'inventaire, l'installation conditionnelle et la recette
suivent le [protocole](../validation-android-sandbox.md). Le rejet TLS ADB
`CERTIFICATE_UNKNOWN` observé le 7 novembre nécessite une vérification physique
de l'appairage. Aucun code n'est demandé ni pairage forcé en l'absence du propriétaire.

L'audit disque mesure séparément projet, builds, caches partagés, SDK et AVD,
sans additionner un total et ses sous-répertoires. Seuls les outputs régénérables
du nouveau worktree peuvent être nettoyés après préservation des APK/rapports et
fin des opérations. Les anciens worktrees, preuves, caches partagés, keystores
et installations ne sont pas supprimés.

Les fichiers et méthodes métier, schémas/migrations Room et contrats/golden JSON
restent inchangés. Aucune dérogation sportive, migration réelle ou conversion
implicite de séance. La précision au doigt, l'ergonomie et SAF réel ne sont pas
validés par les tests JVM ou une simple compilation.

## Sources

- [Configuration des variantes Android](https://developer.android.com/build/build-variants)
- [Isolation des applications Android](https://source.android.com/docs/security/app-sandbox)
