# ADR 036 — Réconciliation Android / domaine configurable

Statut : intégration Git isolée, en revue. **Aucune activation fonctionnelle des séances mixtes**, aucun essai physique revendiqué.

## Sources et graphe

Ancêtre commun des branches : `e4c7e56817baa3f5a5a366bdf5895b3d38f6b798` (`main` à la création).

- Ligne Android : #23 → #24 → #25 → #27 → #28 → #29 → #30, puis fusion contrôlée de #22 dans #32, tête `2c3afeb81982bf9e0a0fa12d0abe3e6bbf7fa9c5`.
- Ligne domaine : #33 (`a70c05f07175664b94283cb8287b1070a941a7a1`) → #34 (`af3598abff20bfc1960ae802cae33bf80d6f0c2b`) → #35 (`8a763e451bdd793ad78145ebc52f846a1e83bb73`).
- #36 : commit de réconciliation à **deux parents**, d'abord #32 puis #35. PR en brouillon, ciblant `codex/integrate-precision-equipment`, sans modification ou fusion des branches sources.

## Résolution des écarts

Les nouveaux fichiers Kotlin du domaine configurable (`core:model` et `core:scoring`) n'écrasent aucun fichier des fonctionnalités Android de #32. Le scoring mixte conserve son paquet séparé et n'influence pas les calculs historiques.

`docs/README.md` : regrouper les ADR 024–027, 029–032, 033–036, le catalogue, les sauvegardes et le protocole Pixel.

`docs/modele-donnees-v1.md` : conserver la description **Room V2 et ses dix tables** de #32 tout en ajoutant les contrats autonomes `EndConfiguration` et `ConfiguredSession` des #33–#35. Ne pas reprendre l'ancienne description Room 1 de la branche métier. Mettre à jour l'indication devenue obsolète sur l'absence d'interface SAF.

`docs/regles-score.md` : retenir la documentation de calcul mixte de #34, absente de #32.

Les ADR historiques des deux branches sont conservés sans fusion ni réécriture. Aucun schéma Room, codec V1/V2, DAO, migration, interface Android, dépendance ou contrat de scoring historique n'est modifié.

## Garanties des contrats

- `Session` reste homogène et reste l'unique agrégat accepté par le stockage Android Room V2.
- `ConfiguredSession` représente des configurations **immuables par volée** et leurs origines, avec conversion depuis une `DomainArchive` **V1 préalablement validée** et complète. La conversion n'utilise jamais un profil « similaire », une édition devinée ou une date historique inventée.
- `DomainArchiveV2` reste **distincte** de `DomainArchive` : les arcs, jeux de flèches et références opaques demeurent dans le parcours V2. Une lecture historique V1 explicite du magasin, lorsqu'elle est demandée séparément, n'autorise ni projection silencieuse de V2, ni suppression de catalogue.
- Les archives V1/V2 et `scoringContractVersion = 1` restent inchangés. Aucune sauvegarde V1/V2 ne peut prétendre représenter une séance mixte.
- Le registre d'identités, les transactions atomiques, les conflits `expected/proposed`, les rollbacks, les traces, les décisions et les protections contre les callbacks obsolètes restent ceux de #32.

## Preuves ajoutées

- Test natif sur **fichier SQLite Room V2** peuplé : séance close et traces, matériel, réouverture, conversion depuis la lecture historique V1 distincte, statistiques, égalité intégrale des dix tables avant/après.
- Test sur fichier Room V2 : création d'une prochaine volée *en mémoire seulement*, aucune ligne `ends` ni autre table modifiée.
- Golden JSON V1 : conversion fidèle, profil et édition, références opaques et bytes originaux inchangés.
- JSON V2 : matériel réel, lecture/réencodage, rejet par décodeur V1 et préservation intégrale des références.
- JSON V2 avec catalogues explicitement vides : références opaques préservées, sans création implicite de fiches.
- JSON V2 à profil officiel contradictoire : rejet intégral typé.

