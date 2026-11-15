# ADR 047 — Déplacement tactile d’un impact sélectionné

- Date : 15 novembre 2026.
- Statut : candidate locale non commitée ; tests JVM/SQLite et démonstration sur medium_phone Android 16 exécutés ; ergonomie au doigt à qualifier.
- Base : main `ea966ad94c17f20bd2ca4a760d42ffcdddcda56a`.

## Intention explicite et périmètre

Le ticket 2.4 complète la sélection de l’[ADR 046](046-selection-tactile-explicite-impacts.md).
Sélectionner une flèche, notamment par choix numéroté en cas de superposition,
puis choisir **Déplacer sur le blason**. Un appui rapide ordinaire conserve son
comportement d’ajout. Aucun déplacement implicite d’un impact simplement proche.

Le mode utilise le repositionnement des [ADR 019](019-selection-repositionnement-android.md)
et [043](043-editeur-android-seances-configurables.md), sans changer domaine,
scoring, profil, Room, migrations, versions JSON ou dépendance.

## Geste et aperçu

`ImpactDragGesture` est indépendant d’Android. Il reçoit la géométrie du Canvas
intérieur, un seul pointeur et la position du seul marqueur sélectionné. La zone
de prise est de 24 dp, convertie en pixels par Compose ; elle ne représente ni
un diamètre de tube ni une tolérance de score. Un marqueur hors du viewport ne
peut pas démarrer ce geste ; le repositionnement par choix de point reste disponible.

Le point de prise est conservé : la flèche se déplace du delta du doigt, sans
sauter sous son centre. Le déplacement doit dépasser le touch slop Android pour
produire un candidat au relâchement. Un simple appui ou une microvariation ne
produit rien. La conversion reste celle de `TargetGeometry`, avec le minimum
largeur/hauteur comme diamètre. Les coins visibles restent M, sans rabattement
sur le cercle réglementaire.

Le mode consomme le geste dès la prise du marqueur. Un appui ailleurs ne déplace
et n’ajoute rien, et peut servir au défilement du parent. Second doigt, pointeur
différent, événement consommé, sortie du doigt ou du marqueur hors du Canvas
annulent définitivement le geste. Le garde multitouch commun observe aussi les
commandes hors du blason. Changement de contexte, mode, taille, position du Canvas
dans la fenêtre ou destruction annulent le handler et son token. Une rotation
interrompt le geste ; elle ne confirme rien.

Le renderer affiche le marqueur proposé en violet, la position durable en
contour orange atténué et le réticule du candidat. Les autres impacts et les
totaux restent ceux de l’état acquitté. Aucun impact n’est muté dans `saved`.
La loupe du mode précis n’est pas activée pendant ce mode dédié ; le réglage
précis/rapide précédent est conservé et retrouvé après fermeture.

## Propriété et confirmation

`ImpactDragContext` contient séance, volée, impact et ticket de mode. Le début
vérifie ce ticket et la position actuellement affichée. `ActiveAim` est réutilisé
avec une origine typée `IMPACT_DRAG`, distincte de `PRECISE_AIM`. Les tokens sont
monotones dans le contrôleur et les anciens callbacks de visée précise ne peuvent
pas terminer ce déplacement. Une nouvelle activation après annulation renouvelle
le ticket, même pour le même UUID.

Les mouvements modifient uniquement l’aperçu. Le relâchement appelle la proposition
de repositionnement existante ; il n’alloue ni UUID, ni date métier, ni trace et
ne déclenche pas de repository. Annuler un raffinement conserve le dernier
candidat relâché ; fermer la sélection abandonne le candidat sans DML. Après
perte de processus, seul le contenu SQL est redécouvert.

**Confirmer le déplacement** est une commande séparée, dont l’emplacement est
conservé et désactivé après acquittement pour éviter une seconde action au même
point tactile. Le callback conserve le ticket et les coordonnées exactes du
candidat observé, pas seulement son score. Les anciennes confirmations de
repositionnement ne peuvent pas confirmer un candidat de ce mode. Une double
confirmation n’alloue aucune seconde intention.

La confirmation délègue au parcours historique `PendingSave` ou configurable
`ConfiguredWriteRequest.Update(expected, proposed)`. UUID/création et origine de
position sont conservés. Le motif reste obligatoire si une décision manuelle est
invalidée ; l’ancienne décision et les traces sont conservées par le domaine.
Le profil effectif de la volée configurable calcule l’aperçu et le score après
confirmation, jamais le profil initial de séance.

Les statuts, verrous SAF/matériel/navigation, conflits et commits incertains
restent ceux des contrôleurs existants. Aucun retry automatique ; les intentions
soumises conservent leur expected/proposed. Volée validée et séance terminée
restent non modifiables.

## Vérifications et limites

Tests ciblés : prise/delta/slop, rectangles/M, annulations, multitouch, tickets,
callbacks périmés, confirmation unique, motif et traces, profil mixte, statuts et
incertitude. Les doubles de contrôleur sont distincts des tests Room/SQLite natif
sur fichiers avec triggers anti-DML et fermeture/réouverture.

Le [protocole de recette](../validation-deplacement-tactile-impacts.md) distingue
émulateur Android 16 et essais humains au doigt sur Pixel. Les tests logiciels
et gestes ADB ne qualifient pas la précision tactile, les gants, le soleil ou le
comportement réel du clavier et des interruptions système. Aucun GO global V1.
