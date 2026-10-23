# ADR 043 — Création et édition Android des séances configurables

Statut : implémenté, validation automatisée sur données synthétiques ; essais
physiques reportés. Base exacte : `8b42c8959f04cd1c9ed5a4868a716e5b4e0c0f91`
(#42). PR en brouillon vers `codex/android-saf-v3-configured-read` ; aucune fusion
ni auto-merge. Aucun accès ADB, appareil ou base utilisateur.

## Contrats réutilisés

Les [ADR 033](033-fondation-configurations-par-volee.md),
[034](034-scoring-par-volee-statistiques-mixtes.md) et
[035](035-agregat-seances-configurations-mixtes.md) définissent les configurations,
statistiques et transitions. Les [ADR 037](037-contrat-archive-seances-configurables-v3.md),
[038](038-codec-json-v3-strict.md), [039](039-room-v3-migration-lecture.md),
[040](040-ecritures-room-v3-configurables.md),
[041](041-restauration-archives-v3-atomique.md) et
[042](042-saf-v3-consultation-configurable.md) fournissent reconstruction, codec,
stockage et sauvegardes. Aucun contrat sportif ou choix produit ouvert n’est
redéfini ici. Room reste **3, douze tables**, JSON 1/2/3 et scoring 1 inchangés.
Les schémas, migrations, DAO, codecs, golden et dépendances ne changent pas.

## Séparation des éditeurs et lecture contextualisée

[`ConfiguredSessionRepository`](../../app/src/main/java/com/lmarchery/data/ConfiguredSessionRepository.kt)
expose `readConfiguredSession`, `createConfiguredSession` et
`updateConfiguredSession(expected, proposed)`. L’implémentation Room du processus
transmet tous les résultats typés de 040, sans booléen ni conversion en archive
homogène. Ni `Session`, ni `SessionSnapshot`, ni `DomainArchive`, ni `PendingSave`
ne représentent une séance mixte dans ce nouveau parcours.

La nouvelle lecture typée du magasin valide **l’ensemble de la source V3 dans
une seule transaction lecteur** et vérifie la représentation SQL de la racine.
Une projection V3 d’une ligne `LEGACY_HOMOGENEOUS` ne donne donc pas accès à cet
éditeur. `Found`, `LegacyHomogeneous`, `NotFound`, `IdentityOccupied`,
`NeedsIntervention` (chemin précis) et `TechnicalFailure` restent distincts.
Cette lecture ne normalise rien et n’exécute aucun DML. Le test natif installe
des triggers refusant INSERT/UPDATE/DELETE sur les douze tables pour le prouver.

Les lignes historiques restent dans le contrôleur et l’éditeur historiques, y
compris leur ancien parcours matériel. Les racines `CONFIGURED` complètement
validées accèdent au nouvel éditeur ; une provenance `LEGACY_DERIVED` restaurée
légitimement en V3 reste conservée, sans être transformée en `CREATION`. Les
transitions et le writer décident de leur validité. Une séance terminée est
entièrement en lecture seule dans ce parcours, matériel compris.

## Contrôleur, formulaires et captures

[`ConfiguredSessionController`](../../app/src/main/java/com/lmarchery/feature/configured/ConfiguredSessionController.kt)
et son état sont distincts du contrôleur historique. Le ViewModel les conserve
à travers les recompositions et la rotation logique de l’Activity. L’état
`saved` contient seulement un agrégat acquitté ou retrouvé intégralement lors
d’une réconciliation explicite. Un candidat local n’est jamais affiché comme
une nouvelle donnée durable.

L’accueil ajoute **Nouvelle séance configurable**, distincte de la création
rapide historique. Le formulaire contient titre obligatoire, lieu et notes
facultatifs, nombre prévu facultatif positif et choix de matériel disponible.
Les références opaques d’une séance existante sont conservables ou supprimables
explicitement ; elles ne donnent lieu à aucune création de fiche implicite.
Les chaînes sont conservées, y compris espaces et chaîne facultative vide.
La date civile de création utilise une Clock injectable UTC et le fuseau IANA
du téléphone ; sa modification n’est pas proposée. Les métadonnées révisées
conservent la date civile enregistrée.

La configuration propose les huit définitions exactes du registre publié et
leurs éditions déclarées, distance entière positive indépendante du profil,
indoor/outdoor et mode de score explicites, FIXED 3/6/personnalisé positif ou
FREE. Aucun UUID, édition ou profil personnalisé n’est inventé. La sélection
d’un profil ne change pas la distance. Un profil officiel associé à une distance
personnalisée ne constitue pas une nouvelle épreuve réglementaire. La sélection
de nouveaux profils personnalisés hors registre reste une limite ; une capture
personnalisée existante reste utilisable par héritage, sans remplacement.

La confirmation appelle `ConfiguredSession.create`, provenance `CREATION`,
puis le writer. Le démarrage et la première volée sont des actions séparées,
utilisant `start` et `createNextEnd` après acquittement du brouillon.

Chaque formulaire possède un jeton de présentation. Les callbacks d’un autre
formulaire ne peuvent ni le modifier ni le confirmer. Avant confirmation, le
formulaire ne crée aucune volée, origine, date ou identité métier. Annuler le
formulaire de prochaine volée ne change rien dans la séance.

Deux choix sont présentés pour une prochaine volée :

- `Inherit` : précédente par index, ou initiale pour la première ; le domaine
  produit `INHERITED(previousEndUuid)` ou `INITIAL`.
- `Replace(configuration)` : choix explicite, provenance `EXPLICIT` même si
  toutes les valeurs sont égales à la précédente.

Revenir du choix explicite à « Hériter » réaffiche la configuration héritée
exacte, en abandonnant les valeurs explicites non soumises. La capture réelle
est visible avant confirmation et reste immuable après,
même vide. Aucun bouton de correction rétroactive n’est proposé. La création
d’une volée suivante peut laisser des volées antérieures éditables : le domaine
ne définit pas une politique imposant une seule volée éditable.

## Saisie, décisions et statistiques

Le Canvas, la loupe, les gestes et les règles de score ne sont pas réécrits.
Les champs de visée et de décision sont extraits en présentations partageables ;
l’éditeur historique garde ses adaptateurs et ses tests inchangés.

Le nouveau contrôleur utilise exclusivement
`configuredEnd.configuration.targetType` et `scoringMode`, jamais les alias de
séance. Chaque callback tactile porte séance, volée, impact éventuel et **époque
de présentation**. Le Canvas est recréé sur cette clé pour abandonner les anciens
callbacks de début de geste. Les tokens de geste rejettent les fins/déplacements
annulés. Le garde multitouch commun reste actif jusqu’au relâchement complet.

En mode rapide, un appui admissible soumet une flèche. En visée précise, maintenir,
déplacer et relâcher ne fait que proposer ; une confirmation distincte écrit,
une annulation n’écrit rien. Le passage synchrone à une intention en attente et
le changement d’époque après acquittement empêchent la double confirmation.
FIXED limite la saisie UI à sa capacité déclarée ; FREE n’impose pas trois
flèches. L’affichage ne génère aucune collection de cases jusqu’à une valeur
FIXED potentiellement `Int.MAX_VALUE` : seules les flèches présentes sont listées.

Les options de jugement proviennent du profil effectif. Jugement de frontière,
correction exceptionnelle motivée et repositionnement utilisent les opérations
existantes de l’agrégat. Le repositionnement invalide et conserve l’ancienne
décision manuelle selon 012 ; son motif est exigé lorsque le domaine l’exige.
Le contrôleur ne reconstruit ni décision ni trace à la main. Les nouvelles
décisions restent possibles après repositionnement.

La validation délègue au domaine actuel : une volée FIXED vide ou incomplète peut
être validée. L’écart à la capacité est informatif, sans nouvelle politique
sportive. Une volée validée refuse tout ajout/correction. La clôture exige une
confirmation indiquant que les volées provisoires seront figées **sans** devenir
validées. La séance terminée ne peut pas être rouverte.

La présentation 042 et les statistiques 034 sont réutilisées : validé et provisoire
séparés, groupes par configuration exacte, compteurs maximum/9/X/M finaux,
maximum de capacité enregistré distinct du maximum des flèches présentes.
FREE compte seulement les flèches enregistrées. Aucun maximum planifié fictif,
comparabilité réglementaire d’un ensemble hétérogène ou pourcentage inventé.
Un dépassement numérique conserve un diagnostic visible, jamais un faux zéro.

## Intention exacte, concurrence et incertitude

Chaque action soumise conserve `ConfiguredWriteRequest` avec agrégats complets,
UUID, dates, sélection, formulaire et correction exacts. Chaque révision appelle
`validateConfiguredSessionUpdate` et `updateConfiguredSession(expected, proposed)`.
Le writer 040 relit l’état complet sous transaction écrivain immédiate, vérifie
égalité/provenance, écrit le delta autorisé et contrôle l’archive avant commit.
Aucun historique supprimé, configuration remplacée, profil écrasé ou comparaison
réduite à `updatedAt`.

| Résultat | Comportement de présentation |
|---|---|
| `Created` / `Updated` | succès après acquittement, candidat libéré |
| `AlreadyApplied` | contenu intégral déjà appliqué, aucune écriture |
| `Conflict` | catégorie UUID/révision/transition/provenance/etc. conservée ; aucun écrasement |
| `NeedsIntervention(NOT_ATTEMPTED)` | diagnostic ; candidat retenu |
| `TechnicalFailure` | échec avant DML par cet appel, sans conclusion sur un appel précédent |
| `CommitUnconfirmed` / `NeedsIntervention(UNCONFIRMED)` / interruption sans preuve | issue non confirmée ; aucune relance automatique |

Le résultat doit acquitter la même demande. Tous les échecs retiennent le candidat
et le verrou. Une relecture explicite offre trois issues :

1. `actual == proposed` : reconnaître la proposition intégrale retrouvée, sans
   nouvelle écriture ni acquittement SQL inventé.
2. `actual == expected` (révision), ou UUID absent (création) : afficher le
   constat puis permettre une **nouvelle confirmation explicite de la même
   intention**. Aucun UUID, date, formulaire ou choix n’est régénéré. Le writer
   vérifie à nouveau l’état sous verrou, même si un tiers écrit entre-temps.
3. Troisième état, racine disparue, mauvais type ou historique : bloquer la
   répétition, conserver l’intention, présenter le conflit. Aucun rebase.

Une erreur de lecture maintient le blocage. Une erreur d’actualisation secondaire
après un commit acquitté ne transforme jamais ce commit en échec incertain.

## Navigation et cycle de vie

Le contrôleur acquiert le **verrou commun** de l’accueil pour toute sa durée,
formulaires locaux et consultation comprise. SAF import/export, catalogue,
éditeur historique et seconde ouverture ne peuvent entrer simultanément.
Fermer libère seulement le verrou acquis, puis redécouvre la liste.

Le retour distingue abandon d’un formulaire/candidat jamais soumis et abandon
d’une intention soumise : le second avertit qu’un commit peut déjà exister et
que quitter n’annule aucune transaction. Il n’est pas possible d’abandonner
pendant l’appel en cours. Les corrections locales ont aussi une annulation
explicite. Après mort du processus, seul un marqueur d’interruption de l’écran
survit ; aucune proposition locale n’est reconstruite ou prétendue enregistrée.
Le message demande une relecture avant de recommencer. Ce marqueur est une aide
UI, pas un journal durable ou une preuve d’absence d’écriture.

## Preuves et limites de validation

Les suites ajoutées sont séparées :

- `ConfiguredEditorLifecycleTest`, `ConfiguredEditorImpactTest`,
  `ConfiguredEditorWritesTest`, `ConfiguredFormsTest` : tests JVM de contrats,
  états, tokens et doubles repository, **sans SQL ni gestes physiques**.
- `ConfiguredEditorExclusionTest` : vrais coordinateurs de présentation et leur
  verrou commun, services doublés ; pas de fournisseur SAF réel.
- `RoomConfiguredEditorTest` : Room/SQLite natif sur fichiers synthétiques,
  vraie migration V1→2→3, révisions, fermeture/réouverture, JSON V3, restauration
  dans une seconde base puis idempotence sous garde SQL ; deux écrivains réels.
  Vérification des douze tables, provenances, nanosecondes, décisions et traces,
  références opaques, conservation des lignes historiques et du catalogue,
  `foreign_key_check`, `integrity_check`, corruption et lecteur sans DML.

Les suites antérieures sont conservées, notamment concurrence/rollback/annulation
et acquittement perdu du writer 040 et de la restauration 041, golden et rejets
stricts V1/V2/V3, migrations sur disque, éditeur historique et SAF 042.
Validation de référence : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
avec inspection des XML, compilation Android, lint, empreintes, liens et diff.
Les résultats effectivement observés sont consignés dans le rapport de PR.

Restent **NON TESTÉS sur appareil** : précision au doigt, multitouch physique,
rotation/recréation et mort réelle du processus, ergonomie des formulaires,
clavier et petits écrans, fournisseurs SAF et stockage Android réels. Aucun ADB,
installation, extraction ou accès à la base du Pixel n’a lieu dans cette mission.

Les arbitrages sportifs (validation des volées incomplètes, maximum planifié des
séances mixtes, correction rétroactive, réouverture) restent ouverts et ne sont
pas résolus par cette interface. Aucun nouveau preset, stockage, format JSON ou
PR suivante n’est engagé.

### Résultats locaux du 23 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : PASS, trois étapes
`BUILD SUCCESSFUL` (tests, assemblage debug app/database, lint). JDK Temurin
21.0.12.1 complet, SDK 35, Build Tools 34.0.0, Gradle Wrapper 8.10.2.

| Suite XML inspectée | Exécutions | Échecs / erreurs / ignorés |
|---|---:|---:|
| core:model | 135 | 0 / 0 / 0 |
| core:scoring | 422 | 0 / 0 / 0 |
| core:export | 99 | 0 / 0 / 0 |
| core:database:jvmTest, SQLite natif | 353 | 0 / 0 / 0 |
| app:testDebugUnitTest | 326 | 0 / 0 / 0 |
| app:testReleaseUnitTest | 326 | 0 / 0 / 0 |
| Total | **1 661** | **0 / 0 / 0** |

**59 nouveaux tests distincts** : 53 de présentation et 6 natifs SQLite. Les
53 méthodes sont retrouvées nominativement dans chacun des deux XML Android ;
les variantes ne représentent pas 106 tests uniques. Tous les tests historiques
sont conservés sans modification. La vérification des XML a conduit à expliciter
le retour `Unit` de tests que JUnit ne découvrait pas initialement.

Lint : zéro erreur, les cinq avertissements `GradleDependency` préexistants
(3 app, 2 database), et le message de compatibilité KMP/AGP restent visibles.
Une relance locale intermédiaire a rencontré un crash lint `zip END header not
found` lors de contrôles qui se chevauchaient ; la validation finale séquentielle
réussit intégralement, sans désactivation ni modification du build.

Les schémas 1/2/3 gardent leurs empreintes SHA-256 respectives :

- `917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`
- `e2697637163f84dc9f20c467a66899de7c7ea86766dc5a9842e9dad7db291a40`
- `3333739e70b6ffc1781df44624a152ef6940c25f2d4299a23b34b8f41e41a2b7`

Les sources modèle/scoring/export, fixtures et golden, schémas, migrations,
DAO, dépendances et CI sont inchangés par rapport à la base exacte #42.
337 liens locaux et `git diff --check` sont conformes. Le Quality gate GitHub
est vérifié séparément sur le commit publié et rapporté dans la PR.