Les suites historiques des deux parents restent exécutables ensemble sur cette branche, notamment migrations Room 1→2, lecture/import V2 transactionnels, traces et validations de scoring. Les nouveaux tests ne remplacent pas ces preuves antérieures.

**Exécution réelle des tests/CI** : à relever dans les rapports GitHub sur le SHA final. Ne pas déduire un nombre de tests ou une réussite de l'addition des suites parentes.

## Limite ADB

Lors de la création initiale via GitHub, l'accès ADB au poste Fedora et au Pixel 8 n'était pas disponible depuis cet environnement d'intégration. **Aucun appareil n'a été interrogé ni modifié.** Aucun APK installé et aucune migration Room physique lancée. Le protocole de la #32 reste à suivre ultérieurement, en installation indépendante, avec confirmation humaine séparée. Les gestes, la rotation physique et les fournisseurs SAF restent NON TESTÉS ici.

## Étapes suivantes — sans démarrage automatique

1. Définir le **contrat d'archive successeur** représentant une configuration initiale, une configuration effective pour chaque volée, la provenance et le catalogue complet ; décider les versions indépendantes du scoring et de Room ; lire V1/V2 sans altération et sans écrasement.
2. Étendre **Room** depuis la vraie version publiée (2 sur cette branche) : propriétaire/configuration/provenance par volée et migration non destructive 1→2→suivante, profils/éditions exacts, historique intact, comparaison complète sous verrou.
3. Ajouter collecte/import **atomiques** de l'archive successeur, idempotence, concurrence, rollback après INSERT et état de commit incertain.
4. Adapter enfin les contrôleurs et écrans Android avec candidat de prochaine volée, héritage/changement volontaire, exclusions visée/matériel/SAF et réessai strict, sans activer de flux producteur avant persistance/export fidèles.

## Arbitrages ouverts

- Volées FIXED vides, incomplètes ou excédentaires ; validation et clôture.
- Plafonds UI pour comptes personnalisés, presets d'épreuves et terminologie « personnalisé » / officiel.
- Choix du maximum affiché, dénominateurs, arrondis, groupes et limites numériques des séances mixtes.
- Correction exceptionnelle d'une configuration existante, raison obligatoire et journal non falsifiable.
- Révisions post-clôture et rétention des anciennes décisions ; version et provenance de capture persistées.

Aucun arbitrage n'est implicitement décidé par cette fusion.


## Reprise locale Fedora — 21 octobre 2026

La reprise part du commit GitHub exact
`f2b688754e764945fd189fa03f59691e2837a9c9`, sans reconstruire la réconciliation.
L’inspection des arbres vérifie les deux parents annoncés, l’ancêtre commun et
les 248 fichiers : seules README/modèle de données divergent dans les deux lignes ;
les résolutions supplémentaires sont l’ADR 036 et les deux suites de coexistence.
Tous les fichiers Kotlin de production des deux parents sont conservés à l’identique.
Les modèles nouveaux sont autonomes ; aucun appel Android/Room/codec n’accepte
une `ConfiguredSession` comme une `Session` homogène ou une archive.

