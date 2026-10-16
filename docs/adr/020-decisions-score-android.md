# ADR 020 — Jugement de limite et correction exceptionnelle dans Android

## Portée et contrats conservés

Exposer dans Compose les demandes typées de l'[ADR 011](011-decisions-score-explicites.md),
à position constante, avant validation de la volée. Les profils, le scoring, les
contrats de sauvegarde et le schéma Room **1** restent inchangés. Le parcours
reste limité aux quatre profils WA indoor, trois flèches et entraînement de
l'[ADR 018](018-integration-android-room.md). Les profils historiques avec X et
les profils personnalisés restent consultables ; leur sélection n'est pas ajoutée.

## Sélection et propositions exclusives

La sélection numérotée conserve les UUID séance/volée/impact. Elle offre deux
actions distinctes : « Repositionner » et « Corriger le score ». `ImpactProposal`
est une union fermée : sélection simple, repositionnement, jugement de limite,
correction exceptionnelle. Aucun ensemble de booléens indépendants ne peut
activer simultanément plusieurs opérations. Les phases de soumission de la
PR 17 restent communes ; après succès, la sélection simple est conservée.

Les choix, le motif et le candidat restent hors de `SessionUiState.saved`.
La position, le score et les totaux confirmés ne changent qu'après acquittement.
Le blason est en consultation pendant une décision de score : toucher ne crée
ni flèche ni déplacement. L'annulation avant soumission n'écrit rien. Retour,
validation, navigation entre volées et clôture conservent les protections de
l'[ADR 019](019-selection-repositionnement-android.md). La rotation conserve le
contrôleur du ViewModel ; après mort du processus, seule la base est restaurée.

## Jugement de limite

`scoreDecisionOptions(target, point)` adapte les valeurs du moteur pour la
présentation. `findBoundaryHints` donne en priorité les suggestions proches,
avec sa tolérance UX existante. Les autres limites canoniques de
`TargetType.scoreBoundaries()` sont accessibles par une action secondaire
explicite, même si aucune suggestion n'existe. Chaque choix affiche type, rayon,
valeurs intérieure/extérieure et écart radial normalisé.

Aucune limite ni aucun côté n'est présélectionné. Choisir une autre limite
efface le côté précédent. Les valeurs viennent du profil : pas de soustraction
de 1, d'inférence depuis le score calculé ou de X fabriqué. Les types zones,
X/non-X (si activé) et bord/M sont représentés. Les quatre profils proposés
dans Android ont X désactivé. Les tests de présentation couvrent aussi les
valeurs non consécutives et plusieurs suggestions proches d'un profil synthétique,
sans rendre ce profil éditable dans le parcours Android actuel.

La tolérance tactile est une aide de présentation, jamais une preuve de contact
physique du tube, une condition réglementaire du jugement ou une promotion.
Un humain peut choisir une vraie limite hors tolérance. La confirmation utilise
`ScoreDecisionRequest.JudgeBoundary(reference, selected, decidedAt)`, puis
`ArrowImpact.withFinalScore(targetType, request, updatedAt)`. Le domaine retrouve
la véritable frontière et contrôle son côté. Une confirmation de même score
reste `BOUNDARY_JUDGMENT`, avec date UTC et référence de limite.

## Correction exceptionnelle

L'action secondaire « Correction exceptionnelle » quitte le jugement et efface
ses choix temporaires. Les valeurs proposées sont les scores réellement présents
dans les zones, M/0 et la distinction X seulement si le profil l'autorise. Aucun
intervalle entier artificiel ne remplit des scores absents. Un motif non blanc
est obligatoire ; le texte enregistré est débarrassé de ses espaces périphériques.

La confirmation construit uniquement
`ScoreDecisionRequest.ExceptionalCorrection(selected, reason, decidedAt)` et
appelle le même chemin métier contextualisé. Une valeur numériquement inchangée
reste `EXCEPTIONAL_CORRECTION`, avec motif et date. L'écran distingue score
géométrique, score final enregistré et proposition, et affiche la provenance
courante lors d'une nouvelle sélection. Aucun retour implicite à `AcceptGeometry`
n'est proposé pour supprimer une décision manuelle.

## Confirmation, temps et données conservées

La confirmation retrouve l'impact dans sa volée initiale du dernier `DomainArchive`
confirmé. La Clock injectable est lue une seule fois : `decidedAt = updatedAt`.
Une horloge antérieure à la révision est refusée avec diagnostic, jamais corrigée.
Le chemin est exclusivement : demande typée → `withFinalScore` → `End.withImpact`
→ `Session.withEnd` → `validateSessionUpdate` → capture/reconstruction d'archive
→ `repository.update(expected, proposed)`.

UUID, index, point (bits Float compris), création, origine de position, profil,
notes et traces de repositionnement restent inchangés. X/M dérivent du résultat
validé. Une simple décision de score ne crée aucune trace de repositionnement.
Une volée `VALIDATED`, une séance `COMPLETED`, une mauvaise identité, une
configuration incompatible ou une sauvegarde non résolue interdisent ce parcours.

Le repositionnement ultérieur suit toujours l'[ADR 012](012-repositionnement-explicite-impacts.md) :
motif obligatoire pour invalider la décision manuelle, retour à la nouvelle
géométrie et conservation structurée de cette décision dans la nouvelle trace.
Les traces antérieures ne sont ni réécrites ni supprimées.

## Transactions, erreurs et limite d'historique

Le verrou de soumission est posé avant le lancement coroutine. Les doubles
confirmations n'émettent qu'une écriture. Une erreur conserve le dernier état
confirmé et le `PendingSave` complet. Le retry technique explicite réutilise
exactement les mêmes archives, identités, choix, motif et timestamps, sans relire
l'horloge ou reconstruire la demande. Un conflit n'est pas retenté automatiquement ;
consulter le stocké conserve la proposition. L'abandon avec relecture n'efface
la proposition qu'après réussite de la lecture. Aucune fusion selon `updatedAt`.

**Il n'existe pas de journal exhaustif des jugements successifs à position
constante.** Une nouvelle décision manuelle remplace la décision courante. Seules
les décisions invalidées par repositionnement sont conservées dans ces traces.
Cette PR ne fabrique pas d'historique supplémentaire et ne change aucun schéma.

## Validation et exclusions

Tests du contrôleur avec repository simulé/Clock fixe : choix explicites,
annulation, géométrie inchangée, provenance même à score égal, garde des statuts,
acquittement, conflit/retry, horloge et compatibilité du repositionnement. Les
tests SQLite natifs sur disque vérifient références/motifs/dates, fermeture et
réouverture, décisions de même score, rollback tardif, concurrence et immutabilité
des anciennes traces sous triggers. Les tests PR 17 sont conservés.

La validation ADB est consignée séparément dans le
[protocole PR 18](../tests-terrain-v1.md#protocole-decisions-de-score-pr-18) ; elle ne
prouve pas l'ergonomie au doigt. Pas de suppression, drag/drop, zoom, réouverture,
nouveau profil, règle de pénalité, JSON, synchronisation ou nouveau contrat métier.

*16 octobre 2026*
