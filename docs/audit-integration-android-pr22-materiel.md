# Consolidation Android — sauvegardes, matériel et visée précise

État au 18 octobre 2026. Aucun merge ni auto-merge n’est effectué par cette
mission. Les essais ci-dessous sont JVM/SQLite/compilation/lint sur PC, pas une
validation physique. Aucun accès au Pixel : APK #22 / Room V1 conservés.

## Livraison et points de contrôle

Les PR sont empilées, chaque chantier de code ayant attendu la validation locale
et le Quality gate du précédent avant de commencer.

| PR | Base exacte | Commit publié | Tests locaux |
|---|---|---|---|
| [#27 SAF V2](https://github.com/LounaMaili/LMarchery/pull/27) | #25, `ffe789f275cb73c6ffc0d81d396dccee3327d3b0` | `27054393053fbae6f41ae4270ca1dc20ab9a32f7` | 811 |
| [#28 catalogue](https://github.com/LounaMaili/LMarchery/pull/28) | #27, `27054393053fbae6f41ae4270ca1dc20ab9a32f7` | `cdca53515e02927a4c6f496b4178065b8258e8e7` | 857 |
| [#29 associations](https://github.com/LounaMaili/LMarchery/pull/29) | #28, `cdca53515e02927a4c6f496b4178065b8258e8e7` | `d956dee5be74fa0246a9132bc91e818ceb0ca556` | 894 |
| [#30 fiabilité](https://github.com/LounaMaili/LMarchery/pull/30) | #29, `d956dee5be74fa0246a9132bc91e818ceb0ca556` | `d7a916bf1bf21b187c2b2dba64460c0d803a53f1` | 906 |

Ce sont des **exécutions** : chaque total comprend les variantes Android debug
et release ; les mêmes suites sont relancées dans plusieurs chantiers. Tous les
tests existants sont conservés. La commande de référence pour chaque tête est :

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
```

Les rapports XML, tests SQLite natifs sur fichiers disque, schémas, build Android,
lint, liens locaux et `git diff --check` sont inspectés séparément. Aucun test
ignoré n’est compté comme réussi. Cinq avertissements GradleDependency préexistants
restent présents, sans mise à jour de dépendance ni affaiblissement de validation.

Quality gates vérifiés sur les SHA de tête publiés, pas seulement un ancien run :
[#27](https://github.com/LounaMaili/LMarchery/actions/runs/39088332734),
[#28](https://github.com/LounaMaili/LMarchery/actions/runs/39089079669),
[#29](https://github.com/LounaMaili/LMarchery/actions/runs/39089900425),
[#30](https://github.com/LounaMaili/LMarchery/actions/runs/39090599039).
Les quatre runs sont terminés avec succès ; chaque étape et les trois validations
Gradle sont présentes dans leurs logs. Aucun contrôle n’a été contourné.

Les ADR d’implémentation 029, 030 et 031 sont dans ces branches empilées. Cette PR
documentaire part de `main` et ne les copie pas artificiellement dans sa base.
La [conception des séances personnalisables](conception-seances-personnalisables.md)
reste préparatoire, dans la direction de #26, sans modification de cette PR.

## Intégration d’essai avec #22

Sources exactes :

- #22 : `c9675a257e1459daf2577220b0c0d0bcd9b3ed6e`, toujours en brouillon.
- Première tête d’essai : #29, `d956dee5be74fa0246a9132bc91e818ceb0ca556`.
- Tête finale incluant le correctif : #30, `d7a916bf1bf21b187c2b2dba64460c0d803a53f1`.

Dans un worktree détaché propre, `git merge --no-commit --no-ff <SHA-22>` a
identifié exactement quatre fichiers en conflit :

| Fichier | Résolution nécessaire |
|---|---|
| `README.md` | Conserver visée précise et sauvegardes V2/catalogue ; éviter les anciens comptes d’ADR devenus inexacts |
| `app/.../app/LMarcheryApp.kt` | Conserver route matériel, launchers SAF racine, panneau de visée et sorties explicites des candidats |
| `app/.../sessions/SessionController.kt` | Composer les refus SAF ; conserver les nettoyages de candidat/visée et de métadonnées aux bonnes transitions |
| `app/.../sessions/SessionUiState.kt` | Conserver les deux familles d’état et leurs conditions d’exclusion, sans choisir un côté entier |

Aucun conflit texte dans ViewModel, repository, `TargetCanvas` ou les fichiers de
loupe. Cela ne prouve pas leur compatibilité fonctionnelle à lui seul.

### Conflit logique à ne pas perdre

Choisir seulement la condition de #29 laisserait ouvrir l’éditeur de matériel
pendant une visée ou une proposition précise ; choisir seulement celle de #22
perdrait la protection du candidat de métadonnées. La résolution testée impose :

```kotlin
canNavigate = ready && impactEdit == null && equipmentEdit == null && !hasAimProposal
canEdit = ready && equipmentEdit == null && /* conditions historiques de saisie */
canEditEquipment = canNavigate && screen == SESSION && session != null
```

`acquireExport()` refuse impactEdit, equipmentEdit **et** hasAimProposal ; V1 et
V2 partagent ce garde. Ne pas effacer une proposition pour acquérir le verrou.
Les commandes de retour, abandon, relecture et acquittement conservent les
nettoyages des deux branches, sans abandon implicite d’un PendingSave.

Quatre tests croisés ajoutés uniquement à l’essai vérifient :

1. Geste actif puis candidat relâché : ouverture des métadonnées refusée, candidat
   préservé, aucun UUID ni SQL alloué.
2. Éditeur de matériel : saisie rapide et précise refusées, puis reprise de la
   confirmation d’une seule flèche après annulation des métadonnées.
3. Échec de confirmation précise : métadonnées refusées, candidat et expected /
   proposed exacts conservés jusqu’à la relance explicitement acquittée.
4. Export V2 : aucune collecte pendant geste, candidat relâché ou métadonnées ;
   collecte unique possible après résolution, sans repli V1.

La première résolution passe 966 exécutions. Elle a été conservée en patch local
puis l’essai de merge a été annulé, sans commit. Le patch a été rejoué dans une
nouvelle HEAD détachée issue de #30, pour tester aussi le correctif final.
Résultat final : **978 exécutions sans échec ni skip**, dont 222 tests Android
debug et 222 release, 103 modèle, 223 scoring, 34 codec et 174 SQLite disque.
Build Android et lint réussis ; cinq avertissements préexistants, schémas
inchangés, 85 liens locaux et `git diff --check` conformes. Ce résultat n’a pas
de run GitHub propre : c’est un assemblage temporaire local non publié.

Empreinte SHA-256 du patch local de résolution et des quatre tests :
`dfb356f3c429e91d3f13b267e5699406ebd9b97984c94ac2aa4d59af80b70acf`.
Il n’est ni un merge publié ni un fichier de données utilisateur. Les tests
croisés devront être portés dans la vraie PR d’intégration ; les CI individuelles
ne prouvent pas automatiquement cette combinaison. Les worktrees d’essai sont
rendus propres après conservation de cette preuve, sans déplacement de branche.

## Revue de fiabilité

| Point | Résultat et limite |
|---|---|
| Conflits d’écriture | Expected complet préservé, jamais rebased automatiquement ; tests natifs deux magasins et état divergent à date identique |
| Annulation/commit incertain | Aucun succès anticipé ; tests rollback après INSERT réel et perte d’acquittement après COMMIT, interfaces explicitement non confirmées |
| Décisions/traces | Aucun changement de scoring ; association après clôture sous triggers interdisant toutes les écritures hors `sessions`, plus égalité des tables après restauration |
| Rotation/processus | État temporaire au ViewModel, tests de réattachement ; arrêt du processus perd les propositions non acquittées, reprise par relecture SQL, sans promesse de journal durable |
| Horodatages | Instant UTC explicites, nanos préservées, révisions antidatées rejetées ; aucune conversion de date civile pendant une association |
| JSON | V1 golden/empreintes conservés, V2 intégral ; plafond 16 MiB et limites strictes, fermeture/relecture exacte, pas d’export partiel ni de downgrade silencieux |
| Identités/migration | UUID v4 et registre global conservés, collisions de types rejetées, schémas inchangés dans ces PR ; migration 1→2 de #23 testée sur bases synthétiques, jamais sur le Pixel |

### Défaut démontré et correction isolée #30

Après un échec d’écriture du catalogue, l’inspection du stocké effaçait ou
remplaçait le diagnostic de cette écriture. La relance exacte pouvait disparaître ;
une erreur technique de relecture pouvait aussi transformer l’affichage d’un
conflit en erreur réessayable. Deux tests de reproduction ont d’abord échoué.

Le correctif sépare `storedDiagnostic` du diagnostic de sauvegarde. Lecture
réussie, absence, erreur typée/lancée et annulation ne modifient plus l’issue de
l’écriture ni sa proposition. Une relecture échouée retire l’ancienne valeur
lue. Six nouvelles régressions passent avec toute la suite. Aucun SQL, contrat
d’identité, transition métier ou politique de conflit n’est modifié.

### Limites à conserver visibles

Une archive dépassant les budgets est refusée avant SAF ; aucune sauvegarde
complète tronquée n’est annoncée. Le découpage de grandes archives n’est pas
implémenté. Les notes temporaires non confirmées ne survivent pas à une mort de
processus. Le matériel associé est une référence à la fiche courante, pas une
preuve des réglages historiques. Le journal général des révisions post-clôture
reste un futur contrat, pas une propriété des traces de repositionnement actuelles.

NON TESTÉ physiquement : fournisseurs SAF V2, formulaires/clavier, sélection réelle
des fiches, rotation Android réelle sur ces nouveaux parcours, combinaison réelle
avec la loupe. Les retours humains antérieurs de #22 ne sont pas réinterprétés
comme validation des nouveaux écrans. Le Pixel et ses séances ne sont pas utilisés.

## Ordre d’intégration recommandé au propriétaire

1. Revoir les bases #23 → #24 → #25 puis #27 → #28 → #29 → #30 dans cet ordre,
   en actualisant et revérifiant chaque état réellement destiné à la fusion.
2. #22 reste indépendante et en brouillon jusqu’à la fin de son protocole
   physique. Selon l’ordre finalement retenu, résoudre les quatre conflits et
   composer les gardes ci-dessus. Porter les quatre tests croisés et relancer
   la validation complète ainsi que la CI sur l’état final.
3. Effectuer les essais Android de la combinaison sur une installation indépendante.
   Ne pas migrer le Pixel Room V1 tant que sa campagne #22 doit être préservée.
4. Revoir #26 et la présente conception documentaire séparément. Valider le contrat
   de configuration/migration/archive avant toute UI qui produirait ces données.

Ce rapport n’autorise ni n’effectue ces intégrations. `main`, #22–#26, leurs
branches et les protections restent inchangés ; aucune fusion automatique.
