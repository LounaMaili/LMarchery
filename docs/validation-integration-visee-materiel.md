# Validation future — intégration visée précise / matériel / SAF

État : **NON TESTÉ sur appareil**. Aucun accès ADB, installation, lecture de base
utilisateur ni geste simulé pendant l’intégration. Les preuves automatisées sont
séparées des observations humaines. Référence : [ADR 032](adr/032-integration-visee-materiel.md).
Ce protocole concerne un Pixel 8 sous Android 17 ; la compilation SDK reste 35.

## Références vérifiées avant assemblage

`main` protégée : `e4c7e56817baa3f5a5a366bdf5895b3d38f6b798`.
Les dix PR sont ouvertes, sans auto-merge. Vérification le 20 octobre 2026 :

| PR | Tête | Base | Quality gate sur cette tête |
|---|---|---|---|
| #22, brouillon | `c9675a257e1459daf2577220b0c0d0bcd9b3ed6e` | main | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/38943993823) |
| #23 | `de06429ef137c570119e57d5e9fb804ae04807df` | main | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/38976305865) |
| #24 | `b334c8eff5af8f4ca3872761f29d00e1d4aa97df` | #23 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/38991483464) |
| #25 | `ffe789f275cb73c6ffc0d81d396dccee3327d3b0` | #24 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39001600076) |
| #26, brouillon documentaire | `ae96dbda3973445ff850a77ef4e4cb3de9066b04` | main | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39086793997) |
| #27 | `27054393053fbae6f41ae4270ca1dc20ab9a32f7` | #25 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39088332734) |
| #28 | `cdca53515e02927a4c6f496b4178065b8258e8e7` | #27 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39089079669) |
| #29 | `d956dee5be74fa0246a9132bc91e818ceb0ca556` | #28 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39089900425) |
| #30 | `d7a916bf1bf21b187c2b2dba64460c0d803a53f1` | #29 | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39090599039) |
| #31, audit documentaire | `b2399834ecf6490d82fc433efee40fdfa6fd5379` | main | [PASS](https://github.com/LounaMaili/LMarchery/actions/runs/39090908930) |

#26 et la proposition de séances personnalisables de #31 ne sont pas du code à
assembler. L’intégration part de #30 et incorpore #22 dans une nouvelle branche.
Les CI individuelles ne remplacent pas son propre Quality gate.

## Ordre d’installation impératif

1. **Préserver la campagne #22 actuelle.** Tant que son installation Room 1 doit
   rester disponible, ne pas installer l’APK d’intégration dans cet environnement.
   Conserver toutes les séances existantes, dont `TEST AUTO PR18` et `TEST AUTO PR22`.
   Ne pas supposer leur nombre ; établir la référence privée au début du futur essai.
2. Commencer avec un émulateur/appareil ou utilisateur Android **indépendant**,
   contenant uniquement des données synthétiques. Le même package dans le même
   utilisateur Android partage sa base : deux fichiers APK ne créent pas deux
   installations isolées. Un profil secondaire doit être réellement configuré et
   vérifié avant usage ; sa présence n’est pas présumée.
3. Pour tester la migration, installer d’abord l’APK exact #22 / Room 1 dans cet
   environnement isolé. Y créer les seules séances synthétiques autorisées et
   obtenir un export SAF V1 fermé, relu et reconstruit. Conserver hors téléphone
   les octets, empreinte et copie privée cohérente DB/WAL/SHM après arrêt de l’app.
4. Noter commit, SHA-256 APK, package, certificat de signature et version ; ne pas
   utiliser seulement le `versionName` commun aux builds. Relever sur la **copie**
   SQLite `PRAGMA user_version = 1`, `quick_check`, FK, UUID et contenu des huit tables.
5. Installer ensuite l’APK d’intégration **Room 2**, même package et signature,
   sans désinstallation ni effacement. Si incompatibilité de signature/version,
   arrêter et diagnostiquer ; ne pas résoudre par `pm clear` ou suppression de base.
6. Après premier lancement et après un second démarrage, capturer une nouvelle
   référence privée cohérente. Vérifier `user_version = 2`, dix tables, intégrité,
   huit tables historiques et exports V1 identiques avant toute édition. Arcs et
   jeux de flèches sont initialement vides. Toute différence inexpliquée est FAIL.
