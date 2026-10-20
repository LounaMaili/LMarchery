# ADR 033 — Fondation des configurations par volée

Statut : contrat pur implémenté, intégration aux agrégats/persistance/UI future.
Aucune fusion de branche ni validation physique dans cette étape.

## Contexte et frontières

Cette branche indépendante part de `main`,
`e4c7e56817baa3f5a5a366bdf5895b3d38f6b798`. Ses contrats opérationnels sont encore
Room 1 et JSON V1. Les branches de catalogue et JSON V2 ne sont pas fusionnées.
Les PR existantes restent inchangées, y compris l’intégration #32.

Références préparatoires lues, sans intégrer leurs branches :

- [ADR 028 proposé dans #26](https://github.com/LounaMaili/LMarchery/blob/ae96dbda3973445ff850a77ef4e4cb3de9066b04/docs/adr/028-seances-flexibles-edition-desktop.md).
- [Conception des séances personnalisables dans #31](https://github.com/LounaMaili/LMarchery/blob/b2399834ecf6490d82fc433efee40fdfa6fd5379/docs/conception-seances-personnalisables.md).

Aujourd’hui `Session` porte profil, distance, mode/nombre de flèches, indoor et
mode de score. `End` ne porte pas de configuration. La séance impose son profil
exact à tous ses impacts ; `validateSessionUpdate` interdit le changement de
profil d’une séance persistée. Une volée validée garde ses impacts et une séance
terminée ses enfants/clôture. Ces règles restent intégralement en vigueur.

L’ajout d’un champ à `End`/`Session` demanderait un contrat d’archive, une migration
et des validateurs cohérents. **Aucun champ ni adaptateur implicite n’est ajouté.**
`EndConfiguration` prépare une valeur ; il ne rend pas encore possible une séance
mixte dans l’application ou le stockage actuels.

## Valeur immuable autonome

[`EndConfiguration`](../../core/model/src/main/kotlin/com/lmarchery/core/model/session/EndConfiguration.kt)
appartient à `core:model.session` et n’a aucune dépendance Android, SQL, scoring
ou codec. Ce n’est pas une entité exportable : pas d’UUID propre, d’horodatage,
Clock, propriétaire, événement ni statut. Le futur propriétaire sera défini par
l’évolution des agrégats, sans inventer d’identité lors d’un héritage.

Tous les paramètres du constructeur sont explicites, y compris les `null` :

| Champ | Invariant appliqué |
|---|---|
| `targetType` | Définition `TargetType` complète déjà structurellement validée ; UUID, géométrie, zones et paramètres conservés |
| `regulatoryEdition` | Métadonnée **du profil**, `null` si non fournie, sinon chaîne non blanche conservée exactement ; aucun parsing ni édition inventée |
| `distanceM` | Entier strictement positif ; indépendant du diamètre et de la distance par défaut du profil |
| `arrowCountMode` | Enum existant `FIXED` ou `FREE` |
| `arrowCountPerEnd` | Entier strictement positif si FIXED ; obligatoirement `null` si FREE |
| `isIndoor` | Booléen explicite, sans déduction du profil ou de la distance |
| `scoringMode` | Enum existant explicite, sans déduction du profil ou de la distance |

Les bornes positives de `Int` sont admises, sans plafond ergonomique ou sportif
inventé. Cela n’autorise aucun débordement dans de futurs agrégats : leur
arithmétique et leurs budgets devront être traités avant intégration.

La `data class` ne contient que des valeurs immuables. `TargetType` possède déjà
une liste de zones copiée et non modifiable, et `TargetZone` est immuable. Partager
ces objets lors d’un héritage est sûr. Le constructeur et le `copy` généré
appliquent les mêmes contrôles ; aucun setter ni mutation rétroactive.
Égalité/hash incluent tous les champs, profil complet et édition compris.

### Personnalisé ne signifie pas nouveau règlement

`EndConfiguration` désigne un ensemble de paramètres de tir personnalisable,
**pas une épreuve officielle**. La provenance d’un profil World Archery reste
celle de sa définition et de son édition. Exemple : choisir ce même blason à
50 ou 70 m ne modifie pas ses zones, son identité ou sa distance par défaut, et
ne certifie pas cette combinaison. Les raccourcis de nombre ne sont pas des
presets réglementaires. Le mode `COMPETITION` ne certifie pas davantage une épreuve.

`core:model` ne dépend pas de `BuiltInTargetRegistry`. La validation présente est
structurelle ; une édition déclarée n’est pas une preuve d’authenticité, pas plus
que `isBuiltIn`. Un même UUID avec définition/édition différente reste détectable
par comparaison, sans substitution silencieuse. Le futur adaptateur de profil
connu doit vérifier définition **et** édition selon l’[ADR 015](015-sauvegarde-versionnee-reconstruction.md).
Une comparaison signale un écart ; elle n’autorise jamais la republication d’un
profil altéré sous une identité existante. Aucun nouveau profil réglementaire.

## Héritage et remplacement explicite

[`resolveNextEndConfiguration`](../../core/model/src/main/kotlin/com/lmarchery/core/model/session/NextEndConfiguration.kt)
prend seulement des valeurs :

```kotlin
fun resolveNextEndConfiguration(
    initial: EndConfiguration,
    previous: EndConfiguration? = null,
    choice: NextEndConfigurationChoice = NextEndConfigurationChoice.Inherit,
): EndConfiguration
```

| Situation | Résultat |
|---|---|
| `Replace(configuration)` | La valeur explicitement choisie, même sans précédente ou égale à la précédente |
| `Inherit` et précédente présente | Toute la configuration précédente, pas seulement le profil ou le nombre |
| `Inherit` sans précédente | Toute la configuration initiale |

Le futur appelant doit fournir la véritable configuration de la volée précédente.
La fonction ne trie pas d’anciennes volées et ne consulte aucun preset courant.
Un candidat de formulaire non confirmé n’est pas passé comme `Replace` ; annuler
conserve `Inherit`. Le choix typé reste distinct de l’égalité de valeurs :
confirmer explicitement une valeur identique ne devient pas une preuve d’héritage.
Ce choix d’entrée n’est toutefois pas un journal durable de confirmation humaine.

Une configuration sélectionnée devient la précédente pour l’appel suivant.
Revenir à l’initiale nécessite un `Replace(initial)` explicite si la précédente
est différente. Aucun événement, UUID, instant, volée ou écriture n’est produit.
Les valeurs antérieures restent identiques, même lors d’une longue succession de
changements. Partager leur référence ne rend pas les anciennes volées modifiables.

## Raccourcis, validation et comparaison

- `EndArrowCountShortcut.THREE`, `SIX`, `FREE` et
  `configuration.withArrowCountShortcut(...)` ne modifient que mode/nombre.
  FIXED 1, 2, 4, 5, 7, 12 ou tout autre entier positif reste admissible ; aucune
  conversion implicite vers 3/6/libre et aucune préférence UI enregistrée.
- `validateEndConfiguration(distanceM, arrowCountMode, arrowCountPerEnd,
  regulatoryEdition)` retourne les diagnostics typés `EndConfigurationIssue`
  avec champ/code, dans un ordre déterministe. Le constructeur utilise exactement
  cette validation avant d’exiger l’absence d’erreur. Les enums et `TargetType`
  sont déjà typés/validés ; aucun contrôle de catalogue n’est prétendu.
- `compareEndConfigurations(previous, proposed)` retourne les champs différents
  dans leur ordre de déclaration. Il compare les profils par contenu complet,
  UUID inclus, et l’édition séparément. Ce n’est ni une autorisation de révision,
  ni un regroupement statistique simplifié, ni une politique de conflit.

Les futurs contrôleurs pourront traduire les codes de diagnostic pour un
formulaire, présenter les différences et conserver le choix explicite. Le parsing
de texte, l’édition temporaire, les confirmations et les acquittements restent
hors de cette API métier.

## Tableau de transition vers les prochaines étapes

Les numéros Room/JSON successeurs seront décidés selon les versions réellement
intégrées au moment de chaque chantier ; aucun couplage mécanique ni migration
Room V3/format JSON nouveau n’est entrepris ici.

| Étape suivante | Changements nécessaires avant activation | Preuves attendues |
|---|---|---|
| Agrégats et scoring par volée | Définir la configuration initiale et la capture immuable de chaque volée ; faire porter le contexte des impacts/décisions sur leur volée, en conservant les profils historiques exacts et leurs éditions ; adapter transitions et reconstruction ensemble | Homogène numériquement identique ; volées mixtes ; jugement/repositionnement sous le bon profil ; aucun effacement d’historique ni changement rétroactif |
| Room | Après choix du contrat d’agrégat, stockage possédé par la volée, configuration créée atomiquement avec elle ; dérivation explicite des anciennes valeurs de séance, sans recalcul ; intégrer les schémas de matériel effectivement publiés si présents | Vraies bases historiques peuplées et fermées/réouvertes ; toutes lignes/décisions/traces conservées ; conflits, rollback et annulation sous transaction ; mêmes garanties d’identités et références opaques |
| JSON | Nouveau contrat versionné DTO/domaine, initiale/effectives obligatoires ; lire V1/V2 selon leurs règles propres avant conversion explicite ; conserver les octets anciens ; ne pas exporter silencieusement du mixte dans une version incapable de le représenter | Golden historiques, profils/éditions exacts, aller-retour mixte complet, champs manquants/contradictions rejetés, limites strictes et import atomique de toute l’archive |
| Contrôleurs Android | Choix 3/6/libre et nombre personnalisé, distance indépendante, candidat de prochaine volée explicite ; héritage sans formulaire obligatoire ; création/configuration dans un seul PendingSave expected/proposed ; aucune UI productrice avant stockage et sauvegarde fidèles | Annulation sans écriture, double commande, callback périmé, conflit/retry exact, visée/correction/navigation/SAF exclus ; rotation logique, puis essais humains séparés |
| Statistiques mixtes | Contexte effectif de chaque volée, groupes par profil/édition/distance/modes ; périmètre validé/provisoire visible ; maxima par volée puis somme, arithmétique sans débordement et dénominateurs explicites | Fixtures homogènes inchangées, profils/maxima différents, FREE sans supposer 3/6, ensemble vide et poids/dénominateurs ; ne pas comparer des réglages hétérogènes comme un format réglementaire |

Aucun adaptateur `Session → EndConfiguration` ne devine l’édition : `Session`
ne la contient pas. La future conversion devra recevoir aussi le snapshot de
profil historiquement enregistré. Les anciens `End` ne reçoivent pas une
configuration à la lecture par défaut Kotlin. Sa provenance de dérivation devra
être distinguée d’une nouvelle sélection humaine, sans date de choix inventée.

## Arbitrages encore ouverts

À trancher dans les étapes concernées, sans décision implicite dans cette PR :

1. Présentation des nombres fixes personnalisés et éventuels plafonds UX, distincts
   des entiers positifs admis par la valeur métier ; validation d’une volée FIXED
   incomplète ou vide et limites de capacité des futurs parcours.
2. Présentation et validation de presets d’épreuves complètes ; vocabulaire pour
   une configuration personnalisée utilisant un profil officiel. Aucune liste
   nouvelle de formats réglementaires ni certification automatique aujourd’hui.
3. Maximums planifiés pour séances mixtes, regroupements et pourcentages pondérés ;
   politique numérique des très grands comptes/distances avant calcul/affichage.
4. Opération exceptionnelle pour corriger la configuration d’une volée déjà créée,
   même vide : motif, autorisation, révision et journal éventuel ; la sélection
   sportive de la prochaine volée ne fournit aucune autorisation rétroactive.
5. Représentation persistée de la provenance de capture et ordre/versionnement
   des futurs contrats d’agrégats, Room et JSON selon les branches intégrées.
   Les révisions post-clôture et leur journal général restent un chantier distinct.

## Validation

32 tests purs du nouveau contrat : bornes invalides/valides, diagnostics cumulés,
copies, égalité/hash/comparaison, indépendance des paramètres, immutabilité profonde,
raccourcis/nombres personnalisés, héritage/repli/remplacement/candidat abandonné,
choix égal explicite, séquences et non-mutation des anciennes entités. Ils vérifient
aussi que les anciennes interdictions VALIDATED/COMPLETED/profil unique subsistent.
Trois tests additionnels dans `core:scoring` utilisent les huit profils réellement
publiés, leurs éditions et les géométries classique/poulies sans modifier le moteur.

Toutes les suites historiques sont conservées sans modification. Exécuter
`./scripts/dev-check.sh --rerun-tasks --console=plain`, inspecter XML de tests,
SQLite natif disque, builds et lint Android, puis schémas et `git diff --check`.
La CI de la PR doit réussir sur le SHA publié ; un ancien run de main ou d’une
branche empilée n’est pas une validation de cette fondation.
Aucun appareil, ADB, geste humain, Room futur ou archive future n’est revendiqué.

### Résultat local du 20 octobre 2026

`./scripts/dev-check.sh --rerun-tasks --console=plain` réussit avec JDK 21,
Gradle Wrapper 8.10.2, SDK 35 et Build Tools 34.0.0 :

| Contrôle | Résultat |
|---|---|
| Tests modèle | 118 PASS, dont 32 nouveaux |
| Tests scoring/reconstruction | 196 PASS, dont 3 nouveaux |
| Tests codec V1 de main | 10 PASS |
| Tests SQLite natifs sur disque de main | 96 PASS |
| Tests Android JVM | 122 debug + 122 release PASS |
| Builds debug app et database | PASS |
| Lint app et database | PASS, zéro erreur ; un avertissement GradleDependency database |
| Schéma Room 1, API historiques et anciens fichiers de tests | Inchangés |
| Liens documentaires locaux et `git diff --check` | PASS |

Soit **664 exécutions**, zéro échec, erreur ou test ignoré ; les suites Android
sont exécutées sur deux variantes. Cette branche ne contient pas les tests V2 des
PR empilées non fusionnées : aucune validation V2 ou migration n’est revendiquée
sur cette base main. Le schéma Room 1 conserve son empreinte SHA-256
`917ca1379e19ffabf5f441e743f6f0acfeaf5f114fd52e972d5bfcd7c5726d62`.
La CI de livraison se vérifie séparément sur la PR et son commit exact.
