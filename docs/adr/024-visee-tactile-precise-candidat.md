# ADR 024 — Visée tactile précise et candidat non destructif

## Portée

Ajouter un mode de présentation facultatif au parcours des
[ADR 018](018-integration-android-room.md), [019](019-selection-repositionnement-android.md)
et [023](023-historique-et-presentation-scores-android.md). Le mode rapide reste
le défaut. Aucun changement du domaine, scoring, profil, Room, JSON ou dépendance.
La PR reste en brouillon, sans fusion, jusqu’à évaluation ergonomique humaine.

## Coordonnées et rendu

`TargetGeometry` reste l’unique conversion pixel / `NormalizedPoint`. La taille
est celle du Canvas intérieur, après son padding, avec le minimum des dimensions
pour diamètre et le centre visuel pour origine. Les tailles non finies sont
refusées. Les coins du carré restent des positions M, sans rabattement sur le cercle.

`SessionUiState.aimedPoint` fournit un seul point normalisé au réticule, à la loupe
et à la présentation : geste en cours, sinon candidat relâché, sinon candidat de
repositionnement existant. La confirmation utilise ce candidat relâché ; elle
reste impossible pendant un geste. Le relâchement emploie sa dernière position.

Le blason et la loupe utilisent **le même renderer** pour les zones, couleurs,
limites, axes, impacts enregistrés, sélection et candidat. La loupe ×4 applique
seulement une transformation visuelle autour du point visé, découpée dans un cercle
placé dans le quadrant opposé. Elle ne modifie ni coordonnées ni score. Son rayon
vaut 20 % du diamètre du Canvas, avec une marge de 2,5 %. Ce choix UX reste à
évaluer au doigt ; aucun diamètre physique du tube n’est simulé.

En mode précis, la largeur maximale du blason est aussi limitée à 65 % de la
hauteur de fenêtre, avec plafond 480 dp. Après défilement, le carré et sa loupe
peuvent ainsi tenir en paysage. La visée ne déclenche aucun défilement automatique.
Le mode rapide conserve son dimensionnement. Ce n’est pas un zoom/panoramique général.

## Gestes et annulation

Référence consultée le 16 octobre 2026 :
[gestion des gestes Compose](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures).
Un unique détecteur est choisi selon le mode : `detectTapGestures` historique en
rapide, `awaitEachGesture` avec `awaitFirstDown` et événements de pointeur en précis.
Il n’y a pas de détecteur d’ajout concurrent du détecteur de déplacement.

`PrecisionGesture`, indépendant de Compose, suit un seul pointeur. Appui et
mouvements produisent un aperçu ; le relâchement produit un candidat, jamais une
sauvegarde. Sortir du rectangle, changer de pointeur, recevoir un événement consommé
ou un deuxième doigt annule le geste jusqu’au prochain appui. Même un retour à
l’intérieur après sortie ne réactive pas ce geste. Aucun point n’est plafonné.

Le mode précis consomme les événements du blason dès l’appui pour éviter le
scroll involontaire de LazyColumn ; le défilement reste possible hors du blason.
Une garde en passe `Initial` sur l’écran détecte aussi un second doigt hors du
blason, annule la visée et consomme les interactions jusqu’au relâchement de tous
les doigts, y compris sur les boutons. Elle ne change pas le mode rapide.

Chaque geste reçoit un token de présentation monotone du contrôleur et un contexte
UUID séance/volée/impact éventuel. Le début vérifie le contexte capturé par le
Canvas, sans le remplacer par celui d’une autre volée. Déplacement, fin et annulation
vérifient le token ; un ancien callback ne termine ni n’efface un nouveau geste.
Le handler est annulé sur changement d’identité, mode, taille ou destruction.
Son `finally` annule seulement le geste actif correspondant. Une rotation ne peut
ainsi produire une flèche ; un candidat **déjà relâché** reste dans le ViewModel.

## Candidats et commandes

`ActiveAim` contient l’aperçu du geste ; `PositionCandidate` contient contexte,
point et résultat géométrique du moteur existant. `newImpactCandidate` conserve
la nouvelle flèche relâchée hors de `saved`. Aucun UUID métier, horodatage, total,
trace ou enregistrement SQL n’est créé par les aperçus.