7. La migration du Pixel principal nécessite une décision ultérieure du propriétaire,
   après fin de campagne #22, sauvegarde V1 vérifiée hors appareil et essais isolés.
   Cette mission n’autorise ni n’exécute cette migration physique.

**Après passage à Room 2, ne jamais installer/lancer l’ancien APK Room 1 sur cette
base.** Ni `adb install -r -d`, ni un changement de `user_version`, ni une migration
destructive ne constituent un retour arrière. Conserver un APK compatible Room 2.
Pour revoir #22/Room 1, utiliser une autre installation indépendante avec sa propre
base et un fichier V1 compatible explicitement autorisé ; laisser la base V2 intacte.
Une archive V2 complète ne doit pas être projetée silencieusement en V1.

Commandes préparatoires pour la **future** campagne, non exécutées ici :

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb devices -l
# Utiliser le numéro/port réellement observé et le bon utilisateur Android.
LMARCHERY_TEST_SERIAL='identifiant-observe'
adb -s "$LMARCHERY_TEST_SERIAL" shell getprop ro.build.version.release
adb -s "$LMARCHERY_TEST_SERIAL" shell dumpsys package com.lmarchery
# Seulement après les étapes 1–5 et vérification explicite de l’environnement isolé :
adb -s "$LMARCHERY_TEST_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$LMARCHERY_TEST_SERIAL" shell am start -n com.lmarchery/.app.MainActivity
```

Pour un utilisateur Android secondaire, adapter explicitement les commandes à cet
utilisateur ; ne pas exécuter ce bloc en supposant qu’il vise une autre base.
Les captures nécessitent `run-as` autorisé pour cet APK debug : arrêter l’application,
copier en privé `sessions.db` et ses WAL/SHM présents, puis analyser les copies sur
PC. Ne pas lire seulement le fichier principal d’une base WAL active. Ne rien
committer (base, export, captures, détails privés). Sans capture cohérente, noter
NON TESTÉ pour les comparaisons SQL, sans prétendre à une preuve de conservation.

## Essais au doigt, pas à pas

Les gestes ci-dessous sont réalisés par le propriétaire. À chaque point de contrôle,
noter UUID des séances/volées/impacts, nombre d’impacts, score calculé/final, total
provisoire/validé, X/M et nombre/contenu des traces. Comparer les séances protégées
intégralement ; seules les séances synthétiques de cette nouvelle campagne peuvent
être modifiées. Les captures interrompent l’app : ne les faire qu’après résolution
du candidat en cours, sinon cela testerait une interruption supplémentaire.

| Étape | Action humaine et résultat attendu | Statut initial |
|---|---|---|
| 1 | Créer deux fiches synthétiques, arc et flèches ; null/vide, Unicode et unités libres conservés ; UUID distincts | NON TESTÉ |
| 2 | Créer une séance de test classique 40 cm avec ces associations ; 0 impact, références exactes | NON TESTÉ |
| 3 | Mode rapide : placer un 9 ; attendre l’acquittement, un seul UUID, total provisoire 9 | NON TESTÉ |
| 4 | Mode précis : appuyer/déplacer/relâcher sur 8 ; loupe/réticule/candidat concordent, durable toujours 9 ; matériel/navigation/export indisponibles ; annuler, SQL identique | NON TESTÉ |
| 5 | Refaire 8, confirmer une fois ; deux impacts, total 17 ; refaire sur un 7 et tenter deux appuis rapides de confirmation, trois impacts, total 24, aucun quatrième UUID | NON TESTÉ |
| 6 | Sélectionner le 9 : jugement explicite 10/9 côté 10, total 25 ; repositionner précisément vers 8, motif obligatoire ; confirmer, total 23, UUID inchangé, décision antérieure conservée dans la trace | NON TESTÉ |
| 7 | Correction exceptionnelle du même impact à 9, motif ; total 24, géométrie 8, décision distincte et trace conservée ; annuler une nouvelle correction ne change rien | NON TESTÉ |
| 8 | Fermer la sélection, ouvrir les associations : Canvas/visée/SAF/navigation bloqués ; annuler conserve les références ; remplacer volontairement une fiche puis confirmer, impacts/décisions/traces/totaux strictement identiques | NON TESTÉ |
| 9 | Sur une volée de test avec place disponible : deuxième doigt dedans/dehors, sortie/rentrée du carré, rotation pendant geste puis après relâchement ; aucun ajout par interruption, candidat déjà relâché conservé ; revenir puis reprendre sans callback parasite | NON TESTÉ |
| 10 | Depuis l’accueil ouvrir le catalogue, revenir ; aucune reprise de geste/confirmation ancienne ; les associations existantes ne deviennent pas nulles | NON TESTÉ |
| 11 | Valider et clôturer la séance de test : score 24 ; score/repositionnement impossibles, associations seules modifiables ; fermeture/réouverture exacte | NON TESTÉ |
| 12 | Export principal V2 : comptes corrects, fermeture + relecture confirmées ; décodage strict PC puis import dans une autre base jetable, comparaison intégrale des dix tables | NON TESTÉ |
| 13 | Export explicite V1 : séances/décisions/traces et références identiques, fiches exclues ; relecture puis import isolé conserve les références opaques ; aucun effacement de catalogue | NON TESTÉ |
| 14 | Annuler SAF et tourner avec sélecteur ouvert ; aucun faux succès, fichier partiel signalé lors d’une erreur réelle de fournisseur ; ne pas provoquer une corruption de la base | NON TESTÉ |

Un seul appui normal suffit à confirmer. Le double appui est un essai de résistance
aux répétitions : la première commande peut déjà faire disparaître le bouton ;
l’attendu porte sur **une seule écriture/un seul UUID**, pas sur deux clics traités.
Les conflits et callbacks périmés injectés en JVM ne sont pas déclarés testés sur
appareil sans reproduction contrôlée ; ne pas corrompre de données pour les simuler.

Pour l’ergonomie : comparer rapide/précis sur 10, 9/8 et bord 1/M ; vérifier au doigt
que la loupe déportée reste lisible, n’occulte pas la cible et ne provoque pas de
scroll. Essayer portrait/paysage, annuler puis recommencer. Noter les difficultés,
sans déduire une précision physique d’un score affiché ou d’une tolérance tactile.

## Relevé de validation

Pour chaque ligne : date, SHA APK, environnement isolé, action, attendu, observation,
preuve privée (empreinte/nom local), puis **PASS / FAIL / NON TESTÉ**. Un FAIL bloque
la qualification de cette combinaison ; une absence d’appareil reste NON TESTÉ.
Aucune fusion ni auto-merge n’est autorisé par ce protocole.

## Validation automatisée de la combinaison sur Fedora

Commande exécutée : `./scripts/dev-check.sh --rerun-tasks --console=plain`.
JDK Temurin 21.0.12.1, Wrapper Gradle 8.10.2, SDK 35 / Build Tools 34.0.0.

| Validation | Résultat local |
|---|---|
| Modèle | PASS — 103 |
| Scoring et reconstruction | PASS — 223 |
| Codec JSON V1/V2, golden et empreintes V1 | PASS — 34 |
| SQLite natif sur disque, dont migration V1→V2 | PASS — 176 |
| Présentation/contrôleurs Android JVM | PASS — 228 debug + 228 release |
| `:app:assembleDebug` et `:core:database:assembleDebug` | PASS |
| `:app:lintDebug` et `:core:database:lintDebug` | PASS — zéro erreur, aucun issue dans les deux XML locaux |
| Schémas, code métier/codec/stockage et dépendances | Inchangés face à #30 |
| Liens locaux et `git diff --check` | PASS |
| Appareil, gestes humains, SAF Android réel | NON TESTÉ |

Total : **992 exécutions**, sans échec, erreur ni test ignoré. Les variantes
Android exécutent les mêmes suites deux fois. Dix cas croisés Android et deux
cas SQLite sont nouveaux pour l’intégration ; les 32 tests de #22 et toutes les
suites de #30 sont conservés. La compilation signale seulement les bibliothèques
natives non strippées, empaquetées telles quelles ; aucune dépendance n’est changée.
Les avertissements disponibles peuvent différer en CI selon les métadonnées réseau :
consulter aussi le run du commit publié, sans assimiler CI source et CI intégrée.
Les previews sont compilées, pas rendues. Aucun résultat physique n’est inféré.
