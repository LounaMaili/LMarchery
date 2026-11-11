# Essai futur — sélection tactile des impacts

Ce protocole ne vaut pas autorisation d'installation. Une nouvelle instruction
du propriétaire est indispensable ; ne jamais mettre à jour `com.lmarchery`
ni consulter sa base réelle. Utiliser exclusivement des données synthétiques
dans une installation TEST dont l'identité, le certificat, l'UID et le stockage
indépendant ont été vérifiés.

La [PR 45](https://github.com/LounaMaili/LMarchery/pull/45) seule ne contient pas
la sélection ajoutée par l'[ADR 046](adr/046-selection-tactile-explicite-impacts.md).
Avant un essai physique, construire et vérifier une APK TEST contenant les deux
ensembles, après revue de leurs commits. Aucune intégration ou installation
automatique n'est impliquée par ce document.

## Matrice à renseigner PASS / FAIL / NON TESTÉ

1. Dans une séance historique synthétique éditable, enregistrer trois impacts.
   Activer « Sélectionner sur le blason » malgré la capacité pleine. Toucher un
   marqueur isolé : vérifier numéro sélectionné, même score et aucun quatrième impact.
2. Toucher une zone vide en mode sélection : aucun ajout/déplacement, message
   explicite. Annuler : retrouver le mode rapide ou précis antérieur.
3. Créer deux impacts superposés par la saisie habituelle, puis les toucher en
   sélection : demander les numéros, choisir le second et vérifier la sélection.
   Aucun choix automatique du plus proche ni modification de score.
4. Garder la liste numérotée comme alternative, notamment pour un impact hors
   surface visible ou un groupement de plus de vingt impacts.
5. Essayer défilement, mouvement dépassant le touch slop, second doigt et sortie
   de surface : pas de sélection fantôme, aucun enregistrement. Ne pas confondre
   gestes automatisés et essai ergonomique humain.
6. Faire Retour pendant le mode : quitter seulement la sélection sur le blason.
   Recomposition/rotation : aucun choix automatique, geste actif annulé. Arrêt
   de processus : aucune prétention de persistance du mode, données confirmées conservées.
7. Après sélection, juger une limite puis repositionner avec le motif exigé :
   UUID/création conservés, nouvelle géométrie, ancienne décision dans les traces.
   Annuler une correction ne doit rien enregistrer.
8. Dans une séance configurable mixte classique/poulies, recommencer sur chaque
   volée : profil effectif, édition et scores inchangés, choix sans réinterprétation
   selon la configuration initiale. Essayer FIXED 3/6/personnalisé et FREE.
9. Une visée/correction déjà commencée ou une sauvegarde non confirmée empêche
   d'entrer dans ce mode. Validation, métadonnées, navigation et sauvegardes
   incompatibles ne peuvent pas l'interrompre silencieusement.
10. Une volée validée et une séance terminée restent non modifiables.

Ne lire/exporter que la base synthétique de TEST. Comparer les captures et
archives avant/après sélection ; le contenu doit être identique. Pour une vraie
correction confirmée, comparer les deltas autorisés et les traces. Ne jamais
effacer une base pour rendre un essai rejouable, ni installer un ancien APK
Room 1/2 sur une base déjà Room 3.

Les tests Kotlin/JVM et SQLite sur disque n'attestent pas la précision au doigt,
la lisibilité en extérieur, les fournisseurs SAF réels ou la rotation physique.
