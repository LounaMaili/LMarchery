# ADR 021 — Export JSON versionné des séances Android

## Portée

Rendre opérationnel le contrat de l'[ADR 015](015-sauvegarde-versionnee-reconstruction.md)
pour exporter **toutes les séances persistées** et leurs profils référencés. Les
modèles, règles, éditions réglementaires, versions d'archive/scoring **1** et
schéma Room **1** restent inchangés. Aucun import utilisateur, fusion, suppression
ou arbitrage par `updatedAt` n'est ajouté.

Le fichier ne représente que les modèles implémentés : séances, volées, impacts,
profils complets, décisions et repositionnements. Les UUID opaques de matériel
déjà présents restent des références ; ils n'inventent pas de fiches matériel,
presets, photographies ou données futures. Les décisions successives à position
constante ne sont pas un journal exhaustif : la décision courante et celles
conservées par les repositionnements sont exportées exactement comme stockées.

## Codec léger et explicite

Le module JVM `:core:export` dépend du domaine/scoring et de **Gson 2.13.2**.
Il suit le toolchain/cible JVM 21 des modules purs existants ; Android consomme
ses classes comme celles du domaine. Aucune dépendance existante n'est mise à jour.

Seules les API JSON de flux/arbre sont employées. Aucun modèle Kotlin ou métier
n'est sérialisé par réflexion, `Unsafe` ou inférence de constructeur. Les
adaptateurs de `ArchiveJsonMapping.kt` nomment explicitement chaque propriété
du snapshot V1. Gson fournit la grammaire et les tokens ; l'adaptateur ajoute
types exacts, clés uniques, budgets et correspondance avec le contrat Kotlin.

Références primaires vérifiées le 16 octobre 2026 :

