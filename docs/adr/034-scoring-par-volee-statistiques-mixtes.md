# ADR 034 — Scoring contextualisé par volée et statistiques mixtes

Statut : implémenté dans le moteur pur, sans intégration aux agrégats persistés.
PR empilée sur #33, tête vérifiée `a70c05f07175664b94283cb8287b1070a941a7a1`.
L’[ADR 033](033-fondation-configurations-par-volee.md) reste applicable.
Main et les PR existantes, notamment #32, ne sont pas intégrées ou modifiées.

## Portée et compatibilité

Les nouvelles API sont dans `core.scoring.mixed`. `Session`, `End`, `ArrowImpact`,
`TargetType`, `EndConfiguration` et les quatre API historiques
`calculateEndScore`, `calculateSessionScore`, `calculateMaxPossibleScore` et
`summarizeSessionScore` restent inchangés, ainsi que tous leurs anciens tests.
Le calcul historique conserve notamment son périmètre de maximum prévu/enregistré.

Il ne devient pas possible de construire ou persister une `Session` mixte : ses
impacts doivent toujours partager son profil. Le nouveau moteur agrège seulement
une collection explicite de volées et de configurations, sans prétendre valider
leur appartenance à une même séance. Aucune migration, JSON, SAF, UI ou dépendance.
Room 1/JSON V1 de la base #33 et les branches indépendantes V2 restent inchangés.

## Association et validation du contexte

[`EndScoringContext.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/mixed/EndScoringContext.kt)
expose :

```kotlin
EndConfigurationBinding(endUuid, configuration)
EndScoringContext(end, binding)
validateEndScoringContext(end, binding)
bindEndConfigurations(ends, bindings): List<EndScoringContext>
```

Le contexte contient la volée immuable d’origine et une affectation nommant son
UUID. Constructeur et `copy()` revalident. L’association par collection exige une
bijection stricte, **même pour une volée vide** : aucun repli implicite sur une
configuration initiale, aucun doublon identique accepté comme « dernier gagnant »,
aucune affectation sans volée. Un contexte d’une autre volée est rejeté.

Le profil de chaque impact doit être égal à la définition complète de sa
configuration. Aucun impact classique ne passe sous le profil poulies du même
diamètre. Dans `core:scoring`, le registre publié est disponible : définition et
édition connues doivent correspondre exactement, et un built-in inconnu est
rejeté, y compris dans une volée vide. Un profil personnalisé conserve sa définition
et son édition déclarée, sans certification inventée.

Une collection rejette les UUID de volée/impact répétés, les collisions entre
types et un même UUID de profil portant deux définitions ou éditions. Deux
éditions légitimes peuvent être agrégées sous **deux identités distinctes** ; elles
ne sont jamais fusionnées par diamètre, géométrie ressemblante ou nom.

Ces contrôles valident la cohérence de l’affectation déclarée. L’ancien `End`
ne porte ni configuration ni édition : une volée vide ne peut pas prouver quelle
distance avait été choisie historiquement. La future reconstruction/persistance
devra fournir cette capture et sa provenance. L’API ne fabrique pas cette preuve,
ne devine pas une édition et n’autorise aucune révision rétroactive.

Les erreurs sont des `MixedScoringException` avec `code: MixedScoringIssue` et
`path` : configuration manquante/dupliquée, propriétaire incorrect ou absent,
profil incompatible/contradictoire/inconnu, identité dupliquée/en collision et
débordement numérique. Aucune réussite partielle n’est retournée.

## Calculs sans réinterprétation des décisions

[`MixedScoreCalculator.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/mixed/MixedScoreCalculator.kt)
fournit :

```kotlin
calculateConfiguredEndScore(context): ConfiguredEndScore
calculateRecordedMaximum(context): Long
summarizeConfiguredEnds(contexts): MixedScoreSummary
```

Le score est la somme des **`scoreFinal` existants**. Le moteur ne rappelle jamais
le calcul géométrique, la résolution d’une décision ou une transition de domaine.
Il ne remplace aucune flèche, date, décision, position ou trace. Le contexte de
résultat conserve la même volée et ses valeurs historiques.

Chaque volée compte X/M depuis ses indicateurs finaux cohérents ; `nineCount`
compte `scoreFinal == 9`, et `maxScoreCount` utilise le maximum du **profil de
l’impact**, jamais un 10 global. Une valeur 9 peut donc être à la fois un 9 et le
maximum de son profil. X reste une distinction du maximum, sans point ajouté.
Un X corrigé en 9/M perd son X ; un M arbitré à 1 n’est plus un miss. Les décisions
manuelles et leurs invalidations restent intégralement dans les impacts d’origine.

