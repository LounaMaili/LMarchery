# ADR 041 — Prévisualisation et restauration transactionnelle des archives V3

Statut : planification pure et restauration locale implémentées. Aucune connexion
SAF V3 ou interface de séances mixtes, aucune fusion/auto-merge.
Base exacte #40 : `17a576cc5491b5e455e71db06c87f32e7e011147`.
PR en brouillon vers `codex/room-v3-configured-writes`.

## Audit et contrats conservés

Les [ADR 027](027-archives-room-v2-atomiques.md),
[037](037-contrat-archive-seances-configurables-v3.md),
[038](038-codec-json-v3-strict.md),
[039](039-room-v3-migration-lecture.md) et
[040](040-ecritures-room-v3-configurables.md) fournissent respectivement les
transactions d'archives V2, le domaine V3 complet, son codec strict, la collecte
Room et les révisions locales configurables.

Room reste **3**, avec les mêmes douze tables et migrations 1→2→3. Les trois
schémas exportés sont figés. Les contrats/codecs/golden JSON V1/V2/V3 et le
scoring 1 restent inchangés. L'archive n'est jamais une autorisation de révision
`expected/proposed` ni un conteneur homogène de substitution.

Le registre global comporte six catégories UUID v4 : profil, séance, volée,
impact, arc et jeu de flèches. Zones, décisions, traces et configurations n'ont
pas de nouvelle identité. Les propriétaires et l'existence physique sont audités,
ainsi que tous les profils/catalogues non référencés et descendants atteignables.
Une référence de matériel reste opaque ; aucun UUID absent n'est réparé, créé
implicitement ou remplacé par null.

## Planificateur pur

[`planArchiveImportV3(received, known)`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/archive/v3/ArchiveV3ImportPlan.kt)
compare deux `DomainArchiveV3` déjà validées, sans I/O, horloge, UUID généré,
réinterprétation de score ou projection V1/V2.

`ArchiveV3ImportPlan` expose six `ImportCategoryPlan` : `sessions`, `profiles`,
`ends`, `impacts`, `bows`, `arrowSets`, et des `ImportConflict` déterministes avec
UUID, code, chemin et détail. Chaque catégorie a `newUuids`, `identicalUuids` et
`conflictingUuids`, possédées, triées et non modifiables.

L'égalité compare type, parent et valeur complète : définition/édition de profil,
métadonnées et dates civiles, initiale/effectives, origines et héritages, volées
vides, ordre des enfants, décisions, X/M et traces. Un profil exact reste requis ;
un profil ressemblant ne remplace pas une identité différente. L'édition déclarée
fait partie de la définition connue, sans déduction depuis le registre actuel.

Une séance nouvelle dépendant d'un profil ou descendant contradictoire est classée
bloquée (`DEPENDENCY_CONFLICT`). Les catégories d'enfants décrivent leur contenu
et parent direct ; une valeur identique dans un conteneur conflictuel ne permet
pas d'en importer une partie. **Un seul conflit bloque toute la restauration**,
même si des racines indépendantes sont nouvelles. Aucun winner par `updatedAt`,
merge, suppression, adoption de descendant ou remplacement de capture.

Une archive entièrement vide, de matériel seul ou de profils seuls est admise.
Les racines absentes ne sont jamais une demande d'effacement. Des listes matériel
explicitement vides n'effacent pas le catalogue local. `isEmptyArchive`,
`hasAdditions` et `canImport` restent distincts.

## Aperçu exact, aucune écriture

[`RoomSessionStore`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/RoomSessionStore.kt)
ajoute :

```kotlin
suspend fun previewImportV3(archive: DomainArchiveV3): ArchiveV3PreviewResult
suspend fun importArchiveV3(confirmed: ArchiveV3ImportPreview): ArchiveV3ImportResult
```

L'aperçu revalide la source avec les budgets V3 par défaut, puis ouvre une seule
`useReaderConnection` / `deferredTransaction`. `collectArchiveV3` lit et reconstruit
l'état Room complet sur cette connexion : aucun discover/read en boucle ni appel
public indépendant au catalogue ou au store.

