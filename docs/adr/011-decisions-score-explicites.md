# ADR 011 — Décisions finales explicites et contextualisées

## Contexte

La résolution historique promeut 9 vers 10 en `COMPETITION` à `r = 0.105` depuis
une tolérance UX. Les corrections scalaires ne vérifient pas les valeurs réellement
présentes dans la cible, ne conservent pas de provenance structurée et laissent
parfois les indicateurs géométriques X/M après modification du score final.

Le constructeur et `copy` d'`ArrowImpact` permettent aussi des valeurs et
indicateurs indépendants. Corriger uniquement le moteur laisse ces chemins ouverts.
Le propriétaire a retenu explicitement l'option **contexte de cible obligatoire**,
avec valeurs finales dérivées d'une décision validée, après examen de cette rupture
d'API. Aucun instantané brut distinct ni stockage n'est introduit dans cette PR.

## Décision

### Trois demandes explicites

`ScoreDecisionRequest` distingue :

- `AcceptGeometry` : score et X géométriques, sans jugement manuel ;
- `JudgeBoundary(boundary, selected, decidedAt)` : référence de limite réelle,
  valeur choisie et date ;
- `ExceptionalCorrection(selected, reason, decidedAt)` : valeur légale, motif
  non blanc obligatoire et date.

Une demande commencée peut avoir `selected = null`, mais sa résolution est alors
rejetée ; elle ne devient jamais une acceptation géométrique implicite ni un
`ArrowImpact`. Les suggestions seules ne bloquent pas la validation d'une volée.

La résolution contextualisée reçoit `TargetType`, `NormalizedPoint`, le mode et
la demande. Elle recalcule la géométrie ; aucun `ScoreResult` fourni par l'appelant
n'est accepté comme source de vérité. Sans décision, les trois modes conservent
la valeur géométrique. À `r = 0.105`, géométrie 9 et final 9 ; les suggestions
10/9 restent disponibles sans aucune attribution automatique.

### Validation des limites et valeurs

`TargetType.scoreBoundaries()` reconstruit les limites canoniques du profil :
zones réellement voisines, X/non-X si activé, bord/M. Le moteur de suggestions
de l'ADR 010 utilise les mêmes définitions, avec sa tolérance inchangée.

Le jugement identifie une `BoundaryReference(type, radiusNormalized)`. Le moteur
retrouve cette référence exacte dans le profil et vérifie que `selected` est un
des deux côtés réels. Les côtés et la distance d'un `BoundaryHint` construit ou
copié par un appelant ne sont pas des preuves. Le jugement ne dépend d'aucune
tolérance UX : un archer peut arbitrer hors de la proximité tactile. Les scores
ne sont pas supposés consécutifs et les limites coïncidentes restent distinctes.

Les valeurs légales sont M/0 et les scores présents dans les zones, sans remplir
artificiellement l'intervalle `minScore..maxScore`. X est une distinction du score
maximal, jamais un point supplémentaire ; il exige `xRingEnabled`. Un maximum
non-X reste possible. `isMiss` est dérivé de `scoreFinal == 0` et M exclut X.

### Résultat validé et provenance

`FinalScoreDecision` est immuable, sans constructeur scalaire public ni `copy`
libre. Sa fabrique valide le contexte, la valeur géométrique, la valeur finale
et `ScoreDecisionProvenance` :

| Type | Données de provenance |
|------|-----------------------|
| `GEOMETRY` | Pas de date manuelle, limite ou motif |
| `BOUNDARY_JUDGMENT` | Date et référence de la limite |
| `EXCEPTIONAL_CORRECTION` | Date et motif non blanc |

Une confirmation qui conserve le score numérique reste manuelle. `InputMode`
décrit séparément la provenance de la position. Le booléen historique
`isCordDecisionManual` est dérivé du seul jugement de limite ; une correction
exceptionnelle reste manuelle via sa provenance, sans devenir un cordon.

