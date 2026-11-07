# Recette Android isolée — LMarchery TEST

Ce protocole prépare une application indépendante ; il ne donne aucune
permission de lire ou modifier les données privées de `com.lmarchery`.
Voir [ADR 045](adr/045-variante-android-sandbox.md) et les contrats de
[l'éditeur configurable](adr/043-editeur-android-seances-configurables.md),
[SAF V3](adr/042-saf-v3-consultation-configurable.md) et
[recette SQLite intégrée](recette-integration-v3-pr44.md).

## Construire et contrôler

À partir de la branche de campagne, dans un environnement JDK/SDK cohérent :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_USER_HOME=/tmp/lmarchery-android-home
./scripts/dev-check.sh --rerun-tasks --console=plain
./gradlew :app:assembleSandbox :app:lintSandbox --console=plain
python3 scripts/verify-sandbox-apk.py
```

Le debug normal est un témoin de non-régression, **pas un APK à installer lors
de cette campagne**. L'APK de recette est
`app/build/outputs/apk/sandbox/app-sandbox.apk`, package `com.lmarchery.sandbox`,
nom **LMarchery TEST**, signature de développement. Le vérificateur inspecte les
APK et leurs certificats, pas seulement les fichiers source ou le nom du fichier.
Ne pas mélanger les sorties de homes Android/keystores différents. La CI ajoute
le build, lint et contrôle APK sandbox aux validations historiques obligatoires.

Archiver en privé le SHA Git, les résultats JSON/XML, SHA-256 APK et certificat,
manifestes décodés et journaux utiles. Préserver le keystore hors Git si une
mise à jour est envisagée ; un home temporaire peut disparaître au redémarrage.
Aucun APK, keystore, capture ou export utilisateur dans Git.

## Connexion et inventaire avant toute installation

1. Relever les versions et transports : `adb version`, `adb devices -l`,
   `adb mdns services`. Si le backend ADB ne découvre rien, une découverte Avahi
   ciblée `_adb-tls-connect._tcp` peut résoudre une annonce réelle. Une liste
   vide ne prouve pas une indisponibilité réseau.
2. Connecter seulement un endpoint actuellement annoncé, sans réutiliser un
   ancien port, inventer d'IP ou scanner le réseau. Limiter les tentatives.
   `CERTIFICATE_UNKNOWN` ne se contourne pas : une vérification/réassociation
   exige le propriétaire et le code éphémère affiché, jamais un ancien code.
3. Avant toute opération, exiger `device` et une commande shell réussie.
   Confirmer modèle Pixel 8, version/API, utilisateur/profils et transport.
   S'il existe plusieurs appareils ambigus, ne rien installer. Conserver le
   transport complet dans les preuves privées, le masquer dans le compte rendu.
4. Inventorier uniquement les packages LMarchery pour chaque utilisateur
   identifié : applicationId, UID, version, chemins APK et lanceurs. Relever
   l'identité du package normal avant/après, sans `run-as com.lmarchery`, lecture
   de base réelle ou extraction de documents. Les quatre icônes peuvent être
   packages, profils ou raccourcis : le nombre seul ne tranche pas.
5. Mesurer le stockage accessible (`df`) et les statistiques que le shell Android
   expose réellement. Si les données privées ne sont pas mesurables sans accès
   privilégié, noter cette limite. Ne pas annoncer une taille à partir de l'APK seul.

Ne pas contourner écran verrouillé, autorisation USB/Wi-Fi ou pairage. Aucun
`adb tcpip 5555`, changement de sécurité ou tentative de réveil/déverrouillage
forcé. Sans accès autorisé, noter NON TESTÉ et poursuivre les contrôles logiciels.

## Installer seulement dans le périmètre établi

Confirmer de nouveau le résultat du vérificateur, l'utilisateur Android cible,
la présence de `com.lmarchery` et l'absence de `com.lmarchery.sandbox`.
Pour une installation neuve **seulement**, après toutes ces vérifications :

```bash
# Valeurs issues des vérifications, jamais des exemples historiques.
adb -s "$ADB_TRANSPORT" install --user "$DEVICE_USER_ID" "$APK_SANDBOX"
```

Si sandbox existe déjà, s'arrêter avant la commande : établir sa provenance
exclusivement synthétique, son certificat et sa compatibilité. Une mise à jour
n'est autorisée que si ces preuves concordent. Aucun uninstall, clear, changement
de signature forcé ou suppression de base pour résoudre un échec.

Après installation, vérifier UID distinct du normal, répertoire privé associé
au bon utilisateur et package exact, backup désactivé, puis démarrer seulement
`com.lmarchery.sandbox/com.lmarchery.app.MainActivity`. Vérifier Room V3, archive
initialement vide et absence de crash. Une applicationId distincte avec même
classe Kotlin n'est pas un remplacement du package normal.

Les logs doivent être limités au PID/UID de la sandbox. Aucun nettoyage global
de logcat. Les copies SQLite ne concernent que ses données synthétiques, après
arrêt de **son** processus et capture cohérente du fichier et de son WAL éventuel.
Ne pas interpréter une copie à chaud de la seule base comme un snapshot fiable.

## Matrice fonctionnelle à renseigner PASS / FAIL / NON TESTÉ

| Étape | Vérifications attendues |
|---|---|
| Démarrage | Bon package, base V3 distincte, écran sans crash, archive vide |
| Séance rapide synthétique | Trois impacts, total attendu calculé indépendamment, validation puis reprise après arrêt/re lancement |
| Création configurable | Brouillon, démarrage, initiale et édition exacte ; distance indépendante |
| Volées mixtes | Classique/poulies ; FIXED 3/6/7 et FREE ; première origine INITIAL, héritage INHERITED, EXPLICIT même si identique |
| Saisie | Profil effectif de la volée ; 9 à r=0.105 sans promotion automatique ; X seulement sur profil autorisé, M cohérent |
| Visée | Candidat non durable, annulation sans écriture, confirmation unique ; aucun appui à coordonnées d'écran non observées |
| Corrections | Jugement contextualisé, motif exceptionnel, plusieurs repositionnements, invalidations/traces conservées |
| Statuts | Volée validée protégée, clôture immuable, provisoire/validé distingués ; pas de règle sportive inventée |
| Cycle de vie | Arrière-plan, recréation/rotation possible, arrêt/reprise de la sandbox ; sauvegardé conservé, candidat annulé absent |
| Intégrité | UUID, captures et origines, décisions, traces, douze tables, FK et integrity_check |
| SAF V3 | Destination synthétique connue, fermeture/relecture exacte, annulation, candidat d'import exact, réimport idempotent sans doublon |
| Concurrence | Double confirmation et callback ancien sans deuxième écriture ; conflit explicite, commit incertain relu sans retry automatique |

Utiliser les sémantiques/accessibilités observées et des contrôles de résultat,
jamais des coordonnées aveugles. Si un écran, clavier, fournisseur SAF ou déverrouillage
requiert une personne, noter NON TESTÉ et poursuivre les autres étapes possibles.
Les scripts de recette ne doivent pas modifier le scoring ou les invariants.

SAF ne doit parcourir que des documents créés pour la campagne dans une destination
synthétique explicitement établie. La restauration dans une seconde base doit
utiliser un autre fichier Room synthétique via un harnais isolé ou une autre
installation de test dont l'identité a été vérifiée. **Ne pas vider la première
base ni réutiliser une base réelle.** Le harnais SQLite JVM teste déjà deux fichiers ;
ce résultat n'est pas un essai du fournisseur SAF ou du stockage Android.

Restaurer toute valeur système éventuellement modifiée. La précision au doigt,
la lisibilité extérieure et l'ergonomie humaine restent NON TESTÉES sans essai humain.

## Audit disque et nettoyage

Mesurer les tailles physiques allouées avec `du -x -s -B1`, les volumes avec `df`,
et noter les chemins réels. Le total du projet comprend ses builds et son cache :
ces lignes sont des sous-ensembles, pas des quantités à additionner. Les caches
Gradle utilisateur et le SDK sont partagés avec d'autres projets. Distinguer
`/tmp` en tmpfs d'un disque persistant ; supprimer un build tmpfs ne libère pas
le volume home. Dédupliquer les APK accessibles par le même chemin réel.

Seuls les builds du nouveau worktree sont nettoyables ici, après fin des tâches,
archivage de l'APK vérifié, manifestes, empreintes et XML/lint, et preuve d'absence
de données uniques. Ne supprimer ni anciennes preuves, ni worktrees, SDK/AVD,
cache Gradle partagé ou keystore sous prétexte de gagner de l'espace.
Consigner tailles avant/après et action pour chaque chemin ; une suppression
potentielle sur appareil reste une proposition au propriétaire.

## Campagne future de migration Android

La preuve native hôte des migrations peuplées 1→2→3 est déjà exécutée. Pour la
preuve Android, prévoir une identité de migration supplémentaire distincte du
normal et de toute sandbox contenant des données à conserver, des APK historiques
adaptés avec la même signature et le même nom de base synthétique. Créer V1,
capturer UUID/valeurs/archives, mettre à jour vers V2 puis V3 et comparer après
chaque fermeture/réouverture, intégrité et FK comprises. La signature et l'UID
continu doivent être vérifiés à chaque étape. Ne jamais installer V1/V2 sur une
base déjà V3. Aucune migration de l'application normale dans cette campagne.

## Relevé du 7 novembre 2026

La découverte Avahi retrouve l'annonce Pixel 8 ; le shell ADB n'est pas autorisé.
Les journaux des deux serveurs (Fedora et SDK, mêmes clés existantes) montrent
`SSLV3_ALERT_CERTIFICATE_UNKNOWN`. L'IP répond, mais un appairage accepté reste
indispensable. Aucun modèle/API/utilisateur ni UID réel n'a été confirmé par shell.
Aucun APK installé, application normale interrogée ou document personnel consulté.
La présence et la taille des applications, ainsi que l'origine des quatre icônes,
restent NON TESTÉES. Les preuves privées et mesures détaillées sont dans le
rapport local de campagne, hors Git.