`ArchiveV3ImportPreview`, à constructeur interne, conserve source exacte, plan,
archive locale complète et empreintes des valeurs SQL des douze tables.
Les empreintes incluent types, longueurs et octets exacts des textes, NUL compris,
via [`StorageFingerprint`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/StorageFingerprint.kt).
Elles complètent l'égalité métier : les marqueurs `LEGACY_HOMOGENEOUS`/`CONFIGURED`
ne font pas partie de `DomainArchiveV3`, et deux lexèmes civils distincts peuvent
représenter la même valeur métier. Aucun de ces changements n'est accepté comme
un aperçu encore actuel. La copie des empreintes est aussi non modifiable.

Résultats : `Ready(preview)` avec plan éventuellement conflictuel pour inspection,
`NeedsIntervention(ArchiveV3StorageDiagnostic)` ou `TechnicalFailure(cause)`.
Une incohérence bloque l'archive entière ; jamais de résultat partiel. L'annulation
se propage. Aucun INSERT/UPDATE/DELETE ni normalisation ; une migration nécessaire
à la première ouverture reste la phase d'ouverture Room des ADR 039/040. Les
preuves de lecture sans DML commencent après cette ouverture, sous gardes SQL.

## Confirmation et transaction écrivain unique

[`RoomArchiveV3Import`](../../core/database/src/sharedMain/kotlin/com/lmarchery/core/database/RoomArchiveV3Import.kt)
utilise une seule `useWriterConnection` / `immediateTransaction`, partagée avec
les autres écrivains Room, sans boucle de créations à commits séparés.

1. Contrôler le Job d'origine et le cycle de vie ; revalider la source immuable.
2. Relire l'état complet sur **la connexion écrivain** de cette transaction et
   recalculer ses empreintes et le plan pur.
3. Exiger l'égalité avec **tout** l'état confirmé : domaine, SQL et plan. Toute
   divergence, même sur une fiche sans rapport avec l'archive, donne `Conflict`
   avant DML. Une confirmation contenant des conflits est aussi refusée.
4. Sans ajout, retourner `AlreadyPresent` sans DML, avec vide/identiques distingués.
5. Construire et valider l'union entière attendue avant INSERT, y compris les
   budgets cumulés. Les 20 000 racines admissibles d'une base ne permettent pas
   d'ajouter une 20 001e simplement parce que la source seule est petite et valide.
6. Insérer les seules identités nouvelles, profils/zones absents, séances et
   initiales, toutes les lignes de volées avant leurs captures héritées, puis
   impacts/décisions/repositionnements et matériel. Utiliser INSERT/ABORT existant,
   sans UPDATE, DELETE, REPLACE, IGNORE, UPSERT ou migration nouvelle.
7. Relire et reconstruire V3 sur la même transaction. Exiger l'égalité exacte
   avec l'union attendue et l'égalité des valeurs SQL préexistantes, en excluant
   seulement les nouvelles lignes attendues des empreintes. Vérifier les FK.
8. Contrôler l'annulation avant de laisser Room committer. Le succès n'est rendu
   qu'après acquittement de la transaction.

Tout échec après tentative DML **lève hors du corps transactionnel**. Les
exceptions ne sont converties en résultats qu'à l'extérieur : aucune branche de
conflit ou intervention retournée normalement après insertion ne peut committer
un sous-ensemble. Les triggers malveillants/tardifs sont aussi contrôlés par la
reconstruction et l'audit brut des anciennes lignes, y compris lorsqu'une
altération SQL serait équivalente après normalisation métier.

Le mapping et l'ordre des FK sont partagés avec l'insertion configurable de 040,
via `insertConfiguredRows`, sans utiliser les objets `Session`/`SessionSnapshot`
comme représentation intermédiaire. Les empreintes 040 sont factorisées sans
modifier leurs règles. Aucune des 59 preuves SQLite de 040 n'est retirée.

### Représentation et provenance des séances restaurées

**Toute nouvelle séance reçue via V3 est enregistrée `CONFIGURED`**, avec
représentation de ligne 3 et scoring 1, même lorsqu'elle provient d'une conversion
historique homogène. Ses origines `LEGACY_DERIVED` restent exactes, ainsi que ses
UUID, indices, dates, décisions et traces. Elle ne reçoit aucun choix humain,
configuration ou événement inventé. Les colonnes historiques de séance restent
les alias vérifiés de son initiale ; les impacts utilisent la capture effective.