Le [Quality gate initial](https://github.com/LounaMaili/LMarchery/actions/runs/39540462659)
a réussi sur ce SHA, toutes les étapes tests/build/lint incluses. La première
exécution locale de `./scripts/dev-check.sh --rerun-tasks --console=plain` réussit
également : **1 139 exécutions**, zéro échec/erreur/test ignoré, dont les six tests
croisés de cette PR. Les rapports XML sont inspectés séparément de la CI.

### Preuves renforcées, sans changement fonctionnel

Les deux tests de lecture Room V2 reçoivent des triggers interdisant INSERT,
UPDATE et DELETE dans les dix tables. Ils refusent désormais aussi une tentative
d’écriture sans changement de valeur ; une simple égalité nette des lignes ne
suffirait pas à démontrer l’absence d’écriture.

La première base contient aussi un profil WA non référencé, importé par V2 :
la lecture historique V1 fournit les profils des séances seulement, tandis que
V2 conserve ce profil supplémentaire et le catalogue. Aucune égalité artificielle
des deux périmètres ni projection silencieuse n’est imposée.

Un septième test de coexistence construit une **vraie base V1** depuis la DDL/hash
figée, avec données synthétiques dans les huit tables. La fabrique réelle migre
vers V2 ; lignes et DDL anciennes restent identiques avant ajout du catalogue.
Le test vérifie ensuite exactement les deux nouvelles identités matériel, ferme
et rouvre, puis crée une configuration poulies et un impact **en mémoire seulement**.
Jugement 9→10, repositionnement motivé vers 8, ancienne décision invalidée et traces
sont contrôlés sous ce profil effectif. Les dix tables, le catalogue, les archives
V1/V2 et leurs octets restent identiques après ces opérations et une nouvelle
réouverture. Même un UPDATE sans changement est prouvé refusé par les gardes SQL.
Les six tests initiaux et toutes leurs garanties sont conservés.

Le backlog est aligné sur le SAF V2 déjà présent ; le modèle de données distingue
les périmètres d’import V1 et V2. Les ADR historiques des parents restent datés
de leurs livraisons ; leurs descriptions de Room 1 ne sont pas présentées comme
l’état de cette branche combinée.

### Diagnostic ADB réellement effectué

Commandes en lecture seule : `adb version` et `adb devices -l`, avec le binaire
`/home/erwan/Android/Sdk/platform-tools/adb`. Version 37.0.1 ; le serveur local
démarre, la liste des appareils est **vide**. Aucune connexion Wi-Fi tentée,
aucun accès à une application ou base du Pixel, aucune installation, lancement,
permission ou manipulation d’écran. Tests applicatifs et essais physiques :
**NON TESTÉS**. Une future installation exige un package/applicationId et un
stockage synthétiques séparés, vérifiés avant toute écriture ; l’APK normal
`com.lmarchery` n’est pas isolé et ne doit pas remplacer l’installation existante.

### Résultats finaux locaux

Après renforcement, `./scripts/dev-check.sh --rerun-tasks --console=plain` réussit :

| Rapport XML / contrôle | Résultat |
|---|---|
| Modèle Kotlin | 135 PASS |
| Scoring et reconstruction | 331 PASS |
| Codec V1/V2 | 38 PASS, dont les 4 coexistences |
| SQLite natif disque | 180 PASS, dont les 3 coexistences |
| Android JVM | 228 debug + 228 release PASS |
| Assemblages debug app/database | PASS |
| Lint app/database | PASS, zéro erreur, 5 avertissements GradleDependency déjà présents au run initial |
| Liens documentaires locaux | 238 PASS |
| `git diff --check` | PASS |

Soit **1 140 exécutions** sans échec, erreur ni test ignoré ; les variantes Android
répètent des suites communes, ce ne sont pas 1 140 tests uniques. Les sept
coexistences passent aussi de façon ciblée avec :

```bash
JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew \
  :core:database:jvmTest --tests '*ConfiguredSessionV2CoexistenceTest' \
  :core:export:test --tests '*ConfiguredArchiveCoexistenceTest' --console=plain
```

Les schémas exportés 1 et 2 sont identiques à ceux de #32. Le complément local
ne modifie aucun code de production, modèle, archive, build ou workflow par rapport
au commit initial de #36 (`f2b6887`). Les SHA-256 des deux schémas restent
respectivement `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`
et `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`.
Aucune anomalie fonctionnelle n’a été reproduite ; les corrections concernent les
preuves de test et les indications documentaires obsolètes. Le nouveau commit
est soumis au Quality gate de #36 avant livraison ; le résultat initial ne lui
est pas substitué.
