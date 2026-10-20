# ADR 035 — Agrégat métier des séances à configurations mixtes

Statut : implémenté et testé en Kotlin pur, sans stockage ni interface Android.
Base exacte : PR #34, `af3598abff20bfc1960ae802cae33bf80d6f0c2b`.
Les contrats des [ADR 033](033-fondation-configurations-par-volee.md) et
[ADR 034](034-scoring-par-volee-statistiques-mixtes.md) restent applicables.
Aucune fusion, modification des branches parentes ou activation d’auto-merge.

## Contexte et emplacement

`Session` impose un profil homogène à tous ses impacts. Elle n’est ni modifiée,
ni utilisée comme conteneur interne du nouvel agrégat. `End`, `ArrowImpact` et
les quatre fonctions historiques de calcul restent également inchangés.

Le paquet `core.scoring.configured` compose les valeurs de `core:model`, le
registre complet des profils, les contextes de calcul #34 et les archives métier
validées. Cet emplacement maintient le sens actuel des dépendances : aucun
`core:model → core:scoring`, nouveau module ou changement Gradle n’est nécessaire.
Il s’agit de domaine JVM pur, sans Android, SQL, codec ou I/O. La séparation
ultérieure en un module de composition dédié ne modifierait pas ces responsabilités.

La base empilée contient Room 1 et les archives V1 de main. Les branches de
catalogue, archives V2 et intégration Android #32 sont indépendantes. Aucun test
ou support de leur intégration n’est revendiqué par cette PR.

## Propriété et immutabilité

[`ConfiguredSession`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/configured/ConfiguredSession.kt)
possède :

- UUID, `createdAt`, `updatedAt`, statut DRAFT/IN_PROGRESS/COMPLETED,
  `startedAt` et `endedAt` explicites ; aucun appel à l’horloge ou générateur UUID.
- `ConfiguredSessionMetadata` : date civile complète `SessionDateTime`, titre,
  nombre prévu de volées, références opaques `bowUuid`/`arrowSetUuid`, lieu et notes.
- Configuration initiale complète et sa provenance `CREATION` ou `LEGACY_DERIVED`,
  y compris quand aucune volée n’existe encore.
- Liste possédée et non modifiable de `ConfiguredEnd`. Chaque capture conserve
  l’UUID de séance propriétaire, l’objet `End`, son `EndConfiguration` exact et
  son origine. L’UUID de volée est celui de `End`, sans deuxième identité.

Les profils et impacts existants sont réutilisés : pas de copie de leurs champs,
ni recalcul des décisions. Le constructeur de l’agrégat est privé ; une capture
n’a pas de constructeur public Kotlin ni de `copy` permettant de changer son
propriétaire/configuration. `ConfiguredSession.copy()` ne prend **aucun paramètre**
et produit une valeur égale, avec les mêmes descendants immuables.
Les métadonnées sont une valeur autonome dont le constructeur et le `copy`
valident titre non blanc, nombre prévu positif ou absent et références non blanches
ou absentes. Les chaînes sont conservées sans trim ou déduction.

Les identités définies dans l’agrégat (séance, profils, volées, impacts) exigent des
UUID v4 canoniques et sont contrôlées contre les collisions entre types. Un profil
partagé peut être référencé plusieurs fois, mais sous exactement la même définition
complète et édition. Le registre vérifie les built-ins, initiale comprise, même
sans impact ; une édition absente n’est jamais remplacée par celle du registre.
Les profils personnalisés conservent leur édition déclarative ou `null` sans
certification. Deux éditions différentes exigent deux identités de profil.
Les index de volée sont uniques ; ceux des impacts sont validés par `End`.

Les références de matériel restent opaques : aucune création/résolution de fiche,
aucune transformation d’une référence non résolue en `null`, aucun contrôle global
du catalogue absent sur cette branche. Un agrégat isolé ne prouve pas l’absence
de collision avec une autre séance ou un catalogue externe : la future transaction
et son registre devront contrôler l’ensemble des identités.

## Capture et provenance minimale

