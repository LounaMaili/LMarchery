# ADR 046 — Sélection tactile explicite des impacts

- Date : 11 novembre 2026.
- Base : main `3f85eb442d082993b27d64f9f20aa3087d48a37d`.
- Statut : implémenté dans une branche produit distincte ; essais tactiles réels reportés.

## Besoin et périmètre

Le ticket 2.6 du backlog ne permettait la sélection que par boutons numérotés.
Un appui en saisie rapide ajoute déjà une flèche, notamment au même endroit
qu'une autre : le transformer implicitement en sélection empêcherait certains
groupements. Un mode **Sélectionner sur le blason** rend cette intention explicite.
Il est disponible dans les deux éditeurs, même lorsque la volée est pleine.

Les [ADR 019](019-selection-repositionnement-android.md),
[020](020-decisions-score-android.md), [024](024-visee-tactile-precise-candidat.md)
et [043](043-editeur-android-seances-configurables.md) restent les contrats de
correction et de confirmation. Seul leur point d'entrée de sélection est étendu.
Aucun score, décision, trace, UUID ou horodatage métier n'est créé par une sélection.
Aucune règle sportive, réouverture, suppression, migration ou sauvegarde nouvelle.

## Recherche et ambiguïté

`findImpactCandidates(impacts, point, toleranceNormalized, viewport)` est une fonction pure
de présentation, sans Android ni appel au moteur de score. Les calculs de
distance convertissent les coordonnées en Double avant la soustraction.
La tolérance doit être finie et non négative ; zéro signifie un point exact,
sans epsilon. Les identités dupliquées sont rejetées. Le résultat immuable
contient tous les UUID admissibles, triés par index puis UUID, pas par score
ou par une préférence pour le plus proche.

Le Canvas convertit un rayon ergonomique de **24 dp** en distance normalisée
avec son rayon effectif. Cette aire de sélection n'est ni une taille physique
de tube ni la tolérance de suggestion de cordon 0,015. Elle ne change aucune
coordonnée stockée, aucun score et aucun indicateur X/M.

`ImpactPickViewport` reçoit les demi-largeur/hauteur normalisées du Canvas réel,
y compris rectangulaire. Les centres hors de cette surface ne sont pas des
candidats, même si l'aire tactile atteint leur position ; la liste numérotée
reste disponible. Ce n'est pas un filtrage au cercle réglementaire : les M dans
les coins visibles restent sélectionnables et `(1.1, 0)` reste un impact métier
valide. Une régression échoue avant correction et vérifie désormais ce refus.

- Aucun candidat : rester en sélection et l'indiquer ; ne jamais ajouter une flèche.
- Un seul : ouvrir l'`ImpactEdit` existant pour cet UUID, sans décision manuelle.
- Plusieurs : demander un choix numéroté, même si un candidat est plus proche.

Le panneau affiche au plus vingt choix et signale tout surplus. La liste
numérotée complète reste accessible en annulant le mode ; elle permet aussi de
choisir un impact dont le marqueur est hors de la surface affichée. Le nombre
FIXED n'est jamais utilisé pour allouer des cases ou des candidats fictifs.

## Geste et identité de présentation

`ImpactPickGesture` accepte un seul pointeur, dans les bornes du Canvas, sans
consommation par un autre geste et sans dépasser le touch slop fourni par Android.
Défilement, déplacement, second doigt, sortie ou annulation terminent le geste.
Le choix est transmis au relâchement ; le mouvement ne sélectionne rien.
Le garde multidoigt commun est aussi activé pendant la sélection, y compris
pour un second doigt sur les commandes hors du Canvas ; aucun callback de bouton
ne doit être produit avant le relâchement complet de cette séquence.
Le Canvas conserve le callback et le propriétaire du geste initial, et annule
le handler lorsque le contexte, la taille ou le mode change.

Chaque entrée possède un `ImpactPickContext` : UUID de séance, UUID de volée et
ticket de présentation renouvelé à chaque activation. Un callback annulé,
répété, d'un autre propriétaire ou d'un ancien ticket est ignoré. Le contrôleur
vérifie à nouveau l'appartenance de l'impact ; un choix extérieur aux candidats
ne donne aucun accès à une correction. L'éditeur configurable renouvelle aussi
son époque de présentation.

Le mode est conservé par le contrôleur du ViewModel pendant les recompositions ;
il ne prétend pas survivre à la mort du processus. Il ne constitue ni preuve de
jugement ni historique persistant. Les impacts proviennent exclusivement de la
volée enregistrée ; ni une proposition de nouvelle flèche ni une croix de
repositionnement ne sont des impacts sélectionnables.

## Protections et annulation

L'entrée exige une volée éditable affichée, une séance en cours et aucun
formulaire, candidat, correction ou enregistrement non confirmé concurrent.
Une volée vide, validée ou une séance terminée n'offre pas ce parcours.
Les restrictions propres à l'éditeur historique restent inchangées.

Pendant le mode, ajout, visée, validation, clôture, changement de volée,
métadonnées, matériel et sauvegardes incompatibles restent bloqués. Les verrous
communs et intentions `expected/proposed` sont conservés. Retour ou Annuler
quitte seulement le mode et retrouve le réglage rapide/précis précédent.
Après un choix, les panneaux existants de décision/repositionnement reprennent
leurs gardes et confirmations, y compris le motif d'invalidation d'une décision.

Le profil initial d'une séance configurable ne remplace jamais celui de sa
volée. La sélection retrouve l'objet existant, avec son profil exact, édition,
origine de position, score final et traces ; elle ne recalcule rien.

## Vérification et limites

Les tests JVM couvrent distances inclusives, Float adjacents et extrêmes,
X/M, doublons, immutabilité, gestes annulés, superpositions, propriétaires/tickets,
capacités, lecture seule et conservation des intentions. Les tests de contrôleur
utilisant des doubles restent distincts des tests SQLite natifs.

Trois tests relient les contrôleurs réels à Room sur fichiers : sélection
historique, ambiguïté configurable avec jugement manuel, correction motivée
puis réouverture avec conservation des autres séances et du matériel. Des
triggers refusent le DML sur les douze tables durant les sélections ; archive
V3 et octets JSON restent identiques. Intégrité et clés étrangères sont vérifiées.
Le seul changement Gradle ajoute le fichier Kotlin pur à la liste de sources
Android indépendantes déjà compilées par ce harnais ; aucune dépendance ajoutée.

La [PR 45](https://github.com/LounaMaili/LMarchery/pull/45) prépare séparément
l'identité TEST. Elle n'est ni modifiée ni fusionnée par ce chantier. Aucune APK
contenant cette sélection n'est installée ; compilation et tests JVM ne qualifient
pas le dispatch tactile, le scroll réel, la rotation ou l'ergonomie au doigt.
Voir le [protocole d'essai](../validation-selection-tactile-impacts.md).
