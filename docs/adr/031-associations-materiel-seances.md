# ADR 031 — Associations facultatives du matériel aux séances

Statut : implémenté, essais physiques Android non effectués.

## Audit et invariants

`Session.bowUuid` et `arrowSetUuid` existent dans le domaine, SQLite et les deux
contrats JSON. Ce sont des références opaques, sans FK vers le catalogue.
L’[ADR 017](017-mise-a-jour-seances-room.md) autorise déjà les métadonnées propres
d’une séance terminée, tout en conservant statut, clôture et enfants inchangés.
`Session.copy` et `validateSessionUpdate` imposent ces restrictions ; aucun
assouplissement n’est nécessaire.

Les [ADR 025](025-fondation-catalogue-materiel.md), [029](029-saf-android-archives-v2.md)
et [030](030-catalogue-materiel-android.md) restent applicables. Pas de suppression,
réouverture de séance, réédition des scores, nouveau schéma ou format d’archive.

## Opération métier explicite

`Session.withEquipmentReferences(bowUuid, arrowSetUuid, changedAt)` construit une
révision limitée à ces deux références et `updatedAt`, par la copie validée
existante. UUID, création, configuration, date civile, début/fin, statut, volées,
impacts, scores, décisions et traces sont identiques. Une modification antidatée
est rejetée. Les deux références inchangées retournent la valeur initiale, sans
inventer une date de modification. L’opération est légale en DRAFT, IN_PROGRESS
et COMPLETED ; elle ne réactive aucun chemin d’édition de score après clôture.

La validation métier conserve le contrat opaque existant : aucune fiche n’est
résolue, fabriquée ou exigée par cette opération. La reconstruction d’archive
vérifie toujours les UUID canoniques aux frontières de persistance/sauvegarde.

## Parcours Android

La création présente deux choix facultatifs, aucun par défaut. Seules les fiches
relues et valides du bon type peuvent être nouvellement sélectionnées. Le
chargement du catalogue est une lecture indépendante et annulable : il ne bloque
ni le titre/profil ni la création immédiate sans matériel. Une lecture tardive
ne remplace pas les associations d’une séance déjà créée.

Sur une séance existante, « Consulter / modifier les associations » ouvre un
candidat temporaire. La référence courante non résolue reste affichée avec son
UUID et un choix explicite de conservation. L’absence de fiche ou une erreur de
lecture ne la transforme jamais en `null`. « Aucun » est un retrait volontaire ;
une autre fiche est un remplacement volontaire. Les homonymes restent distincts.

La confirmation utilise le même `PendingSave(expected, proposed)` et le même
`RoomSessionStore.update` que les autres révisions. Le dernier état confirmé
reste visible jusqu’à l’acquittement. Conflit, erreur technique, annulation ou
perte d’acquittement conservent le candidat et l’état attendu ; aucune nouvelle
baseline n’est injectée automatiquement. La double confirmation est refusée.
Une confirmation inchangée et une annulation n’envoient aucune écriture.

Pendant ce candidat, les commandes de score, navigation et SAF sont bloquées.
Après annulation ou acquittement, les droits ordinaires reprennent, toujours sans
édition des scores d’une séance COMPLETED. La sélection et les erreurs restent
au ViewModel pendant rotation ; les propositions en mémoire ne survivent pas à
l’arrêt du processus. La reprise relit l’état réellement stocké.

## Références et sauvegardes

V1 conserve les UUID sans fiche et sans effacement. V2 sauvegarde les références
et les fiches disponibles ; une fiche absente ne rend pas la référence nulle.
Les fiches sont des valeurs actuelles du catalogue, **pas un instantané historique
du matériel au moment du tir**. Modifier leur nom ou leurs caractéristiques ne
modifie pas les séances ni leurs scores. Un historique complet de réglages est
une évolution distincte ; aucune unité ou ancienne valeur n’est inventée.

## Validation

Tests purs de l’opération dans les trois statuts, chronologie et conservation des
protections de clôture. Tests du contrôleur : aucun/arc/flèches/deux, catalogue lent
ou illisible sans blocage de création, UUID/type invalides, références opaques,
ajout/remplacement après clôture, annulation, double confirmation, état confirmé
et candidat séparés, conflit, erreur technique, annulation coroutine et relecture.

Deux tests SQLite natifs : association d’une séance terminée riche en décisions,
X/M et traces, avec triggers interdisant **toute écriture hors de `sessions`** ;
fermeture/réouverture, export V2/restauration et égalité intégrale de toutes les
tables. Le second vérifie les références absentes et le conflit entre deux
magasins à état attendu obsolète, sans écriture partielle.

Le Pixel n’est pas utilisé. Consultation réelle des listes, sélection, rotation,
clavier et fournisseurs SAF restent NON TESTÉS physiquement pour ces parcours.
