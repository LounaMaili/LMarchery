# ADR 026 — Contrat de sauvegarde JSON V2 avec matériel

> Suite : l’[ADR 027](027-archives-room-v2-atomiques.md) ajoute la collecte et
> restauration Room V2. Le parcours SAF/Android reste V1.

## Portée

L'[ADR 025](025-fondation-catalogue-materiel.md) ajoute `Bow`, `ArrowSet` et le
catalogue Room 2. Le JSON V1 des [ADR 015](015-sauvegarde-versionnee-reconstruction.md),
[021](021-export-json-versionne-android.md) et
[022](022-import-json-controle-transactionnel.md) ne contient pas ces fiches.
Cette étape ajoute seulement les transferts, la reconstruction pure et le codec
V2. **L'application Android exporte et importe toujours le V1.** Ni collecte Room
V2, restauration atomique du matériel, SAF V2 ni interface de catalogue ne sont livrés.

## Versions et types distincts

| Élément | Valeur et rôle |
|---------|----------------|
| Schéma JSON V1 | `ArchiveContractVersions.SCHEMA = 1`, conservé pour les API opérationnelles |
| Schéma JSON V2 | `ArchiveContractVersions.SCHEMA_V2 = 2`, profils/séances et matériel explicite |
| Contrat de scoring | `SCORING = 1`, inchangé ; aucune nouvelle règle métier de score |
| Schéma SQLite | Room 2, inchangé ; aucune migration dans cette PR |

`ArchiveV2Snapshot`, `BowSnapshot` et `ArrowSetSnapshot` sont des valeurs de
transfert copiables, non fiables. Les listes racines et champs indispensables
peuvent être absents dans un transfert Kotlin pour produire un diagnostic précis ;
leur absence n'a aucun défaut métier. Comme pour V1, ne pas muter un transfert
pendant sa validation. `snapshotArchiveV2(profiles, sessions, bows, arrowSets)`
capture des valeurs explicites, sans conférer une autorisation de restauration.

`reconstructArchiveV2` retourne `ArchiveV2Reconstruction.Accepted(DomainArchiveV2)`
ou `NeedsIntervention(source, ArchiveIssue)`. Les codes typés et chemins identifient
la première erreur ; aucun sous-ensemble accepté n'est retourné. La portion
profil/séance réutilise intégralement le validateur V1 des profils, décisions,
assertions, traces, dates et agrégats, après contrôle de la version et des listes
V2. Ce partage interne de validation n'est pas une conversion publique en V1.

`DomainArchiveV2` possède ses listes non modifiables, compare tout son contenu et
n'hérite **pas** de `DomainArchive`. Il n'expose aucune projection automatique
vers ce type V1 : on ne peut pas le transmettre au planificateur/import Room V1
ni sélectionner accidentellement l'encodeur V1. Il ne prouve pas une filiation
face à un état local connu ; aucune résolution de conflits n'est implémentée ici.

## Contenu et validation

La racine V2 contient exactement `schemaVersion`, `scoringContractVersion`,
`profiles`, `sessions`, `bows`, `arrowSets`, toutes propriétés obligatoires.
Chaque liste est un tableau explicite, jamais `null`. Des tableaux vides sont
valides ; une archive uniquement matérielle ou entièrement vide est structurellement
valide. Cela ne définit pas la politique d'une future commande de restauration.

Profils et séances gardent exactement leur représentation V1. Les fiches incluent
**tous** les champs des modèles de l'ADR 025, sans ID numérique. Chaque clé est
obligatoire même si sa valeur métier est facultative : une clé absente n'est pas
un `null` et encore moins une instruction d'effacement.

- UUID, nom, création et révision présents et non nuls ; UUID v4 canonique ; nom non blanc.
- Instants avec contexte UTC/offset explicite et nanosecondes, `createdAt <= updatedAt`.
- Puissance facultative : `drawWeightLbs: null` signifie non renseignée. Une valeur
  fournie est finie et strictement positive ; zéro (y compris `-0.0`), négatif,
  débordement, sous-débordement non nul, NaN et infinis sont rejetés.
- Type, marque, modèle, notes, spine, longueur, pointe, encoche, empennage et matériau
  restent des textes optionnels, conservés exactement : espaces, vide et Unicode,
  sans trim, conversion d'unités, interprétation réglementaire ou enum inventée.

L'unicité des identités couvre ensemble profils, séances, volées, impacts, arcs
et jeux de flèches. Les doublons identiques ne sont pas dédupliqués. Les UUID des
profils publiés restent réservés, comme dans le catalogue, même si le profil n'est
pas contenu dans l'archive. Les profils connus exigent toujours la définition
complète et l'édition exactes. Les références ne revendiquent pas une identité.

`Session.bowUuid` / `arrowSetUuid` restent opaques et conservés à l'identique.
L'absence d'une fiche n'entraîne ni création, ni `null`, ni erreur d'existence,
ni vérification implicite du type référencé. Aucune association ou réparation
n'est introduite dans les séances. Une archive historique reste reconstructible.

