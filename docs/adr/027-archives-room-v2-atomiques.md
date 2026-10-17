# ADR 027 — Collecte et restauration atomiques des archives Room V2

## Portée et invariants audités

L'[ADR 025](025-fondation-catalogue-materiel.md) fournit le catalogue SQLite et
l'[ADR 026](026-contrat-sauvegarde-json-v2.md) le contrat/codec JSON V2. Cette étape
les relie dans `core:database`. **Le parcours SAF/Compose Android reste en V1** :
il ne sauvegarde ni ne restaure les fiches matériel. Aucun écran de catalogue
n'est exposé avant une intégration SAF V2 distincte.

Les versions restent indépendantes : JSON V1/V2, scoring 1 et Room 2. Aucune
migration ni modification des deux schémas exportés. Les lignes de séance gardent
`archiveSchemaVersion = 1`, version de leur représentation inchangée ; elles ne
prennent pas la version de l'enveloppe JSON complète. Les modèles, profils,
règles de score, décisions et repositionnements restent inchangés.

L'audit identifie six types partageant les UUID (`PROFILE`, `SESSION`, `END`,
`IMPACT`, `BOW`, `ARROW_SET`), dix tables métier et trois tables enfants sans UUID
propre (zones, décisions, traces). Un registre seul ne prouve pas l'existence
physique ; une ligne physique seule ne prouve pas son appartenance au graphe.
Le catalogue partage déjà la base, le verrou écrivain et le cycle de vie du magasin.

## Lecture dans un unique instantané

`RoomSessionStore.readArchiveV2(): ArchiveV2ReadResult` retourne `Found` avec un
`DomainArchiveV2`, `NeedsIntervention` ou `TechnicalFailure`. Une seule
`useReaderConnection` / `deferredTransaction` couvre toute la collecte : registre,
lignes physiques, profils/zones, séances/descendants, arcs et jeux de flèches.
Aucune succession de lectures publiques indépendantes n'est utilisée.

Les profils **tous stockés**, même non référencés, sont restitués avec leur
édition. Ils peuvent exister après un import V2 de profils seuls ; le schéma ne
leur impose pas une séance. Une définition illisible bloque la collecte ; aucun
profil ressemblant n'est substitué depuis le registre publié. Le lecteur V1 garde
sa politique historique de profils référencés seulement.

Avant reconstruction, le registre complet doit correspondre exactement aux
propriétaires physiques des six tables : type inconnu, ligne manquante, UUID
physique sans registre et collision entre types sont des diagnostics avec UUID.
Les enfants sans parent sont également recherchés, même si les FK ont été
contournées ailleurs. Après reconstruction, les identités atteignables doivent
correspondre à **toutes** les identités physiques : aucun descendant inaccessible
ne peut être perdu silencieusement. Les mappers existants contrôlent positions,
compteurs, versions, bits Float, composantes temporelles, décisions et traces.
`reconstructArchiveV2` valide finalement l'archive entière, profils publiés compris.

Les racines de chaque catégorie sont triées par UUID ; l'ordre des enfants et
zones reste significatif. Les lectures ne modifient aucune table. Un écrivain
peut committer pendant la collecte : l'archive conserve intégralement l'ancien
instantané ; la lecture suivante voit le nouveau. Le contexte coroutine d'origine
est contrôlé durant la collecte et avant résultat ; une annulation se propage,
sans succès partiel ni réouverture d'un magasin fermé.

## Planification V2 et confirmation

`planArchiveImportV2(received, known)` est pur, sans SQL ni projection V1. Il classe
séparément séances, profils, arcs et jeux de flèches en UUID nouveaux, identiques
et conflictuels. Chaque liste est possédée, triée et non modifiable. L'égalité
porte sur tout le contenu, dates, édition, enfants, ordre et provenance compris.
Les volées et impacts sont comparés avec leur parent, dans le même espace global
d'identités. Un conflit de profil bloque aussi ses séances ; un nouveau parent
ne peut adopter un descendant déjà connu. Aucun choix selon `updatedAt`.

Contrairement à l'import V1, une archive V2 de matériel seul ou profils seuls
est importable. Une archive entièrement vide est un no-op explicite, sans
suppression. Un conflit bloque **l'ensemble**, même si des éléments indépendants
sont classés nouveaux. Les références opaques de matériel ne revendiquent aucune
identité et n'exigent aucune fiche.

`previewImportV2(archive)` reconstruit la source et lit un instantané local complet,
sans écriture. `Ready` porte un `ArchiveV2ImportPreview` à construction interne :
archive exacte, plan immuable et état local complet. L'appel explicite
`importArchiveV2(preview)` confirme ce candidat ; aucune API ne restaure directement
un domaine sans aperçu. Un aperçu n'autorise aucun écrasement.