[`EndConfigurationOrigin`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/configured/ConfiguredEnd.kt)
distingue :

| Origine | Signification exacte |
|---|---|
| `Initial` | Première nouvelle volée sans remplacement explicite : configuration initiale |
| `Inherited(previousEndUuid)` | Configuration entière de la volée d’index immédiatement précédent, propriétaire nommé |
| `Explicit` | `NextEndConfigurationChoice.Replace` fourni pour cette nouvelle volée, même si la valeur est égale |
| `LegacyDerived` | Dérivation homogène depuis les paramètres historiques de séance et son profil enregistré |

Ces valeurs n’ont ni UUID de configuration ni date de choix/confirmation.
`Explicit` décrit l’opération demandée au domaine ; ce n’est pas une preuve
cryptographique ou un événement d’interface humaine. La date de création d’une
volée reste sa véritable création, sans être présentée comme une date de sélection.
`LegacyDerived` n’invente pas le choix qu’un utilisateur aurait fait autrefois.

La précédente est déterminée par son index maximal, pas par le profil initial ou
un preset courant. Une volée vide a déjà sa configuration définitive. La suivante
peut hériter, remplacer explicitement, puis transmettre sa valeur aux suivantes.
Les captures précédentes ne changent jamais. Une conversion conserve aussi
l’ordre des anciennes listes ; le calcul #34 fournit son propre ordre canonique.

## Opérations autorisées

| Opération | Préconditions et résultat |
|---|---|
| `create(uuid, metadata, initialConfiguration, createdAt)` | DRAFT vide, initiale et origine de création conservées ; pas de début inventé |
| `start(startedAt)` | DRAFT seulement ; date au moins égale à la dernière révision |
| `createNextEnd(endUuid, createdAt, choice, notes)` | IN_PROGRESS seulement ; UUID fourni, index maximal + 1 contrôlé contre le débordement ; création non antidatée, capture atomique dans la valeur |
| `withImpact(endUuid, impact, integratedAt)` | Volée possédée EDITING ; bon profil effectif, index stable pour une révision, contrôles historiques de `End.withImpact` |
| `withEnd(end, integratedAt)` | Révision d’une volée déjà possédée seulement ; aucun ajout brut, changement d’index, suppression d’impact, transfert ou remplacement de configuration |
| `decideImpact(...)` | Volée EDITING et impact possédé ; `withFinalScore` sous le profil **effectif** |
| `repositionImpact(...)` | Volée EDITING ; `ArrowImpact.reposition`, invalidation tracée et motif quand une décision manuelle est invalidée |
| `validateEnd(endUuid, validatedAt)` | Délégation à `End.validate` et intégration sans changement de configuration |
| `withMetadata(metadata, changedAt)` | DRAFT ou IN_PROGRESS ; révision explicite non antidatée |
| `complete(endedAt)` | IN_PROGRESS ; date couvrant l’état complet ; aucune reclassification des volées |

**Aucune politique sportive sur le remplissage n’est ajoutée.** Aujourd’hui
`End.validate` accepte vide, incomplet, complet et excédentaire indépendamment de
FIXED/FREE ; le nouvel agrégat réutilise exactement ce contrat technique, sans
certifier ces cas comme réglementaires. La clôture historique `Session.complete`
ne requiert pas non plus toutes les volées validées. Le nouvel agrégat conserve
les statuts provisoires à la clôture, sans les compter comme validés. Le futur
parcours produit devra arbitrer ces politiques avant de les exposer.

Une volée VALIDATED est figée dans ce nouvel agrégat ; réintégrer exactement sa
valeur est idempotent, la modifier est refusé. Après COMPLETED, tous les descendants,
métadonnées et événements sont figés ; seule une copie identique est possible.
Aucune réouverture ni édition après clôture.

