# ADR 017 — Mise à jour transactionnelle des séances Room

## Contexte

L'[ADR 016](016-persistance-locale-room.md) fournit création et lecture sans
écrasement. Une séance doit maintenant être sauvegardée pendant son déroulement.
Un instantané valide isolément ne prouve ni sa filiation, ni la conservation de
l'historique connu. Une date plus récente ne suffit pas à résoudre un conflit.

## API et contrôle optimiste

```kotlin
suspend fun RoomSessionStore.update(
    expected: DomainArchive,
    proposed: DomainArchive,
): SessionUpdateResult
```

Chaque archive contient exactement une séance et son profil complet, édition
comprise. Le couple `Found(session, profile)` de `read` permet de capturer l'état
attendu avec `snapshotArchive`, puis `reconstructArchive` sans référentiel.
Conserver cet état jusqu'au résultat de sauvegarde ; après `Updated`, le nouvel
état attendu est `proposed`. Il ne faut pas recréer `expected` depuis une relecture
récente pour forcer l'enregistrement d'une ancienne proposition.

Le profil d'enveloppe justifie `DomainArchive` plutôt que `Session` seul :
`TargetType` ne contient pas l'édition. Définition et édition doivent rester
strictement identiques à celles enregistrées, y compris pour un profil personnalisé.
Les huit profils publiés restent inchangés. Aucun changement d'API existante,
de contrat d'archive ou de version de scoring n'est nécessaire.

Les résultats sont :

- `Updated(uuid)` : nouvelle révision enregistrée et intégralement vérifiée ;
- `Unchanged(uuid)` : état attendu exact, proposition déjà identique, aucune écriture ;
- `NotFound` : séance absente ; une identité séance sans sa ligne est une incohérence ;
- `Conflict(diagnostic)` : état stocké différent de l'attendu, ou nouvel UUID déjà possédé ;
- `NeedsIntervention(diagnostic)` : donnée stockée incohérente, transition interdite,
  profil changé ou portée d'archive incorrecte ;
- `TechnicalFailure(cause)` : problème d'accès, magasin fermé, erreur SQL, etc.

L'appelant traite explicitement les résultats ; aucun conflit n'est fusionné ni
réessayé automatiquement. Les annulations de coroutine sont propagées, avec
contrôles coopératifs du contexte de l'appelant avant accès, dans la transaction,
avant écritures et avant commit. Room change de contexte coroutine : vérifier
seulement le contexte courant de la transaction ne suffit pas. Une annulation observée avant commit entraîne le rollback ; une annulation
survenant après un commit ne peut pas annuler une sauvegarde déjà effectuée.

## Une transaction écrivain pour toute l'opération

`immediateTransaction` sérialise les rédacteurs avant leur lecture. Dans cette
transaction :

1. Relire le profil, la séance, les enfants, décisions, traces et registre d'identités.
2. Reconstruire et revérifier l'intégralité du domaine.
3. Comparer **tout** le contenu à `expected`, avant même de rechercher `Unchanged`.
4. Reconstruire `proposed` isolément et valider son évolution par rapport au courant.
5. Vérifier l'absence de collisions globales des nouveaux UUID.
6. Appliquer les ajouts et modifications SQL explicites.
7. Relire et reconstruire, puis exiger l'égalité complète avec `proposed` avant commit.

Une divergence de contenu avec un `updatedAt` identique est un conflit. Une
proposition identique au courant avec un `expected` obsolète reste un conflit.
Deux rédacteurs partis du même état ne peuvent pas enregistrer deux révisions
différentes : le second doit relire et résoudre explicitement son conflit.
La vérification finale lance une exception en cas d'écart, ce qui annule aussi
les écritures dans toutes les tables enfants et le registre global.

`reconstructArchive(snapshot, known)` garde exactement la politique conservatrice
de l'[ADR 015](015-sauvegarde-versionnee-reconstruction.md) : toute divergence
connue est un conflit. Il n'est ni modifié ni utilisé comme autorisation de
révision locale. L'autorisation vient du validateur de transition ci-dessous.

## Validation indépendante de Room

`validateSessionUpdate(expected: Session, proposed: Session)` est une fonction
pure de `core:model`, sans SQL, profil implicite ou ouverture de preuves opaques.
Elle réutilise les contrôles historiques de `Session`, `End` et `ArrowImpact`
qui servent déjà aux copies et remplacements, puis impose les restrictions
propres à ce parcours de sauvegarde :

