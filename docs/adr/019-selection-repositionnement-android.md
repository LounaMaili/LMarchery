# ADR 019 — Sélection et repositionnement explicites dans Android

## Portée

Étendre le parcours de l'[ADR 018](018-integration-android-room.md) pour corriger
une position avant validation. Les opérations et preuves de l'[ADR 012](012-repositionnement-explicite-impacts.md),
les instants UTC et les mises à jour optimistes de l'[ADR 017](017-mise-a-jour-seances-room.md)
restent inchangés. Aucun changement de scoring, profil, schéma ou dépendance.

## Sélection et états temporaires

Chaque flèche de la volée éditable affichée possède un bouton numéroté dans la
liste. La sélection porte sur les UUID **séance, volée et impact**, jamais sur
une proximité graphique ou un index de tableau. Deux flèches superposées restent
sélectionnables sans ambiguïté. Le toucher du blason ne sélectionne pas un impact.

`SessionUiState.impactEdit` est un état de présentation conservé par le contrôleur
du ViewModel. Il distingue `SELECTED`, `AWAITING_POINT`, `CANDIDATE`, `SAVING`,
`CONFIRMED` et `FAILED`. Les commandes sélection/repositionnement sont réservées
à la volée `EDITING` affichée, d'une séance `IN_PROGRESS` compatible avec les
quatre profils indoor, trois flèches et le mode entraînement du premier parcours.
Une volée pleine reste repositionnable : ce droit ne dépend pas de `canAddImpact`.

« Repositionner » active le mode explicite. Le toucher propose une position et
appelle le moteur existant pour un aperçu géométrique, sans horodatage, allocation
d'UUID, trace ou écriture. Le candidat et son aperçu restent **hors de `saved`**.
Le point identique est refusé avec diagnostic et suppression d'un éventuel ancien
candidat, pour empêcher de confirmer une proposition devenue ambiguë.

`TargetCanvas` distingue consultation, ajout et repositionnement. Le routage
du contrôleur interdit tout ajout pendant une sélection/correction, même lorsqu'il
reste des places. Tous les impacts confirmés restent visibles ; un anneau orange
entoure la sélection et une croix violette repère le candidat. Les coordonnées
utilisent toujours `TargetGeometry`. Les positions et scores enregistrés et
proposés sont aussi affichés en texte : la couleur seule ne porte pas leur sens.

## Confirmation et historique

La confirmation retrouve l'impact par UUID dans la volée d'origine de l'archive
confirmée. Elle utilise la Clock injectée, puis compose exclusivement :

`ArrowImpact.reposition` → `End.withImpact` → `Session.withEnd` →
`validateSessionUpdate` → capture/reconstruction du `DomainArchive` →
`repository.update(expected, proposed)`.

Une horloge antérieure à la révision de l'impact produit un diagnostic, sans date
artificielle ni écriture. Les contrôles métier supplémentaires restent applicables.
UUID, index, création et origine de position sont conservés. Le domaine recalcule
le score et X/M, invalide l'ancienne décision et produit seul la trace historique.
Le motif saisi est obligatoire pour une décision manuelle ; il est facultatif
pour une décision géométrique. Un champ facultatif vide signifie aucun motif.
L'interface n'offre ni jugement de cordon ni correction exceptionnelle de score.

Une seule sauvegarde peut être soumise : le verrou `busy` est posé avant le
lancement coroutine. La seconde confirmation n'est pas mise en file. Pendant
l'attente, `saved` et ses totaux restent inchangés ; le candidat est toujours
signalé comme non enregistré. Le succès remplace `saved` par la proposition
exactement acquittée et conserve la sélection avec le statut `CONFIRMED`.
L'utilisateur peut la fermer ou commencer une nouvelle correction explicite.

## Annulation, navigation et erreurs

Avant soumission, « Annuler le repositionnement » efface uniquement la sélection
et le candidat en mémoire, sans écriture. Le retour propose explicitement
d'annuler cette correction avant de quitter ; refuser garde tout l'état.
Validation, nouvelle volée, clôture et changement de volée sont bloqués pendant
la sélection, jusqu'à fermeture/annulation explicite. Une autre flèche peut être
sélectionnée directement seulement au stade de sélection simple ou après succès.

Une fois l'opération soumise, l'annulation locale ne peut plus l'effacer.
`PendingSave` garde ses archives `expected` et `proposed` ; `impactEdit` conserve
identités, candidat, aperçu et motif en cas d'échec. La politique de l'ADR 018
s'applique : retry technique explicite strictement identique, pas de rebase sur
une lecture plus récente, pas de retry de conflit, consultation sans abandon.
Un abandon avec rechargement n'efface rien avant réussite de la lecture ; un
retour avec abandon demande également confirmation. Aucune trace n'est réémise
par un retry et aucune annulation coroutine n'est annoncée comme un succès.

La rotation conserve le contrôleur du ViewModel et sa proposition. Après mort du
processus, seuls les états réellement committés sont retrouvés : un candidat
non confirmé n'est ni restauré ni journalisé sur disque dans cette PR.

## Validation et limites

Les tests du contrôleur utilisent repository simulé et Clock fixe. Ils couvrent
superposition, candidats/annulations sans écriture, invalidation avec motif,
identités/création, M, corrections successives, garde des statuts, navigation,
erreurs et conflits, horloge antérieure et double confirmation sous sauvegarde
suspendue. Les tests SQLite natifs sur fichiers disque couvrent l'ajout exact des
traces, décisions précédentes, coordonnées IEEE 754/nanosecondes, réouverture,
rollbacks et absence de réécriture des anciennes preuves sous triggers.

Les tests ADB et essais tactiles sont consignés séparément dans le
[protocole terrain](../tests-terrain-v1.md#protocole-selection-et-repositionnement-pr-17).
ADB ne prouve pas l'ergonomie au doigt, avec gants ou en plein soleil. Le blason
reste carré et peut nécessiter du défilement en paysage. Aucun glisser-déposer,
zoom, sélection tactile ambiguë, suppression, réouverture, migration, import/export
ou nouveau profil n'est ajouté.

*16 octobre 2026*