Les règles des [ADR 008](008-integrite-horodatages-domaine.md),
[011](011-decisions-score-explicites.md),
[012](012-repositionnement-explicite-impacts.md) et
[014](014-convention-temporelle-utc.md) sont réutilisées : création conservée,
révisions non décroissantes et propagation enfant → parent. Un événement explicite
start/create/metadata/complete ne peut précéder la dernière révision de séance.
Une décision, un repositionnement ou une validation respecte la révision de son
objet ; intégrer un enfant plus ancien que son parent conserve le maximum parent,
sans changer la date de l’événement enfant ou masquer une incohérence.

## Révisions et limites de la preuve

[`validateConfiguredSessionUpdate(expected, proposed)`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/configured/ConfiguredSessionValidation.kt)
compare l’état complet : identité/création/initiale/origine, statuts/événements,
configuration et origine de chaque ancienne volée, index, descendants et historique.
Les nouvelles volées s’ajoutent après l’index maximal et sans origine historique
fabriquée. Suppression/reparentage d’un impact est interdit, avec **ou sans** traces.

Les contrôles d’une révision d’`End` sont composés avec son `copy` historique pour
réutiliser les contraintes de décision, date et préfixe de repositionnement ; les
restrictions supplémentaires de propriété/index/non-suppression appartiennent au
nouvel agrégat. Les constructeurs historiques restent inchangés. Une révision
reconstruite sous le même UUID est confrontée au connu, sans être considérée
comme nouvelle parce qu’elle provient d’un autre objet Kotlin.

Sans état connu ou registre externe, aucune valeur autonome ne peut découvrir
une histoire entièrement omise. Le validateur n’est pas une politique de fusion
ni une preuve de causalité : le futur stockage exigera aussi `expected` exact sous
verrou transactionnel et refusera les révisions concurrentes, sans choisir la plus récente.

Les violations de l’agrégat ont un `ConfiguredSessionException(code, path)` ;
les violations historiques et de contexte #34 gardent leurs exceptions existantes.
Aucune normalisation ou reconstruction partiellement acceptée.

## Adaptation du scoring et conversion historique