Un nouveau geste peut affiner le candidat. S’il est interrompu, le candidat
précédemment relâché est conservé, sans remplacement par une position partielle.
« Annuler la nouvelle flèche » supprime seulement cette proposition en mémoire.
Le changement de mode attend sa confirmation ou son annulation explicite. Pendant
un geste sans candidat relâché, changer de mode annule simplement le geste.

Une proposition d’ajout exclut sélection/correction d’une autre flèche, navigation,
validation, nouvelle volée, clôture et sauvegardes import/export. Le retour demande
confirmation d’abandon. Les anciens points d’entrée d’ajout ne contournent pas
le mode précis. Les gardes de consultation/volée pleine/statuts restent applicables.

## Confirmation et échec SQL

`confirmNewImpactCandidate` revérifie mode, séance, volée éditable, contexte,
capacité et absence de geste actif. Il réutilise le même `saveNewImpact` que
`addImpact`, avec `scoreArrowImpact` → `End.withImpact` → `Session.withEnd` →
capture/reconstruction → `PendingSave` → `repository.update(expected, proposed)`.
UUID et instant sont alloués une seule fois pour la proposition soumise. Le verrou
est posé avant la coroutine ; un double appui ne soumet pas une seconde écriture.

Jusqu’à l’acquittement, positions et totaux durables restent ceux de `saved`.
Un succès efface le candidat ; un échec le conserve avec le PendingSave complet.
Le retry technique réemploie strictement les mêmes expected/proposed, UUID et date.
Après soumission, le libellé « enregistrement non confirmé » ne prétend pas que
la ligne est absente : un commit peut avoir précédé la perte d’acquittement.
Un conflit n’est jamais rebasé ni retenté automatiquement. Consulter la version
stockée conserve l’action ; un abandon avec relecture n’efface rien avant succès.
Après mort du processus, seules les données réellement committées sont redécouvertes.
Le candidat n’est pas un journal durable et le mode repart rapide.

## Repositionnement et score

La fin d’un geste de repositionnement appelle `proposeReposition`. Le candidat
reste celui d’`ImpactProposal.Reposition`, avec son motif et sa confirmation
existants. Annuler un geste conserve une proposition relâchée précédente. Changer
le mode précis/rapide ne supprime pas cette proposition. Aucun nouvel impact.

La confirmation suit exactement l’ADR 019 : UUID, création, origine de saisie et
traces conservés ; motif obligatoire pour invalider une décision manuelle. Aucun
repositionnement après validation de la volée ou clôture de la séance.

L’aperçu géométrique reste étiqueté **non enregistré** ; le score enregistré est
visible séparément lors d’un repositionnement. Le moteur existant calcule les
scores, sans promotion de cordon. Loupe/réticule ne sont pas une preuve de contact
physique du fût. Les décisions explicites de l’ADR 020 restent disponibles après
confirmation et fermeture de la sélection.

## Vérifications et limites

Tests JVM : géométrie, petits carrés/rectangles, M, profils classique/poulies,
loupe et point commun, séquences de gestes/annulations/multitouch, contexte obsolète,
capacité, navigation, sauvegarde différée, double confirmation, retry exact et
repositionnement motivé. Tous les tests antérieurs restent conservés, dont SQLite
natif sur disque. Deux prévisualisations Compose (petite dalle/paysage) sont fournies.

Le Pixel est indisponible ; aucun test ADB de cette PR n’est présumé. Aucun
émulateur/AVD préconfiguré n’a été trouvé. Les previews sont compilées mais ne sont
pas rendues par cet environnement. Le dispatch Android réel, la rotation physique,
le conflit scroll/visée et l’utilité de la loupe restent à vérifier selon le
[protocole PR 22](../validation-visee-precise-pr22.md), avant toute fusion.
Aucun gain de précision tactile physique n’est revendiqué par les tests JVM.

## Validation automatisée de la branche

`./scripts/dev-check.sh --rerun-tasks --console=plain` réussi : modèle 86,
scoring 193, export 10, SQLite natif disque 96, Android JVM debug 154 et release
154, soit 693 exécutions sans échec/erreur/ignoré. Les 32 nouveaux tests sont
13 tests de géométrie/gestes et 19 du contrôleur/présentation ; les tests antérieurs
restent conservés. Assemblage app/database et lint réussis, zéro erreur et cinq
avertissements de dépendances préexistants. Liens documentaires et syntaxe shell
du protocole vérifiés. Ces résultats ne valident pas le dispatch tactile Android
ni une précision ergonomique humaine. La CI doit être vérifiée sur le commit publié.