Une séance locale identique déjà `LEGACY_HOMOGENEOUS` est **ignorée en écriture** :
sa représentation, ses alias et toutes ses lignes restent identiques. La
restauration ne la convertit pas implicitement. La distinction est vérifiée dans
l'aperçu SQL, puis sous verrou. Cette règle évite de rendre des captures reçues
modifiables rétroactivement par un parcours homogène.

La création normale de 040 exige toujours `CREATION` et refuse une origine
historique fabriquée. Seul cet import confirmé peut restaurer des origines
historiques légitimes d'une archive complète validée. Une révision locale ultérieure
d'une séance restaurée passe par `updateConfiguredSession(expected, proposed)` et
son validateur de transitions, jamais par un nouvel import plus récent.

## Résultats, idempotence et commits incertains

Tous les résultats conservent `preview`, donc la source et l'état exacts confirmés.
`ArchiveV3Counts` sépare les six catégories, descendants compris.

| Résultat | Contrat |
|---|---|
| `Imported(added, identical)` | Ajouts seuls, état entier contrôlé, commit acquitté |
| `AlreadyPresent(identical, emptyArchive)` | Aperçu actuel et aucune nouveauté ; zéro DML |
| `Conflict(diagnostic)` | Divergence de contenu/type/propriétaire, aperçu périmé ou plan bloqué ; aucun écrasement |
| `NeedsIntervention(diagnostic, commitOutcome)` | Incohérence `STORAGE`/`DOMAIN` avec chemin et diagnostic métier original si disponible |
| `TechnicalFailure(cause)` | Pas de tentative DML métier par cet appel ; aucune conclusion sur un appel antérieur |
| `CommitUnconfirmed(cause)` | Écriture tentée et confirmation de commit indisponible ; aucun retry automatique |

`ArchiveV3CommitOutcome` est `NOT_ATTEMPTED` ou `UNCONFIRMED`. Une panne technique
après tentative DML est conservativement incertaine, même si un test prouve que
SQLite a réellement annulé. Une perte d'acquittement après COMMIT acquis ne peut
pas annuler ce commit. L'annulation se propage via `ArchiveV3ImportCancelled`,
avec aperçu et issue conservés ; le Job appelant peut masquer cette exception,
ce qui impose de conserver le candidat avant l'appel.

La réconciliation reste explicite, comme en 027 : relire et préparer un **nouvel
aperçu**, puis confirmer volontairement. Si tout a déjà été appliqué, ce nouvel
aperçu conduit à `AlreadyPresent` sans DML. L'ancien aperçu reste périmé, y compris
après le succès de sa propre transaction. Il ne peut pas autoriser automatiquement
une autre opération sur une base actualisée. Après rollback vérifié, une nouvelle
confirmation peut réussir ; un troisième contenu conflictuel ne devient jamais
une fusion ni un remplacement.

## Compatibilité et frontières

Les imports V1/V2, leurs aperçus, codecs, golden et garanties demeurent inchangés.
Une V2 validée peut être convertie explicitement en V3 avec tout son catalogue et
tous ses profils. Une V1 seule reste matériel **non fourni**, via son type partiel
non exportable ; elle n'est jamais annoncée comme archive complète avec un
catalogue vide inventé. Aucun encodage ou projection V3→V1/V2 n'est ajouté.

Les anciennes sauvegardes/imports globaux refusent toute base contenant une
séance `CONFIGURED`, même homogène en apparence, sans omission ni substitution du
profil initial. Un import V3 de matériel/profils seuls sur une base exclusivement
historique conserve les parcours anciens et les octets V1 correspondants.
`readArchiveV3()` restitue au contraire tout le contenu, ancien et configurable.

Les racines sont relues dans l'ordre canonique UUID de Room, comme en 027/039 ;
l'ordre de transfert des racines n'est pas un nouvel ordre persisté. Les listes
significatives d'enfants et leurs indices sont conservés, même si leur ordre de
stockage diffère de l'ordre d'héritage par index. Les éditions, unités, chaînes
optionnelles, bits Float et nanosecondes ne sont pas réinterprétés.

Les budgets métier restent 20 000 éléments par collection, 200 000 cumulés et
16 384 unités UTF-16 par texte. Le coût d'audit est proportionnel à la base bornée,
volontairement conservateur. Les budgets de fichier/valeurs/profondeur JSON 038
restent distincts et devront être appliqués par SAF avant ce domaine.