- [JsonReader de Gson 2.13.2](https://github.com/google/gson/blob/gson-parent-2.13.2/gson/src/main/java/com/google/gson/stream/JsonReader.java) :
  mode `Strictness.STRICT`, limites de profondeur et lecture des tokens.
- [README de cette version](https://github.com/google/gson/blob/gson-parent-2.13.2/README.md) :
  compatibilité Java/Android et précautions sur la réflexion, non utilisée ici.
- [Storage Access Framework Android](https://developer.android.com/training/data-storage/shared/documents-files) :
  création d'un nouveau document sans écraser un fichier existant, destination choisie par l'utilisateur.

## Fichier V1 strict et déterministe

UTF-8 sans BOM, MIME `application/json`, extension `.lmarchery.json`. La racine
est `schemaVersion`, `scoringContractVersion`, `profiles`, `sessions`, avec les
versions de `ArchiveContractVersions`. Tous les noms sont ceux des snapshots,
sans ID Room, total stocké, date de profil ou date variable d'export.

Toutes les propriétés JSON sont obligatoires, y compris celles valant `null`
et les sept assertions lorsqu'un objet `assertions` est fourni. `assertions: null`
reste possible. L'absence d'une propriété n'est jamais interprétée comme son
`null` ou un défaut Kotlin. Les enums sont leurs noms exacts et sensibles à la casse.
Objets inconnus, propriétés inconnues et clés dupliquées (même après décodage
des échappements, même si la première valeur est `null`) sont rejetés.

Le lecteur vérifie les types JSON avant conversion : aucune chaîne numérique
acceptée comme nombre, ni entier comme booléen. Les `Int` sont des littéraux
décimaux sans fraction/exposant, dans leur plage. Les `Float` sont des nombres
JSON finis, écrits avec leur représentation décimale `Float.toString` et lus
directement en `Float`, sans conversion intermédiaire en `Double`. Le lexème
numérique est conservé par l'adaptateur ; `-0.0` ne devient pas `0`. Un débordement
ou un nombre non nul sous-débordant vers zéro est rejeté. Les fractions reçues
sont arrondies selon la conversion JVM en `Float`, type du contrat existant.

Les instants sont émis en UTC `Z` avec nanosecondes. Les offsets explicites valides
sont acceptés à la lecture via `UtcTimestamps`, puis normalisés comme instants.
Le triplet civil conserve heure locale, fuseau IANA et offset choisi, y compris
lors d'une répétition d'heure. Unicode est conservé ; UTF-8 invalide et surrogates
isolés sont rejetés. Les tests comparent les bits des coordonnées, rayons,
références et distances, ainsi que l'ensemble des données reconstruites.

L'ordre des propriétés est fixé par les adaptateurs, indentation de deux espaces.
Les listes métier gardent leur ordre ; la collecte trie séances et profils par
UUID. Deux encodages du même contenu produisent les mêmes octets. Le nom de fichier
proposé utilise la Clock UTC ; cette date n'entre pas dans le contenu JSON.

Budgets par défaut : **16 MiB**, profondeur **32**, **200 000 valeurs** JSON,
**20 000 éléments par tableau**, **64 propriétés par objet**, **16 384 caractères
UTF-16 par chaîne/clé**, **128 caractères par nombre**. Ces plafonds bornent la
lecture de données non fiables ; leur dépassement échoue entièrement. Ils bornent
aussi les exports proposés. Ils ne remplacent pas une future politique d'archives
volumineuses ; aucune troncature ni séance sautée n'est autorisée.

`ArchiveJsonCodec.decode` rend seulement `Accepted(snapshot, archive)` après
`reconstructArchive`, ou `Rejected(JsonIssue(code, path, detail))`. Le lecteur de
transfert interne n'autorise pas une intégration. Aucun domaine partiellement
valide n'est accepté. Les diagnostics ne journalisent pas les contenus utilisateur.

## Collecte globale transactionnelle

`RoomSessionStore.readArchive(): ArchiveReadResult` exécute découverte et lectures
dans **une seule transaction lecteur différée**, avec reconstruction de chaque
séance puis de l'ensemble. La découverte inclut les identités de séance orphelines.
Les définitions partagées sont dédupliquées par UUID après comparaison complète,
édition incluse. Un profil absent n'est pas substitué par le registre courant.

Une seule séance corrompue, identité orpheline, collision globale ou incohérence
de profil bloque tout l'export, avec chemin/UUID. Les séances en cours, partielles
et terminées sont incluses. Une base vide donne une archive vide au niveau de
l'API ; Android signale « Aucune séance enregistrée » avant le sélecteur.

Les résultats distinguent `Found`, `NeedsIntervention` et `TechnicalFailure`.
L'opération ne répare ni n'écrit aucune table. Un autre rédacteur peut enregistrer
après le début de la lecture : l'export conserve l'instantané cohérent capturé,
pas une composition de plusieurs révisions. Le test natif suspend la découverte,
actualise une séance et en ajoute une autre depuis un second magasin, puis
vérifie l'ancien état complet et, à la lecture suivante, le nouvel état.

## Validation avant sortie et parcours Android

Le coordinateur est détenu par le ViewModel. `ArchiveSource` expose la lecture
globale ; le codec reste indépendant d'Android. Avant d'ouvrir le sélecteur :
collecter → reconstruire → encoder → décoder strictement → reconstruire →
comparer l'intégralité avec le snapshot source. Les assertions, profils et traces
sont inclus. Aucun fichier externe n'est ouvert si cette chaîne échoue.

La commande « Exporter mes séances » affiche les phases disponible, préparation,
validation, choix, écriture/relecture, succès, annulation et échec. Elle explique
que le fichier est en clair et que l'import reste indisponible. Les opérations
longues s'exécutent sur `Dispatchers.IO`. Le verrou est acquis avant la coroutine ;
un double appui ou callback ne met pas un autre export en file.

Une opération de séance, un `PendingSave` ou une sélection/correction doivent être
terminés explicitement avant export. Les candidats ne sont jamais capturés comme
durables. Le contrôleur bloque les mutations/navigation pendant l'export ; les
autres rédacteurs restent traités par l'instantané Room, sans verrou global fictif.

`CreateDocument("application/json")` propose un nom UTC explicite et laisse choisir
la destination. Aucune permission globale de stockage, INTERNET ou CAMERA, ni
synchronisation propre à LMarchery. Le fournisseur peut être externe si choisi
dans Android ; l'application n'envoie rien d'elle-même. Le lanceur reste au niveau
racine Compose, indépendant de la visibilité de la carte dans la liste défilante.
Sa requête est réclamée une fois ; rotation et retour ActivityResult ne la doublent pas.

Seul le résultat du sélecteur est transmis au writer. L'adaptateur conserve le
`ContentResolver` applicatif, jamais une Activity. Il ouvre le nouveau document,
écrit, flush et ferme ; puis relit et compare **tous les octets**, avec bornage
du flux reçu. Une fermeture en erreur, un fichier tronqué/différent, une relecture
indisponible ou une interruption empêche le succès. Cette politique conservatrice
laisse donc certains fournisseurs sans confirmation possible. Le nom est obtenu
si disponible, la taille confirmée est celle des octets relus. SHA-256 est un
contrôle d'intégrité calculé après vérification, pas une signature d'authenticité.

Après échec, le document peut être vide ou incomplet : le message demande de ne
pas l'utiliser comme sauvegarde. Aucune suppression automatique, réparation,
écriture dans un autre URI ou nouvelle tentative sur le fichier précédent.
Recommencer ouvre un nouveau choix. L'annulation du sélecteur n'écrit aucun contenu.

Les octets préparés survivent à la rotation dans le ViewModel, pas à la mort du
processus. Un marqueur de présentation restaurable signale une interruption si
Android le restaure ; un ancien résultat sans contenu préparé ne provoque aucune
écriture. En l'absence de restauration d'état par Android, aucun succès n'est
inventé : l'écran repart disponible. Un fichier non acquitté peut subsister.

## Vérifications et limites avant import

Tests JVM : snapshots riches et indépendants, profils WA/personnalisés, M/X,
jugements de même score, décisions invalidées, déplacements successifs, Unicode,
limites Float, nanosecondes/DST, erreurs strictes et budgets. Tests SQLite natifs
sur disque : toutes tables inchangées sous triggers, corruption/orphelins,
concurrence, fermeture/réouverture. Une archive décodée est également créée séance
par séance dans un **magasin temporaire de test** distinct : preuve de portabilité,
pas une fonctionnalité d'import utilisateur.

Les tests du coordinateur et des flux simulés couvrent les phases, doubles appuis,
garde des actions de séance, annulation, destination inaccessible, écriture partielle,
fermetures, relecture et interruption. Le protocole Pixel 8 est décrit dans les
[tests terrain](../tests-terrain-v1.md#protocole-export-json-pr-19) ; un succès JVM
n'est pas une validation des fournisseurs Android.

Avant un futur import : définir sélection, bornage du flux source, intervention
sur conflits, référentiel connu, doublons, anciennes versions et changements IANA.
La validité indépendante d'un fichier n'autorise aucune réécriture d'une séance
locale. Pas de fusion par UUID/date, chiffrement, migration ou journal complet.

*16 octobre 2026*
