# Recette — déplacement tactile direct des impacts

Candidate issue de main `ea966ad94c17f20bd2ca4a760d42ffcdddcda56a` ;
[contrat ADR 047](adr/047-deplacement-tactile-candidat-impacts.md).

## Préparation isolée

Utiliser LMarchery TEST, avec identité/certificat/UID vérifiés et base synthétique
préservée avant mise à jour. Ne pas remplacer LMarchery normal ni réinitialiser
TEST. Sur medium_phone Android 16, toutes les commandes doivent cibler explicitement
`/usr/bin/adb -s emulator-5554`. Aucun Pixel pour cette campagne.

Créer deux séances indépendantes : TEST DRAG HISTORIQUE et TEST DRAG CONFIGURABLE,
sans modifier les séances antérieures. Vérifier une volée éditable, notamment pleine,
et conserver ses UUID, coordonnées, scores, décisions et traces.

## Parcours dans chaque éditeur

1. Placer des impacts synthétiques, dont deux proches ou superposés. Sélectionner
   explicitement la flèche par son numéro (ou choix ambigu), puis activer Déplacer
   sur le blason. Aucune ligne métier ne doit changer.
2. Glisser son marqueur ; observer l’aperçu violet et la position durable orange.
   Relâcher : candidat visible, mais SQL/totaux/UUID inchangés. Vérifier aussi un
   appui sans glissement et un défilement débutant hors du marqueur.
3. Annuler par la fermeture dédiée : position enregistrée retrouvée, aucun DML.
   Refaire un candidat, puis confirmer une seule fois par Confirmer le déplacement.
   Une seconde activation du même emplacement doit rester sans effet.
4. Vérifier UUID, score géométrique/final, indicateurs X/M, motif éventuel, ancienne
   décision invalidée, trace ajoutée une seule fois et autres impacts inchangés.
5. Recommencer depuis le mode précis : le réglage est conservé, la loupe n’est pas
   confondue avec le déplacement. Après fermeture, le mode précis est retrouvé.
6. Fermer/reprendre TEST et comparer la base : persistance exacte. Une volée validée
   ou une séance terminée ne doit pas permettre d’activer le déplacement.

Les coordonnées d’automatisation doivent être réacquises après tout défilement ou
recomposition depuis une observation fraîche et stable. Arrêter au premier état
inattendu ou acquittement incertain ; aucun geste aveugle ni retry durable.

## Essai humain futur

Sur une installation isolée autorisée, comparer au doigt prise au bord/centre du
marqueur, déplacement lent/rapide, vertical près du scroll, annulation, second
doigt hors du Canvas, sortie, rotation et clavier de motif. Vérifier les profils
classique/poulies et les superpositions. Qualifier confort, visibilité, absence de
saut et distinction aperçu/enregistré. Une démonstration ADB n’est pas cet essai.

Consigner séparément PASS observé, FAIL et NON TESTÉ, les artefacts exacts, les
captures avant/après et les comparaisons SQLite. Aucun GO global V1.