Aucune connexion aux écrans/contrôleurs Android ou au sélecteur SAF V3. Aucune
nouvelle règle sportive, réouverture/clôture rétroactive, correction exceptionnelle
de configuration ou politique de fusion. Cette PR ne lance pas automatiquement
la prochaine étape d'intégration Android.

## Preuves et validation

Les tests purs couvrent les six catégories, contenu/propriétaires/origines exacts,
profils/éditions, collisions, références opaques, plans déterministes et immuables.
Les nouvelles suites de stockage utilisent **de vrais fichiers et transactions
SQLite natifs** ; leurs hooks observent après le `step()` natif, sans remplacer
les DAO/transactions par des doubles :

- Archive riche homogène/mixte, tous profils et matériel non référencés, captures
  vides/héritées/explicites/historiques, décisions, X/M et invalidations, réouverture
  et aller-retour JSON V3, conservation des octets historiques V1/V2.
- Aperçus strictement lecteurs et idempotence sous triggers interdisant tout DML,
  coexistence sans réécriture des anciennes lignes, véritable fichier V1→V3.
- Identités inter-types, profils/éditions/propriétaires/captures contradictoires,
  histoire omise d'une racine connue, confirmations V1/V2 devenues interdites.
- Aperçu périmé par révision extérieure, marqueur SQL ou lexème civil équivalent ;
  deux confirmations concurrentes : un succès intégral, un conflit. Instantané
  lecteur unique pendant un vrai commit concurrent sur le catalogue.
- Pannes après INSERT réel, décisions/anciennes lignes altérées par trigger,
  orphelin et FK tardifs : rollback intégral et conservation de toutes les tables.
- Annulations avant/après DML, perte d'acquittement et annulation après vrai COMMIT,
  réouverture prouvant l'état, nouvel aperçu sans duplication, réussite après
  résolution explicite. Le hook vise le COMMIT métier, pas l'invalidation Room.
- Budgets source et union, magasin fermé et corruption : diagnostics sans archive
  partielle, sans troncature, réparation ou réouverture implicite.

Validation : `./scripts/dev-check.sh --rerun-tasks --console=plain`, inspection
XML, SQLite disque et FK/intégrité, builds et lint Android, empreintes des schémas
et migrations/golden inchangés, liens locaux et `git diff --check`, puis Quality
gate GitHub sur le commit publié exact.

Les éventuels tests Pixel doivent être rapportés séparément : nouvel applicationId,
UID/répertoire distincts et bases strictement synthétiques, sans lecture ni mise
à jour de l'application existante. Une instrumentation directe des API ne prouve
pas un parcours UI/SAF V3 ni une précision tactile humaine.

*22 octobre 2026*

### Résultat local du 22 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : PASS, trois étapes
`BUILD SUCCESSFUL`, JDK 21 complet, SDK 35 et Build Tools 34.0.0.
XML inspectés : modèle 135, scoring/reconstruction **422**, codecs 92,
SQLite natif disque **339**, Android JVM 228 debug + 228 release.
Soit **1 444 exécutions**, zéro échec/erreur/test ignoré ; les variantes Android
partagent des suites, il ne s'agit pas d'autant de tests uniques.

Les **62 nouveaux tests** comprennent 15 tests purs, 15 restaurations/coexistences,
18 conflits/aperçus et 14 transactions/interruptions/budgets sur SQLite natif.
Les 292 tests disque de la base #40 restent exécutés, avec les suites historiques
et golden V1/V2/V3. Builds debug app/database et lint : PASS, zéro erreur,
cinq avertissements GradleDependency préexistants (3 app, 2 database), messages
KMP/AGP non masqués et aucune dépendance actualisée.

302 liens documentaires et `git diff --check` : PASS. Les 173 anciens fichiers
protégés vérifiés sont inchangés : schémas/migrations, codecs/golden, modèle,
scoring historique et Android. Empreintes SHA-256 des schémas conservées :

- 1 : `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`.
- 2 : `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`.
- 3 : `3333739e70b6ffc1781df44624a152ef6940c25f2d4299a23b34b8f41e41a2b7`.

La CI est vérifiée séparément sur le SHA publié exact. Les éventuels résultats
sur appareil sont consignés séparément dans le rapport/description de PR, sans
confondre instrumentation directe, parcours Android futur et essais humains.
