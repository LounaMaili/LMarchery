# ADR 012 — Repositionnement explicite des impacts

## Contexte

L'[ADR 011](011-decisions-score-explicites.md) interdit le déplacement d'un impact
arbitré, pour éviter qu'un jugement lié à une ancienne position soit réutilisé.
Les impacts géométriques peuvent encore être déplacés implicitement par `copy`.
Un simple retour à la géométrie ferait perdre la provenance manuelle. Une trace
unique écrasée au déplacement suivant ne conserverait pas plusieurs invalidations.

## Décision

### Une opération pour toute correction de position

L'opération Kotlin pure réside sur le modèle déjà contextualisé :

```kotlin
// Méthode d'ArrowImpact
fun reposition(
    newPoint: NormalizedPoint,
    changedAt: Instant,
    reason: String? = null,
): ArrowImpact
```

Il s'agit d'une méthode membre, sans dépendance à Android. Le `TargetType`
immuable de l'impact fournit le contexte ; aucun second profil ni `ScoreResult`
fourni par l'appelant n'est nécessaire. `scoreArrowImpact` reste la création
initiale, et `withFinalScore` reste une décision de score à position constante.

L'opération conserve UUID, index, `createdAt`, profil, note et `InputMode` initial.
Elle recalcule le rayon, le score et le X géométriques selon l'ADR 009. La valeur
finale devient cette géométrie avec provenance `GEOMETRY` et X/M dérivés : aucune
ancienne décision de limite ou correction exceptionnelle n'est appliquée au
nouveau point. Un nouveau jugement reste ensuite possible selon l'ADR 011,
y compris hors de la tolérance UX, avec les mêmes règles réglementaires.

`changedAt >= updatedAt` précédent est obligatoire ; l'égalité reste autorisée.
Un point identique est rejeté explicitement : il ne sert pas à effacer un jugement.
Le motif est obligatoire et non blanc si la décision courante est manuelle,
même si elle confirmait le score géométrique. Sans décision manuelle, il est
facultatif ; un motif fourni blanc est rejeté. Aucune mutation de l'ancienne
valeur n'est effectuée, même en cas d'erreur numérique ou temporelle.

### Traces limitées aux repositionnements

`ArrowImpact.repositions` est une liste immuable ordonnée d'`ImpactReposition`.
Chaque trace conserve :

- UUID, création et origine de position de l'impact ;
- ancienne position et résultat validé complet (`previousDecision`), incluant
  valeur géométrique, valeur finale, type, date et limite/motif du jugement ;
- `previousUpdatedAt`, nouveau point, `changedAt` et motif de repositionnement.

`invalidatedManualDecision` expose l'ancienne décision seulement si elle était
manuelle. Le jugement cesse d'être actif mais reste consultable dans cette trace.
Les déplacements purement géométriques portent aussi une trace pour pouvoir
vérifier leur caractère explicite et les révisions reçues dans les agrégats.

Les traces sont ajoutées sans écrasement : c'est nécessaire pour les déplacements
répétés, les retours à une ancienne position et plusieurs jugements invalidés.
Elles ne constituent pas un journal général : les changements de note, validations,
décisions de score successives à position constante, etc. ne sont pas journalisés.
Aucun nouveau UUID d'événement ni stockage n'est introduit. La croissance est
limitée aux corrections effectives de position ; une éventuelle politique
d'archivage devra préserver les invalidations avant toute persistance.

### Constructeurs, copies et remplacements

`ImpactReposition` n'a ni constructeur public ni `copy` libre ; seule l'opération
de repositionnement émet une trace. `ArrowImpact` copie défensivement sa liste,
valide son contexte, sa continuité spatiale et temporelle, son dernier point
et les dates des décisions prises après les déplacements.

`copy` d'impact est réservé aux métadonnées : tout changement de coordonnées,
de valeurs scalaires ou d'origine `InputMode` est rejeté. Il conserve les traces,
comme `withFinalScore`. Un remplacement identifié par UUID exige le même profil,
la même création et origine, une date non décroissante et le préfixe intégral des
traces existantes. Toute nouvelle trace doit partir du précédent point
et d'une révision au moins aussi récente. Sa décision antérieure doit être celle
déjà connue ou une nouvelle décision conforme à l'ADR 011, sans effacement manuel
ni antidatage. Sans nouvelle trace, le point ne change
pas et la provenance manuelle ne peut être effacée. Plusieurs repositionnements
peuvent être intégrés en une fois sans mises à jour intermédiaires des parents,
comme une décision manuelle suivie d'un déplacement explicite.

`End.withImpact`, `End.copy`, `Session.withEnd` et `Session.copy` vérifient ces
règles. La séance compare les UUID d'impacts à travers toutes les volées, y
compris si une volée reconstruite change d'UUID ; ces UUID sont uniques dans la
séance. Les copies ne peuvent supprimer un impact porteur de traces pour les
faire disparaître. Un futur parcours de suppression devra définir explicitement
la conservation de cette provenance ; aucun parcours de suppression n'est ajouté.

Une volée `EDITING` accepte l'impact repositionné. Une volée `VALIDATED` ne peut
ni être rouverte ni changer d'impacts par copie ou remplacement ; une séance
`COMPLETED` ne peut ni être rouverte ni changer ses volées. Les copies de
métadonnées des parents restent possibles selon l'ADR 008, sans toucher leurs
enfants ni déplacer la date de validation/clôture. La propagation impact → volée
→ séance conserve les maximums et les contrôles temporels existants.

## Limites avant Room

Un constructeur isolé valide un instantané, sans registre global des UUID : il
ne peut deviner un historique absent. Une reconstruction reçue en remplacement
doit impérativement être comparée à l'agrégat courant par les chemins contrôlés,
jamais substituée directement comme nouvelle source de vérité. Un futur adaptateur
devra retrouver le profil, rejouer et valider les traces et les décisions dans
l'ordre, puis vérifier l'historique par rapport aux données déjà connues.

Le format de ces traces est décrit comme besoin d'export, sans sérialiseur ni
import effectif. L'[ADR 014](014-convention-temporelle-utc.md) fait porter toutes
les dates de traces et décisions par `Instant`. Versionnement du profil et
migration des anciens fichiers restent à définir avant Room. Aucune
interface de déplacement, réouverture, nouvelle cible, dépendance ou règle de
cordon n'est ajoutée.

*15 octobre 2026*