## Portées et dénominateurs techniques distincts

[`MixedScoreSummary.kt`](../../core/scoring/src/main/kotlin/com/lmarchery/core/scoring/mixed/MixedScoreSummary.kt)
expose un `ScoringBreakdown` global et par groupe :

| Portée | Données comptées |
|---|---|
| `validated` | Seulement les `EndStatus.VALIDATED` déclarées |
| `provisional` | Seulement les `EndStatus.EDITING` déclarées |
| `recorded` | Toutes les volées fournies, sans volée future supposée |

Chaque portée possède ses propres points, nombres de volées/impacts, compteurs,
maximums, pourcentages et moyennes. Un zéro provisoire n’est pas injecté dans le
validé ; le statut n’est jamais déduit du nombre d’impacts.

| Champ de `ScoringTotals` | FIXED | FREE |
|---|---|---|
| `recordedCapacityMaximum` | Nombre déclaré × maximum du profil, pour chaque volée enregistrée de cette portée, même vide | Impacts **présents**, M compris, × maximum du profil |
| `recordedArrowMaximum` | Impacts présents × maximum du profil | Identique au maximum de capacité enregistré |
| `percentageOfRecordedCapacity` | Points de la portée / son maximum de capacité enregistré × 100 | Points / maximum des seuls impacts enregistrés × 100 |
| `percentageOfRecordedArrows` | Points / maximum des seuls impacts enregistrés × 100 | Même dénominateur observé |

`calculateRecordedMaximum` retourne le premier maximum pour une volée. Aucun
paramètre `plannedEndCount` n’existe dans cette API : ce n’est **pas** un maximum
théorique final de séance. En FREE, il n’existe aucun nombre attendu implicite
3/6 : le dénominateur inclut chaque impact réellement présent, y compris M, et
évolue avec eux. Vide FREE : maximum 0 et pourcentage `null`.

Si le maximum est nul, le pourcentage reste `null`, jamais 0/NaN. Points nuls
avec maximum positif : 0 %. Sans impact, moyenne par flèche `null` ; sans volée,
moyenne par volée `null`. Une volée vide présente compte pour une moyenne par
volée mais ne fabrique pas d’impact.

Les pourcentages agrégés divisent les sommes de points/maximums de la **même
portée**, pas la moyenne non pondérée de pourcentages individuels. Pour une
séance historique partiellement saisie, le pourcentage existant peut employer
un maximum incluant les volées provisoires/prévues ; il reste inchangé. Il ne
faut pas le comparer au nouveau pourcentage validé sans aligner les périmètres.

### Aucun arbitrage implicite de validation ou d’épreuve

`EndArrowCountRelation` indique `UNBOUNDED`, `BELOW_FIXED`, `AT_FIXED` ou
`ABOVE_FIXED`. Les anciennes volées peuvent être vides, incomplètes ou dépasser
un nombre configuré ; le calcul rapporte ces faits et leur statut enregistré.
**Il n’autorise pas leur validation, ne les rejette pas selon une nouvelle règle
sportive et ne les reclassifie pas.** Cette politique reste à arbitrer avant UI.

Un dépassement FIXED peut produire un ratio de capacité supérieur à 100 % : il
reste visible, sans plafonnement ni altération des scores. Le ratio observé reste
séparé. Ces valeurs techniques ne doivent pas être présentées comme validation
d’une volée ou homologation. La présence de plusieurs dénominateurs permet à la
future interface de présenter un périmètre explicite après décision produit.

## Regroupements exacts et déterminisme

Un groupe utilise l’égalité complète d’`EndConfiguration` : profil/UUID et édition,
distance, mode de score, mode/nombre de flèches **et indoor**. Aucune réduction au
diamètre ou à la valeur maximale. Les profils et éditions restent consultables.
Les groupes exposent eux aussi validé/provisoire/enregistré.

L’ordre canonique des volées est `(index, UUID)` ; l’index n’est pas renuméroté.
Deux sources peuvent fournir le même index sous des UUID différents : l’API ne
prétend pas connaître leurs parents de séance. Les groupes suivent la première
volée de chaque configuration dans cet ordre canonique, indépendamment de la
permutation d’entrée. L’ordre interne des impacts n’est jamais modifié.

`ConfigurationHomogeneity` vaut EMPTY, HOMOGENEOUS ou HETEROGENEOUS selon les
configurations distinctes de l’ensemble. **HOMOGENEOUS n’est pas une certification
réglementaire**. Un total hétérogène reste une somme technique de points ; aucun
classement ou score d’épreuve réglementaire comparable n’est annoncé.