### ArrowImpact, copies et reconstruction

`ArrowImpact` exige un `TargetType` explicite et un résultat validé pour ce même
profil et ce même point. Sans résultat fourni, il accepte la géométrie. Les
scores, la distance et X/M sont dérivés de ce résultat. Les anciens champs
scalaires optionnels du constructeur contextualisé sont des assertions de
reconstruction, rejetées s'ils contredisent les valeurs dérivées.

`withFinalScore(targetType, request, updatedAt)` est le chemin de modification
typée. `copy` conserve la décision et autorise les métadonnées ; ses paramètres
scalaires de scoring ne peuvent changer aucune valeur ni inventer un booléen de
provenance. Les paramètres existants de copie et de décomposition restent
disponibles. Une provenance manuelle ne peut être effacée par un retour implicite
à la géométrie ; un nouveau jugement ou une correction conserve une trace manuelle.
Déplacer un impact déjà arbitré est rejeté et reste une tâche séparée.

Une volée ne mélange pas les profils de ses impacts. La séance vérifie aussi
qu'ils correspondent exactement à son propre profil immuable. Les totaux et
compteurs utilisent les valeurs et indicateurs finaux ainsi validés.

### Temps et garanties de révision

Les ADR 008 et 009 restent applicables. `createdAt` est conservé, `updatedAt`
ne recule pas, et une date manuelle reconstruite vérifie
`createdAt <= decidedAt <= updatedAt`.

Une nouvelle demande manuelle doit être datée au moins au précédent `updatedAt`,
même si elle reproduit exactement un ancien jugement. Les remplacements via
`End` et `Session` vérifient aussi l'historique de l'impact : nouvelle décision
antidatée, changement de profil, effacement de provenance ou déplacement d'un
impact arbitré sont rejetés. Une copie de métadonnées peut conserver une date
de décision plus ancienne que son nouveau `updatedAt`. La propagation
impact → volée → séance garde les mêmes maximums et contrôles temporels.

### Modules et compatibilité

Les valeurs de limites, demandes, provenance et résultats validés résident dans
`core:model` pour être utilisables par les invariants du modèle. Le sens de la
dépendance reste `core:scoring` → `core:model`, sans changement Gradle. Les imports
Kotlin existants `core.scoring.BoundaryHint`, `BoundaryType`, `BoundaryValue` et
`FinalScoreDecision` sont maintenus par des alias ; cela n'est pas une garantie
de compatibilité binaire JVM des anciens noms de classes.

Les anciennes entrées sans contexte sont dépréciées et rejettent explicitement
l'opération : constructeur scalaire d'`ArrowImpact`, ancien `withFinalScore` et
ancien `resolveFinalScore(ScoreResult, ...)`. Le paramètre historique
`userSelectedScore` de `scoreArrowImpact` doit rester nul ; une valeur exige la
nouvelle demande typée. Aucun adaptateur n'invente une limite, un motif ou une
date pour préserver une ancienne correction libre. Les appels de production et
fixtures de tests du dépôt sont migrés ; l'appel Android sans décision conserve
sa signature et son comportement d'acceptation géométrique.

## Limites avant Room

La future reconstruction doit retrouver la cible avant de résoudre la décision,
puis vérifier les assertions du fichier. Un UUID de cible n'identifie pas une
version de profil : la politique de versionnement/migration reste à décider.
Les instantanés historiques sans provenance structurée ne permettent pas de
reconstituer sûrement un jugement ; leur migration reste une décision distincte.

`LocalDateTime` reste provisoire. La convention UTC/fuseau et les conversions
des offsets illustratifs de l'export seront définies avant la persistance.
Aucun Room, import/export opérationnel, nouvel écran, profil réglementaire,
blason réduit ou multispot n'est ajouté. La détection tactile reste une aide
géométrique et ne prouve jamais le contact physique du tube avec un trait.

*15 octobre 2026*