- même UUID de séance, même profil et même création ; dates de révision monotones ;
- conservation de toutes les volées et de tous les impacts existants dans leur
  parent initial, même sans trace ; ni suppression ni déplacement entre volées ;
- démarrage `DRAFT → IN_PROGRESS`, clôture `IN_PROGRESS → COMPLETED`, sans retour ;
- `startedAt` enregistré conservé ; date de validation et clôture conservées ;
- événements nouveaux non antidatés par rapport à la révision précédente ;
- décisions contextualisées, points inchangés sans repositionnement explicite,
  origine de position conservée et préfixe intégral des traces préservé.

Plusieurs opérations valides peuvent être enregistrées ensemble : une révision
`DRAFT → COMPLETED` doit être rejouable par `start` puis `complete` avec ses dates
explicites. Une intégration d'enfant plus ancien garde les maximums des parents.
Les constructeurs vérifient toujours les dates parent/enfant et les valeurs dérivées.

Une volée `EDITING` accepte ajouts, jugements, corrections exceptionnelles et
repositionnements. Une volée `VALIDATED` conserve ses impacts à l'identique ; ses
métadonnées propres restent modifiables comme le domaine le permet. Une séance
`COMPLETED` conserve tous ses enfants et sa clôture ; seules ses métadonnées
propres autorisées par `Session.copy` restent modifiables. Aucune nouvelle
obligation de valider toutes les volées avant clôture n'est ajoutée.

## SQL sans migration

Les huit tables et l'export de schéma Room **1** restent identiques. Le mapping
est partagé entre création et révision pour préserver couples seconde/nanoseconde,
triplet civil, bits IEEE 754 des `Float`, positions et nombres d'enfants.

Les DAO utilisent `UPDATE` et `INSERT` avec échec `ABORT`, jamais `REPLACE`,
`IGNORE`, suppression ou reconstruction aveugle de toute la séance. Seules les
lignes modifiées sont mises à jour ; chaque `UPDATE` doit toucher exactement une
ligne. Les nouveaux descendants reçoivent d'abord leur identité globale.
La décision courante (`revision = -1`) est mise à jour. Les décisions antérieures
et traces existantes sont inchangées ; seules les nouvelles séquences sont
ajoutées, dans l'ordre, décisions avant traces pour respecter les FK `RESTRICT`.

Les listes et index métier peuvent être permutés conformément aux copies métier.
Si nécessaire, les positions et index des seuls groupes de frères concernés
passent temporairement dans l'espace négatif (`-position-1`, `-index`) avant
écriture des valeurs définitives. Cela évite les conflits `UNIQUE` immédiats,
sans changer UUID ni parent. L'état temporaire reste à l'intérieur de la transaction,
est invisible aux lecteurs cohérents et n'est jamais accepté comme état final.
La relecture exige les positions contiguës et tous les index métier valides.
Toute erreur annule également cette étape temporaire.

## Validation et limites

Les tests purs vérifient les transitions et les reconstructions qui tentent de
contourner les copies. Les tests SQLite natifs utilisent des fichiers disque :
évolution complète, précision, fermeture/réouverture, décisions et invalidations,
huit profils, idempotence sans écriture prouvée par triggers bloquants, états
attendus divergents, deux rédacteurs indépendants, collisions, statuts protégés,
profils altérés, corruption et erreurs tardives. Les comparaisons de toutes les
tables, une altération par trigger après `UPDATE` et une annulation déclenchée
après une véritable insertion de trace prouvent les rollbacks.
Les tests historiques de création, lecture, domaine et scoring sont conservés.

L'historique reste limité aux provenances courantes et aux repositionnements :
les décisions successives à position constante et les modifications de notes ne
forment pas un journal général. Un instantané entièrement falsifié ne peut pas
être authentifié sans autre source connue. Aucun effacement de trace exigée par
le domaine n'est autorisé lors d'une révision connue.

Restent hors périmètre : connexion Compose/ViewModel, liste/historique utilisateur,
suppression, résolution interactive des conflits, JSON, synchronisation,
matériel/presets et migrations futures. La connexion Android devra notamment
conserver l'état attendu, construire les révisions par les opérations du domaine
et traiter chaque résultat, plutôt que substituer silencieusement un état récent.

*15 octobre 2026*
