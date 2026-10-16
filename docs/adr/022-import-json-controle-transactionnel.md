# ADR 022 — Import JSON contrôlé et transactionnel

## Portée et distinction des étapes

L’export de l’[ADR 021](021-export-json-versionne-android.md) reste inchangé :
JSON UTF-8, schéma archive 1, contrat de scoring 1 et schéma Room 1. L’import
restaure uniquement les séances et profils effectivement présents dans ce contrat,
avec leurs volées, impacts, dates, décisions et traces. Le matériel reste référencé
par UUID opaque ; aucun modèle futur, ancien format ou migration n’est inventé.

Trois étapes restent distinctes : validation indépendante du fichier, prévisualisation
face à SQLite, puis confirmation volontaire et revalidation dans une transaction
écrivain. Un domaine reconstructible ou un aperçu n’est jamais une autorisation
d’écraser une identité existante. Ni UUID ni `updatedAt` ne prouvent la filiation.

## Planificateur pur et politique

`planArchiveImport(received, known)` dans `core.scoring.archive` opère uniquement
sur des `DomainArchive` validés, sans Android, JSON ni SQL. L’ordre des racines et
profils découverts n’affecte pas la comparaison ; les listes métier d’enfants et
l’ordre des zones restent significatifs.

- Nouvelle : identité de séance absente. Les collisions éventuelles de ses
  descendants ou de son profil bloquent néanmoins tout le fichier.
- Identique : égalité de tout le contenu métier, dates et révisions comprises.
- Conflit : toute divergence de contenu, d’édition, de type d’entité ou de parent.
  Un même score avec une autre provenance est différent. Une trace retirée ou
  réécrite, un autre point, titre ou `updatedAt` reste un conflit.

Les UUID/index ne changent jamais. La définition complète et l’édition du profil
sont comparées, les profils publiés étant déjà revérifiés par `reconstructArchive`.
Les personnalisés structurellement valides gardent leur identité et leur définition.

Une archive vide est refusée avec `EMPTY_ARCHIVE`. Un profil sans séance associée
est refusé avec `UNREFERENCED_PROFILE`, même s’il est déjà connu : pas d’import
autonome de profils ni de données ignorées silencieusement. Les conflits sont
énumérés avec UUID, code et chemin ; aucun choix d’écrasement n’est proposé.
Le nombre de conflits compte les problèmes d’identité, pas uniquement les racines.
Les listes du plan fourni par Room sont possédées et non modifiables, y compris
si un candidat est construit à partir de listes mutables par un appelant.
L’écran affiche les vingt premiers diagnostics et le nombre des autres ; le plan
conserve tous les conflits, qui bloquent toujours l’intégralité du fichier.

## API Room et absence d’écrasement

```kotlin
suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult
suspend fun importArchive(confirmed: ArchiveImportPreview): ArchiveImportResult
```

`previewImport` reconstruit la source puis lit un instantané lecteur. `Ready`
contient un candidat immuable avec archive exacte et plan informatif, qui peut
contenir des conflits. Les autres résultats sont `NeedsIntervention` et
`TechnicalFailure`. Le candidat n’est pas une preuve inviolable : même construit
par un appelant, il est soumis à tous les contrôles de la transaction finale.
Il n’offre pas de copie permettant de remplacer sa source après prévisualisation.

La comparaison locale lit conservativement **toutes les séances et tous les
profils stockés**, y compris profils non référencés et identités de séance
orphelines. Une donnée locale illisible bloque l’opération plutôt que d’être
réparée ou ignorée. Les mêmes mappers/reconstructeurs que `read` vérifient
versions, compteurs, positions, dates, décisions, traces et registre d’identités.
La liste des UUID des quatre tables d’entités doit correspondre aux objets lus :
un descendant SQL inaccessible ou une collision entre types est également refusé.
Les identités enregistrées correspondant au fichier sont contrôlées séparément,
y compris une identité orpheline `END`/`IMPACT` non atteignable depuis les racines.

`importArchive` utilise **une seule `immediateTransaction`** pour tout le fichier :

1. Vérifier disponibilité et annulation ; reconstruire à nouveau la source.
2. Relire le contenu local et les identités, recalculer le plan dans la transaction.
3. Exiger zéro conflit et le même plan que celui confirmé, y compris les UUID
   des séances nouvelles/identiques et profils à ajouter.
4. Avant toute insertion, refuser le changement de plan ; nouvelle sélection et
   prévisualisation nécessaires, aucun retry sur un attendu remplacé implicitement.
5. Insérer uniquement identités absentes, profils nécessaires absents, nouvelles
   séances et descendants, via les helpers existants (`INSERT`/`ABORT`).
6. Relire/reconstruire le tout, vérifier toutes les séances reçues comme identiques
   et l’absence de changement des profils/séances déjà locaux. Les nombres de
   racines/profils doivent aussi correspondre exactement aux ajouts prévus.
7. Vérifier le contexte d’annulation d’origine avant de laisser committer.

Après insertion, toute incohérence **lève une exception** hors de la transaction ;
aucun retour de conflit ne peut committer un préfixe d’insertions. Le résultat
`NeedsIntervention`/`TechnicalFailure` est construit seulement après le rollback.
Aucun `UPDATE`, `DELETE`, `REPLACE`, `IGNORE`, UPSERT ou migration destructive
n’est utilisé par l’import. Les séances identiques n’entraînent aucune écriture.
Une évolution indépendante, sans effet sur le plan ni les identités reçues,
peut rester compatible ; elle est conservée dans l’état local relu sous verrou.