Les entrées de collection sont capturées et les listes de résultats/groupes
possédées et non modifiables. Contextes, configurations, profils et impacts sont
immuables. Les résultats calculés n’exposent pas de `copy()` non contrôlé et ne
sont jamais acceptés comme entrées d’une nouvelle agrégation.

## Précision et débordements

Points, maximums, nombres et compteurs sont des `Long`. Chaque multiplication et
addition des calculs nouveaux utilise `Math.multiplyExact`/`Math.addExact` ; les
`Int` sont convertis **avant** opération. Un résultat hors Long lève une erreur
typée `NUMERIC_OVERFLOW` avec chemin, sans plafonnement, wrapping ou résultat
partiel. Par exemple trois capacités `Int.MAX_VALUE × Int.MAX_VALUE` ne tiennent
pas dans un Long et sont rejetées, même si chaque volée/groupe individuel tient.
Le maximum global est contrôlé aussi quand les portées séparées tiennent.

Les ratios sont des vues `Double` approximatives de numérateurs/dénominateurs Long
exacts conservés dans le résultat. La conversion précède la multiplication par
100 ; les sorties sont contrôlées finies. Aucun score n’est multiplié par 100 dans
un entier. La politique d’arrondi/affichage des très grands résultats reste future.
Les quatre API historiques Int ne sont ni réécrites ni supposées sécurisées par
ce nouveau chemin ; elles gardent leurs contrats et résultats existants.

## Transition future et décisions encore ouvertes

| Couche | Préalable avant activation |
|---|---|
| Agrégats/domaine | Capture immuable et propriétaire de la configuration de volée, initiale distincte ; faire évoluer les validateurs/reconstruction ensemble, sans contourner les anciennes Session/End |
| Room | Contrat de stockage et migration non destructive depuis les versions alors publiées, configuration + volée atomiques, profils/éditions et données anciennes conservés ; aucune migration ici |
| JSON | Nouveau contrat DTO/domaine et conversion explicite des V1/V2 avec leur configuration historique de séance ; éditions depuis le snapshot de profil, jamais devinées ; aucune rétrogradation perdant du mixte |
| Android/SAF | Aucun écran producteur avant persistance et sauvegarde fidèles ; candidat exact, conflits/acquittements et limites de capacité à définir ; future présentation séparant les portées/maximums |
| Statistiques/UI | Choisir libellés, ratios affichés, maximums planifiés, regroupements et arrondis ; aucune comparaison réglementaire de configurations hétérogènes |

Les arbitrages de l’ADR 033 restent ouverts : validation FIXED vide/incomplète/en
excès, limites ergonomiques, maximums planifiés mixtes, presets d’épreuves,
révision d’une configuration déjà utilisée, provenance persistée et journal
post-clôture. Les relations et ratios exposés ici ne tranchent pas ces questions.
Aucune Room V3 ni nouvelle archive n’est créée automatiquement.

## Vérification

45 nouveaux tests couvrent : huit profils publiés, deux côtés classique/poulies,
propriétaires manquants/dupliqués/incorrects, profils et éditions contradictoires,
identités réutilisées, comparaisons homogènes avec les quatre API historiques,
FIXED 3/6/autres/FREE, vide/validé/provisoire/mélangé, décisions X/M/9/maximum et
traces, deux dénominateurs/pondération, ordres/permutations et immutabilité,
entiers extrêmes et débordements par groupe, entre groupes et entre portées.

Le contrôle complet attendu reste `./scripts/dev-check.sh --rerun-tasks --console=plain`,
avec inspection des XML, SQLite natif disque, builds, lint, schéma inchangé et
`git diff --check`. La CI doit réussir sur le commit publié de cette PR empilée.
Aucun appareil, ADB, données utilisateur ou essai physique dans cette tâche.

### Résultat local du 20 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` : **709 exécutions** sans
échec, erreur ou test ignoré : modèle 118, scoring/reconstruction 241 (dont les
45 nouveaux cas), codec V1 10, SQLite natif disque 96, Android JVM debug 122 et
release 122. Les mêmes suites Android s’exécutent sur deux variantes.

Les assemblages debug app/database et leurs lint réussissent ; les XML locaux
contiennent zéro erreur et trois avertissements GradleDependency (deux app, un
database). Les messages de compatibilité KMP/AGP et bibliothèques natives non
strippées préexistants ne sont pas masqués. Aucun changement de dépendance.

45 liens documentaires locaux et `git diff --check` sont conformes. Les modèles,
quatre API historiques, tests antérieurs, schémas Room, stockage, exports et
Android sont identiques à la tête #33. Les tests V2 des autres branches ne sont
pas revendiqués sur cette base. Le Quality gate reste à vérifier séparément sur
le commit publié, sans déduire une réussite de celui de la PR parente.