## Codec compatible et absence de rétrogradation

- `ArchiveJsonCodec.encode(DomainArchive)` conserve l'émission V1, octets compris.
- `encode(DomainArchiveV2)` émet uniquement V2 et vérifie l'aller-retour complet.
- `decode(bytes)` reste le décodeur V1 employé par Android ; il rejette V2.
- `decodeVersioned(bytes)` rend `VersionedArchiveJsonRead.V1`, `.V2` ou `.Rejected`.

Le résultat `.V1` indique explicitement que le matériel **n'est pas fourni** par
cette version ; aucune liste de matériel vide n'y est synthétisée. `.V2` porte les
deux listes validées, éventuellement vides. Leur distinction doit être conservée
jusqu'à la future transaction Room : aucune de ces valeurs ne demande de supprimer
un catalogue. Il n'existe ni fallback V2→V1 ni encodeur sélectionné par un booléen
qui pourrait perdre les fiches. L'ancienne chaîne Android ne reconnaît pas V2
comme un fichier partiellement restaurable : elle le refuse avant SQL.

Le même `StrictJson`, `JsonFields` et adaptateur V1 des objets existants est
réutilisé sans assouplissement. Les clés inconnues, dupliquées après décodage des
échappements, mauvais types, propriétés absentes, UTF-8 invalide, BOM, commentaires,
contenu tronqué/supplémentaire et surrogates isolés sont rejetés intégralement.
Les diagnostics V2 donnent un code et un chemin JSON tel que `$.bows[0].updatedAt`.

Budgets partagés : 16 MiB, profondeur 32, 200 000 valeurs, 20 000 éléments par
tableau, 64 propriétés par objet, 16 384 caractères UTF-16 par chaîne/clé,
128 caractères par nombre. Ils portent sur tout l'arbre V2, et non séparément
sur chaque catalogue. Ils bornent aussi la sortie. Les transferts Kotlin purs
ne constituent pas à eux seuls un lecteur borné de données externes.

L'ordre des propriétés est fixe, les listes gardent leur ordre significatif,
l'indentation est de deux espaces et les valeurs nulles sont explicites. Même
archive, mêmes octets ; aucune date d'export ou dépendance au fuseau système.
Les Float utilisent directement leur représentation décimale JVM et le lecteur
conserve les lexèmes, sans détour par Double : les bits valides, dont la puissance,
restent exacts. Les instants émis utilisent `Z` avec nanosecondes. Les offsets
valides reçus suivent les conversions strictes de l'ADR 014 ; aucune heure naïve
n'est interprétée comme UTC et aucun triplet civil de séance n'est modifié.

## Preuves de compatibilité et tests

Avant modification de production, les douze fixtures existantes du test V1 ont
été encodées avec le codec du commit parent `de06429`. Leurs SHA-256 sont figés
dans ce même test, conservé avec tous les tests historiques. Deux exemples
synthétiques, vide et riche, sont aussi figés dans les ressources de test JSON :
décodage par les deux API puis réencodage comparé **octet par octet**. Ce sont des
fixtures synthétiques, jamais des séances ou sauvegardes utilisateur.

Les tests V2 couvrent matériel seul, listes explicitement vides, toutes les clés
requises, collisions entre les six types, références opaques, profils altérés,
provenance et traces, chronologies, nanosecondes, Float extrêmes/adjacents, textes
et unités, changement de fuseau par défaut, ordre déterministe, immutabilité,
limites et fichiers malveillants. Une erreur tardive empêche tout résultat accepté.
Le script complet conserve les tests SQLite de la PR parente, le build et le lint.

## Étapes suivantes obligatoires avant utilisation Android

Une PR séparée doit collecter séances, profils **et catalogue** dans un seul
instantané lecteur Room cohérent, diagnostiquer les données illisibles/orphelines
et valider l'archive complète. Appeler successivement `readArchive()` puis des
lectures indépendantes du catalogue ne garantit pas cet instantané.

L'import V2 nécessitera un planificateur intégrant toutes les identités et une
unique transaction écrivain : recalcul du plan/collisions sous verrou, inserts
contrôlés, relecture complète et rollback de l'ensemble sur toute erreur tardive.
Conserver les conflits, l'idempotence et l'absence d'écrasement ; pas de choix selon
`updatedAt`. V1 n'apporte pas de matériel et ne doit jamais effacer l'existant.
Un tableau V2 vide ne constitue pas davantage une autorisation de suppression.
Ni le planificateur V1 ni une boucle de créations ne doivent restaurer une
projection V1 d'une archive V2. La connexion SAF/Android viendra ensuite, avec
confirmation et acquittement du contenu complet. Aucun essai physique ou ADB
n'est impliqué dans ce contrat pur.

*17 octobre 2026*