Résultats : `Imported(added, identical)` après relecture et commit ; `Conflict`
pour un plan conflictuel/périmé avant insertion ; `NeedsIntervention` pour une
incohérence ; `TechnicalFailure` pour une défaillance SQL/accès. Une annulation
coroutine est propagée. L’API accepte un no-op intégralement identique (zéro ajout),
mais Android indique directement « Aucune importation nécessaire » dans l’aperçu.

Deux rédacteurs sont sérialisés avant leur lecture. Si une séance nouvelle est
apparue depuis l’aperçu, même identique, le plan change : refuser cet ancien aperçu.
Une nouvelle sélection retrouve les identiques sans doublon. Un changement de
séance connue ou de profil ne peut jamais être résolu par date.

## Lecture et parcours Android

Références officielles consultées le 16 octobre 2026 :
[SAF](https://developer.android.com/training/data-storage/shared/documents-files),
[OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument),
[ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)).

Depuis la liste, « Importer une sauvegarde » ouvre `OpenDocument`. Le filtre
`*/*` permet aussi un fichier mal étiqueté par un fournisseur : nom/MIME n’accordent
aucune confiance. Seul l’URI explicitement choisi est lu par le resolver applicatif,
sans chemin supposé, permission globale ni permission URI persistante.

`BoundedDocumentReader` alloue un petit buffer, lit au plus **16 MiB + un octet
de détection**, refuse le dépassement avant ajout au buffer et exige une fermeture
réussie. Aucune allocation selon la taille déclarée ni `readBytes()` illimité.
Flux nul/inaccessible, lecture interrompue et fermeture défaillante bloquent la
prévisualisation. `ArchiveJsonCodec.decode(bytes)` reste l’unique décodeur :
aucun champ, version, provenance, historique ou contexte civil n’est corrigé.
Les rejets affichent code et chemin sans journaliser les octets ou noms de séances.

Le ViewModel possède `ImportCoordinator`, avec lecteur, repository, scope et
dispatcher injectables. Lecture, décodage et SQL sont hors thread UI. Le magasin
Room/repository reste unique par processus ; aucune Activity n’est retenue.
Les octets, l’URI et le domaine prévisualisés restent en mémoire jusqu’à annulation
ou résultat. La confirmation ne rouvre jamais le fichier.

Le verrou `archiveOperationLocked` généralise celui de l’export ; l’alias de
lecture `exportLocked` et les méthodes historiques restent compatibles. L’import
requiert la liste, une interface prête sans `PendingSave` ni sélection/correction,
et aucun import/export actif. La navigation et les écritures de séance sont
bloquées pendant le parcours, aperçu compris. Un candidat de correction non
soumis n’est jamais intégré. Aucun double appui n’est mis en file.

États : disponible, sélection, lecture, validation/comparaison, prévisualisation,
conflit, confirmation, importation, succès, annulation, erreur et interruption non
confirmée. L’aperçu donne comptes de séances nouvelles/identiques/conflits/profils.
Une confirmation « Importer les N nouvelles séances » est offerte seulement sans
conflit. L’aperçu identique se ferme sans écriture ; le conflit impose une nouvelle
sélection. Le succès n’est affiché qu’après acquittement SQL, puis la découverte
et relecture habituelles actualisent la liste. Les séances terminées restent
consultables en lecture seule, sans nouveau parcours de réouverture.

La rotation conserve le ViewModel et sa requête SAF réclamée une fois. Après mort
du processus, pas de candidat durable ni reprise automatique. Un marqueur restauré
ou un callback ancien signale une interruption ; sans état restauré, l’écran
repart disponible sans prétendre à un succès. Une erreur/annulation pendant SQL
est non confirmée : le commit peut avoir précédé la perte d’acquittement. Relire
la liste et sélectionner à nouveau le même fichier établit l’état réel ; aucun
rollback d’un commit acquis n’est prétendu. L’idempotence évite les doublons.

## Validation et limites

Tests purs : nouveau/identique/mixte, dates, provenance, histoire, parents, profils
et portée vide/non référencée. Tests SQLite natifs sur disque : contenus complets,
idempotence sous triggers interdisant INSERT/UPDATE/DELETE, ajouts sans écriture
sur l’existant, collisions, modifications entre aperçu/confirmation, deux magasins,
rollback après première insertion, contrainte tardive, altération par trigger et
annulation après une vraie insertion. Fermeture/réouverture et réexport vérifient
la fidélité du fichier ; tous les tests historiques sont conservés.

Tests du coordinateur/flux : budgets, erreurs, annulations, confirmation explicite,
doubles commandes, candidat conservé, interruption, acquittement et verrou commun.
La campagne Pixel est consignée séparément dans les [tests terrain](../tests-terrain-v1.md#protocole-import-json-pr-20) ;
les simulations JVM ne qualifient pas tous les fournisseurs SAF ou gestes réels.

Aucun écrasement, fusion, suppression, ancien format, migration, chiffrement,
signature, cloud, nouveau profil ou modification du scoring. Les mêmes limites
historiques demeurent : pas de journal exhaustif des décisions à position constante,
ni preuve d’authenticité d’un fichier indépendamment cohérent. Les versions
inconnues, données futures et changements IANA restent des refus explicites.

*16 octobre 2026*