[`ConfiguredSessionScoring.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/configured/ConfiguredSessionScoring.kt)
fournit :

```kotlin
configuredEndScoringContext(session, end): EndScoringContext
configuredSessionScoringContexts(session): List<EndScoringContext>
summarizeConfiguredSession(session): MixedScoreSummary
```

Une capture étrangère ou périmée est refusée. La liste est tirée des seuls enfants
possédés avec leurs configurations effectives. `summarizeConfiguredSession` délègue
à `summarizeConfiguredEnds` ; les totaux, compteurs et deux dénominateurs restent
ceux de #34, calculés depuis les scores finaux stockés. Ni décision, trace, X/M ni
profil d’impact n’est réinterprété. Un total mixte reste technique, sans comparabilité
réglementaire annoncée. Le nombre prévu de volées n’invente aucun maximum mixte.

`ConfiguredSession.fromLegacyArchive(archive: DomainArchive, sessionUuid)` est la
seule conversion historique publique. Elle exige la source métier **déjà validée**
de l’ADR 015, avec séance présente et définition complète/édition dans son profil.
Aucun adaptateur depuis une `Session` seule ni résolution par son UUID/diamètre.
Les huit profils publiés sont supportés ; les quatre historiques conservent leur
édition explicitement `null`, les quatre WA leur édition exacte. Pour une source
personnalisée, l’édition déclarée ou son absence explicite est conservée, sans
reconstituer un règlement manquant. Une édition WA manquante est rejetée par la
reconstruction existante, jamais complétée par cette conversion.

La conversion réutilise les `End`/impacts/décisions/traces de la source validée,
préserve métadonnées, références opaques, dates, statuts, index et ordre. Elle
attribue l’origine `LEGACY_DERIVED` à l’initiale et `LegacyDerived` à chaque volée,
même vide. Aucun événement historique ni heure de conversion n’est ajouté.
Cela n’écrit pas dans Room, ne modifie pas l’archive et ne rend pas le mixte
exportable en V1/V2. Les futurs adaptateurs V2 devront recevoir leur propre source
validée et fournir les mêmes garanties ; aucun format existant n’est modifié ici.

## Étapes suivantes, dont intégration avec #32

| Étape séparée | Contrat et preuves à ajouter |
|---|---|
| Réconciliation des branches | Après revue des parents #33/#34/#35 et de #32, comparer les versions réellement intégrées. Ne pas appliquer automatiquement la représentation homogène actuelle de #32 aux nouvelles séances mixtes |
| Room | Nouveau contrat de reconstruction de l’agrégat, capture + volée + provenance atomiques ; migration non destructive depuis les versions effectivement publiées ; garder registre global, `expected` exact, conflits/rollback/concurrence et toutes les anciennes données |
| Archives | Nouveau contrat versionné représentant initiale/effectives/provenances ; conversions explicites depuis V1/V2 validées, profils/éditions exacts, références opaques, budgets stricts et import atomique ; aucune rétrogradation silencieuse vers un format homogène |
| Android de #32 | Adapter repository/contrôleur seulement après stockage et sauvegarde fidèles ; proposition précise, matériel, choix de prochaine volée et acquittement dans un même état pending ; préserver annulation, retry exact, callbacks obsolètes et conflit |
| Validation ultérieure | Tests croisés visée/matériel/volées mixtes/SAF, bases historiques réelles et golden archives ; seulement ensuite campagne humaine séparée sur appareil |

Aucun écran producteur, aucune migration Room V3 ou nouveau numéro JSON n’est
initié automatiquement. Les versions seront choisies selon les branches alors
fusionnées, sans tentative de réouverture d’une base récente par un ancien APK.

### Arbitrages et opération rétroactive non livrée

Restent à valider : politique de remplissage/clôture, capacités ergonomiques,
maximums planifiés mixtes, libellés/arrondis/ratios et regroupements d’interface,
presets d’épreuves complètes et portée des associations de matériel.

La correction exceptionnelle d’une configuration déjà capturée, **même vide**,
écessitera une opération distincte : autorisation, motif non blanc, ancien/nouveau
profil complet/édition et configuration, date et révision, devenir explicite des
impacts/décisions et preuve conservée de leur invalidation éventuelle. Une simple
copie, un choix pour la prochaine volée ou une migration ne donnera pas ce droit.
Les règles de réouverture/post-clôture et leur journal général restent hors périmètre.

## Vérification

60 nouveaux tests unitaires couvrent création, transitions et chronologie, captures
vides/héritées/explicites/dérivées, identités/propriétaires/index, profils/éditions,
révisions reconstruites, immutabilité, conservation des jugements et traces,
statistiques mixtes, conversion validée et équivalence homogène historique.
Ils conservent tous les anciens tests sans modifier leurs attendus.

Validation complète : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
inspection des XML, tests SQLite natifs existants, assemblages/lint Android,
liens locaux et `git diff --check`. Vérifier ensuite le Quality gate GitHub sur
le commit publié, sans substituer celui de la branche parente.
Aucun accès ADB, appareil, données utilisateur ou essai physique.

### Résultat local du 20 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` réussit avec JDK 21,
Gradle Wrapper 8.10.2, SDK 35 et Build Tools 34.0.0. Les rapports XML comptent
**769 exécutions**, zéro échec/erreur/test ignoré : modèle 118, scoring 301 (dont
60 nouveaux), codec V1 10, SQLite natif disque 96, Android JVM 122 debug + 122 release.
Les suites Android communes sont exécutées sur deux variantes, pas comptées comme
autant de tests uniques. Assemblages debug et lint app/database : PASS ; zéro
erreur lint, un avertissement GradleDependency database dans les XML de ce run.
Les avertissements KMP/AGP préexistants ne sont pas masqués.

63 liens documentaires locaux et `git diff --check` sont vérifiés. Les fichiers
historiques des modèles, du scoring et des tests restent inchangés, comme Android,
Room/schéma 1, codecs et build/CI. Le schéma conserve l’empreinte SHA-256
`917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`.
Aucun test V2 des branches indépendantes n’est annoncé. La validation GitHub
est contrôlée séparément sur le commit publié de la PR en brouillon.
