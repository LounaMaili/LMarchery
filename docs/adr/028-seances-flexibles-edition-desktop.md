# ADR 028 — Séances flexibles, révisions explicites et préparation Desktop

**Statut : proposition produit/architecture, non implémentée.** Les décisions ci-dessous n'autorisent aucune migration implicite et ne modifient pas les garanties des PR #22 à #25.

## Intention

LMarchery est d'abord un carnet d'entraînement local-first. La clôture simplifie l'interface et limite les modifications accidentelles ; elle ne prétend pas homologuer un résultat. Une commande explicite « Modifier » permettra ultérieurement de rouvrir *l'interface d'édition* d'une séance terminée, sans effacer son événement de clôture ni transformer silencieusement son statut en IN_PROGRESS.

Les scores, coordonnées et décisions peuvent être corrigés après une séance, mais **par une révision traçable**, jamais par remplacement sans preuve. Le relevé courant est la dernière décision humaine confirmée. Une suggestion issue d'une photo ou d'un futur détecteur n'est pas une décision finale.

Un mode « compétition » plus strict pourra être étudié séparément, sans prétention de certification officielle ni ajout de réseau social/challenges en V1. Par défaut le mode reste « entraînement ».

## Configuration par volée : héritage explicite

Une séance définit une *configuration initiale*. Une volée conserve, pour ses propres flèches, un instantané effectif de la configuration utilisée : distance, référence à un profil de blason complet et son édition, nombre de flèches fixe/libre, mode de score si pertinent.

**Règle UX essentielle : ouvrir la volée suivante reprend automatiquement la configuration de la précédente.** Aucun formulaire obligatoire entre deux volées. L'archer peut activer « Modifier la configuration de la prochaine volée » pour changer volontairement un ou plusieurs paramètres ; les suivants héritent de cette nouvelle configuration jusqu'au prochain changement explicite.

Exemples : entraînement 50 m / blason 40 cm / six flèches ; séance passant de 30 m à 70 m et changeant de diamètre de blason ; parcours campagne/nature à paramètres variables. Les presets officiels/populaires restent proposés, avec identification exacte de l'édition des règles, et les combinaisons libres sont étiquetées comme entraînements personnalisés plutôt que présentées comme réglementaires.

Ne pas modifier rétroactivement les paramètres d'une volée existante lors du changement de la suivante. Distinguer la correction exceptionnelle de métadonnées erronées d'une reconfiguration sportive. Une volée avec impacts utilise **sa propre référence de profil** pour recalculer et vérifier les décisions, jamais un profil de séance présumé unique.

## Calculs et statistiques

Préserver calcul, coordonnées normalisées et historique par volée. Les agrégats multi-distances/multi-profils doivent annoncer leur dénominateur et leur homogénéité. Ne pas afficher un pourcentage ou un maximum commun trompeur pour des blasons ou barèmes différents. Prévoir regroupement par configuration, groupements/dispersion, comparaison des volées et provenance des corrections.

## Édition post-clôture et journal

- Consultation par défaut, mode édition explicite et sortie claire de ce mode ; commandes dangereuses masquées autrement.
- Titre, notes de séance et de volée, lieu, arc et jeu de flèches : édition possible après clôture, sous contrôle optimiste et avec sauvegarde acquittée. Le contenu des fiches matériel reste distinct de l'association utilisée dans une séance.
- Scores, impacts, jugements et positions : révisions explicites avec ancien/nouveau contenu, instant, provenance, motif quand nécessaire, aucun effacement de la décision précédente. Conserver la capacité à invalider un jugement après repositionnement.
- Traces facultatives d'événements de réglage (viseur, berger, repose-flèche, etc.), datées et éventuellement attachées à une volée. Une note libre ne devient pas un diagnostic automatique ; les paramètres de matériel historiques ne sont pas réécrits quand la fiche actuelle change.
- Corriger une date, un type de blason ou une distance déjà utilisée exige un parcours distinct et des recalculs/justifications explicites ; jamais de mutation silencieuse de la définition publiée du profil.

Le verrou actuel de `SessionStatus.COMPLETED`, `EndStatus.VALIDATED`, `validateSessionUpdate` et des contrôleurs **reste en vigueur tant qu'une nouvelle transition validée et testée n'est pas livrée**. Ne pas contourner ces contrôles en reconstruisant artificiellement un ancien agrégat.

## Migration et archives

Le modèle actuel stocke `targetType` et `distanceM` sur la séance et impose le même profil à ses impacts. Ajouter une configuration propre à la volée exige une évolution de domaine, Room, scoring, reconstruction et contrat d'archive. Ne pas modifier JSON V1/V2 ni les empreintes historiques en place. Spécifier un nouveau contrat versionné (et la migration SQL correspondante) dans une PR isolée ; les anciens enregistrements doivent se reconstruire à l'identique en héritant explicitement de leur configuration de séance historique.

Tester sauvegarde/restauration et export/import interversions, profil personnalisé, volées mixtes, conflits d'édition, calcul par volée, changement explicite vs héritage par défaut, fermeture/reprise et intégrité des anciennes traces. Préserver le Pixel Room V1 durant les derniers essais #22.

## Extension photographique et Desktop

Les photos de blason et futures propositions automatiques doivent être associées à une volée et à une configuration effective. Le modèle doit distinguer photo brute, calibration/version, proposition de détection, correction humaine et score confirmé. Conserver les photographies comme fichiers séparés, avec références et vérification d'intégrité ; ne pas les intégrer en base64 au JSON V1/V2.

Un dépôt [LMarchery-Desktop](https://github.com/LounaMaili/LMarchery-Desktop) distinct exploitera d'abord des archives locales pour afficher et analyser les séances. Plus tard, synchronisation facultative et authentifiée sur réseau local avec file d'attente, appairage et reprise après coupure. Toute saisie Android persiste localement **avant** transmission ; aucune dépendance serveur sur le parcours de score.

Le partage des modules Kotlin `core:model`, `core:scoring` et `core:export` sera effectué par dépendances explicitement versionnées/publications reproductibles ou intégration contrôlée, sans duplication/fork silencieux du moteur et sans couplage direct à Room/Compose Android.

## Ordre de mise en œuvre proposé

1. Terminer la validation de #22 ; maintenir séparées les PR #23–#25.
2. Intégrer SAF Android V2 sous couverture de tests, sans changer le contrat de sauvegarde.
3. Spécifier précisément la migration de configuration par volée + contrat d'archive successeur avant toute implémentation.
4. Construire les formulaires de création/presets et le mode édition documenté, avec révisions historiques.
5. Initialiser Desktop par lecture hors ligne des archives V1/V2, vues blason/volées et statistiques ; ensuite protocole live, photos et traitement d'image.

Ce document établit le cap, pas une promesse que ces capacités sont déjà utilisables sur le Pixel.

*18 octobre 2026*
