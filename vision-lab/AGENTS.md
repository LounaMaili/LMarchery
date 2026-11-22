# Instructions du laboratoire Vision

Ces règles s’appliquent à `vision-lab/` dès R9 et à chaque étape suivante. Conserver un rapport `rN.md` et un dossier `rN/`. L’index du [README](README.md) donne la question, le verdict, le statut, la PR, le commit de référence et les liens vers le rapport, le protocole, les mesures et les figures. Pour l’historique, pointer vers les fichiers d’origine et préciser les pièces indisponibles.

## Avant la comparaison

Publier le protocole figé sur la branche du laboratoire avant les réglages ou les essais comparatifs. Y inscrire les partitions et leurs unités indépendantes, les exclusions, les modèles et leurs empreintes, les bras, les paramètres, les graines, le budget, les seuils, les règles de sélection et de décision. Donner le SHA du gel. Documenter toute correction ultérieure, les résultats concernés et la version qui les remplace.

## Dossier à publier avec les résultats

- Rédiger un rapport Markdown autonome qui décrit les données utilisées, les expériences exécutées, les résultats mesurés et leurs limites. Identifier les hypothèses et les expériences proposées. Terminer sur la décision et la prochaine question.
- Publier les configurations, les effectifs et les mesures JSON ou CSV pour chaque bras, graine, partition et provenance. Donner les seuils, dénominateurs, contacts retrouvés, propositions non appariées, faux établis, cas non résolus, photos silencieuses et déclenchements sur témoins vides. Expliquer l’unité de chaque compteur et les cas sans dénominateur.
- Joindre des courbes PNG ou SVG légères avec leurs valeurs numériques lisibles, les variables tracées et les règles d’agrégation. Un lecteur sur GitHub doit pouvoir vérifier les tableaux, les courbes et la décision sans archive locale.
- Documenter les sources, crédits, licences, commandes, versions et empreintes nécessaires à la reproduction. Pour chaque capsule locale, corpus ou checkpoint, publier le nom, le rôle, la taille en octets, le SHA-256 et l’emplacement de conservation.
- Garder les photos privées locales. Pour une planche photographique, vérifier les droits et la confidentialité avant publication. Protéger le futur test jusque dans les liens, légendes, noms de fichiers et registres de sources. Publier uniquement les effectifs et empreintes nécessaires à la preuve de réservation.

## Vérifications et compte rendu

Pousser les résultats validés sur la branche du laboratoire. Conserver la PR en brouillon jusqu’à une instruction de fusion. Au SHA distant, vérifier la présence et le contenu des fichiers de revue, les liens du README et du rapport, et l’accord entre figures, données et décision. Conserver la trace datée des corrections et des mesures remplacées.

Ouvrir la livraison par la décision et le résultat principal, puis donner dans l’ordre le rapport GitHub, le protocole, les mesures, les figures, la PR, le commit publié et les contrôles CI. Épingler les liens de fichiers au SHA complet. Indiquer séparément la révision des preuves, les SHA effectivement validés en local et sur GitHub et les contrôles en attente ou ignorés. Les chemins locaux peuvent suivre les liens GitHub.