## Une transaction écrivain et vérification complète

Sous une unique `useWriterConnection` / `immediateTransaction` :

1. Contrôler disponibilité/annulation et revalider la source complète.
2. Relire le même état global, registre et propriétaires physiques compris.
3. Comparer exactement cet état à celui de l'aperçu et recalculer le plan V2.
4. Refuser tout conflit, tout plan différent ou toute évolution de l'état local.
5. Insérer seulement identités, profils, séances/descendants et matériel absents,
   avec les DAO `INSERT` / `ABORT` existants.
6. Recollecter et reconstruire **toute la base**, puis exiger l'égalité avec l'état
   initial augmenté exactement des ajouts prévus, sans modification de l'existant.
7. Contrôler le Job d'origine avant de laisser Room committer.

La comparaison de l'aperçu est volontairement conservatrice : même une révision
locale sans effet sur les seuls UUID reçus exige un nouvel aperçu. Elle évite
de confirmer un état différent de celui consulté. Aucun retry sur une base
actualisée automatiquement, `UPDATE`, `DELETE`, `REPLACE`, `IGNORE`, UPSERT,
effacement d'historique ou stratégie de réparation n'est utilisé par l'import.

Après le premier INSERT, toute erreur de vérification **lève une exception hors
de la transaction**, pour annuler toutes les écritures. Les diagnostics ne sont
convertis en résultats qu'à l'extérieur. Un import identique, y compris vide,
ne lance aucune écriture métier.

Résultats distincts :

- `Imported(added, identical)` : comptes séparés des quatre catégories après commit.
- `AlreadyPresent(identical, emptyArchive)` : zéro écriture, vide distingué des identiques.
- `Conflict` : état/plan périmé ou divergence avant insertion.
- `NeedsIntervention` : incohérence du domaine ou du stockage.
- `TechnicalFailure(cause, commitOutcome = UNCONFIRMED)` : erreur technique dont
  l'acquittement ne prouve pas l'état du commit.

L'annulation coroutine est propagée. Une annulation observée avant commit annule
la transaction ; une erreur ou perte d'acquittement **après un COMMIT acquis** ne
peut pas l'annuler. Relire puis préparer/confirmer un nouvel aperçu établit l'état
réel ; l'idempotence évite les doublons. Aucun résultat technique n'est présenté
comme une garantie universelle de rollback.

## Compatibilité et frontières

`readArchive`, planificateur/import V1, codec et fixtures historiques restent
inchangés. V1 signifie matériel non fourni ; V2 avec listes matériel vides
signifie zéro fiche reçue. **Aucun des deux ne demande de vider le catalogue.**
`Session.bowUuid` / `arrowSetUuid` restent intacts, même sans fiche ; aucune FK,
association, réparation, génération ou remplacement par `null` n'est introduit.

Le branchement SAF reste une prochaine PR : collecte V2, `decodeVersioned`,
conservation du candidat/version, aperçu explicite, acquittement et relecture du
fichier complet. Ne jamais rétrograder une sauvegarde V2 en V1. Les plafonds JSON
de l'ADR 026 restent inchangés ; une archive trop grande doit être refusée par le
codec, jamais tronquée. La résolution interactive de conflits, suppression et
fusion restent exclues. Les limites de preuve d'authenticité de l'ADR 015 demeurent.

## Vérification

Les tests du planificateur sont purs. Les nouveaux tests Room emploient le vrai
pilote SQLite natif sur fichiers disque, avec fermeture/réouverture et deux
instances : collecte concurrente couvrant séances/catalogue/registre, profils
non référencés, corruptions et orphelins, références opaques, conflits globaux,
aperçus périmés et import concurrent. Des triggers interdisent toutes les écritures
pour prouver l'idempotence ; d'autres introduisent une erreur tardive ou altèrent
un état préexistant pour vérifier le rollback de toutes les tables.

Des hooks exécutés **après** `SQLiteStatement.step` natif prouvent une véritable
insertion avant panne/annulation, ainsi qu'une perte d'acquittement après COMMIT.
L'aller-retour complet Room → codec V2 → seconde base → réouverture compare toutes
les entités, décisions/traces, bits Float, nanosecondes et Unicode. Les tests V1,
leurs octets et empreintes, migrations et contrôles antérieurs restent exécutés.
Les fixtures versionnées sont entièrement synthétiques. Aucun test ADB ne qualifie
cette couche JVM ; la compilation/lint Android ne constitue pas une validation SAF.

*17 octobre 2026*
