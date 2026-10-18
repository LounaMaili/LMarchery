# Conception préparatoire — configurations par volée et révisions

Statut : proposition technique à valider avant implémentation. Aucun changement de
modèle, migration, JSON ou parcours de score n’est livré par ce document.

Le cap vient de l’[ADR 028 proposé dans #26](https://github.com/LounaMaili/LMarchery/blob/ae96dbda3973445ff850a77ef4e4cb3de9066b04/docs/adr/028-seances-flexibles-edition-desktop.md).
Cette conception concerne Android local/offline. La PR #26 reste inchangée.

## Baselines et versions

`main` reste à `e4c7e56817baa3f5a5a366bdf5895b3d38f6b798`. L’évolution est préparée
pour le socle Room 2 / JSON V1 et V2 des PR #23–#25, et les parcours Android
[#27](https://github.com/LounaMaili/LMarchery/pull/27),
[#28](https://github.com/LounaMaili/LMarchery/pull/28),
[#29](https://github.com/LounaMaili/LMarchery/pull/29), tous non fusionnés.
Les versions Room, d’archive et de scoring sont trois contrats indépendants.
Les numéros « Room 3 » et « JSON 3 » ci-dessous sont des propositions pour la
prochaine évolution, pas des schémas publiés ni une obligation de les synchroniser.
Le contrat de calcul reste 1 tant que les règles du moteur restent identiques.

Aujourd’hui la configuration est portée par `Session` : profil, distance,
nombre/mode de flèches, indoor et mode de score. `Session` impose le même profil
à tous les impacts. Une volée validée conserve ses impacts ; une séance terminée
conserve ses enfants et sa clôture. Les copies ne sont pas une autorisation de
contourner ces invariants. Les associations de matériel de #29 sont seulement des
métadonnées propres de séance, pas une édition post-clôture des scores.

## Configuration effective immuable

Introduire une valeur `EndConfiguration`, appartenant à la volée :

| Valeur | Invariant proposé |
|---|---|
| Profil complet et édition | Référence stable à une définition exacte ; chaque impact et décision emploie ce profil |
| Distance en mètres | Entier strictement positif, indépendant du diamètre et de `isIndoor` |
| Mode de nombre de flèches | FIXED avec nombre positif ou FREE avec nombre nul |
| Nombre fixe | Presets 3 et 6 ; conservation des autres nombres valides reçus, sans conversion vers 3 |
| Indoor et mode de score | Valeurs explicites héritées, sans déduction du seul profil |
| Origine de capture | Nouvelle configuration confirmée ou dérivation explicite des anciennes valeurs de séance |

Cette valeur n’est pas une nouvelle entité : sa clé de stockage peut être l’UUID
de sa volée propriétaire. Ne pas inventer un UUID d’événement pour chaque héritage.
Si le futur modèle en fait une entité autonome, il devra adopter un UUID v4 et
le registre global, dans un ADR distinct.

Une configuration publiée est figée pour sa volée, même vide. Les modifications
sont préparées **avant** création de la prochaine volée. Ne pas supprimer puis
recréer une volée pour contourner cette règle. Corriger une configuration déjà
utilisée demande une opération distincte, un motif et un contrat de révision ;
ce n’est pas la commande sportive « prochaine volée ».

Le profil est un instantané réglementaire complet, identifié/versionné. Une
nouvelle édition reçoit une nouvelle identité ; aucun profil n’est réinterprété
par diamètre, distance ou division présumée. Les profils complets actuels restent
les seuls profils implémentés ; aucune promesse de multispots ou blasons réduits.

## Création, presets et héritage

- La séance possède une configuration initiale. Les quatre profils officiels
  conservent leurs identités ; les raccourcis 3/6/libre préparent des valeurs.
- Une combinaison personnalisée est étiquetée entraînement personnalisé. Un
  preset ne certifie pas un règlement ; aucune définition de cible publiée n’est
  modifiée pour satisfaire un choix libre de distance.
- « Nouvelle volée » copie par défaut la configuration effective de la précédente,
  ou l’initiale s’il n’existe aucune volée. Aucun formulaire intermédiaire obligatoire.
- « Configurer la prochaine volée » prépare un candidat temporaire, sans écriture.
  Confirmer crée une volée et sa configuration dans la même transaction.
  Annuler ne crée aucune volée. La nouvelle valeur devient l’héritage suivant.
- Changer de preset ne modifie jamais les volées existantes. Les propositions
  tactiles et décisions en cours doivent d’abord être confirmées ou annulées.
- Sélections, erreurs et expected/proposed restent au ViewModel pendant rotation ;
  seul un acquittement rend la nouvelle configuration durable.

## Domaine et scoring à faire évoluer ensemble

Le contexte cible d’un impact devra être égal à celui de **sa volée**, plus à un
profil global présumé unique. `findBoundaryHints`, jugement de limite,
reconstruction de décision et repositionnement utilisent cette définition exacte.
La tolérance tactile demeure ergonomique ; elle n’attribue jamais un score final.

Préserver `InputMode`, UUID, géométrie calculée, décision finale et traces de
repositionnement. Les anciennes décisions manuelles ne peuvent pas être rejouées
sous un autre profil ; aucune transformation des coordonnées ni nouveau calcul
historique silencieux lors de migration. Les états publiés V1/V2 doivent d’abord
être reconstruits selon leur contrat, puis convertis explicitement.

## Statistiques mixtes : dénominateurs visibles

Séparer volées validées, volée provisoire et éventuelles corrections ultérieures.
Afficher les regroupements par profil exact/édition, distance, mode de score et,
lorsque nécessaire, nombre/mode de flèches. Un total hétérogène est explicitement
signalé ; ne pas présenter une comparaison réglementaire entre configurations.

Pour les volées enregistrées, proposer :

- FIXED : maximum de la volée = nombre prévu de ses flèches × maximum de son profil.
- FREE : maximum = impacts présents × maximum de ce profil.
- Maximum enregistré de séance = somme de ces maxima, avec périmètre affiché.
- Pourcentage = somme des points / somme des maxima, jamais moyenne non pondérée
  de pourcentages. Si le maximum vaut zéro, afficher « non défini », pas NaN/infini.
- Les futures volées planifiées sans configuration connue ne donnent pas un faux
  maximum final : afficher un maximum enregistré/provisoire ou une projection
  explicitement hypothétique, à arbitrer avant UI.

Conserver les compteurs M/X finaux cohérents. X reste une distinction du maximum,
pas un point ; indoor officiel utilise les 10 et les 9 et n’active pas X. Ne pas
mélanger des densités/groupements de diamètres/distances différents sans unité et
normalisation explicites. Les statistiques existantes homogènes doivent rester
numériquement identiques sur leurs fixtures.

## Notes et révisions après clôture

Conserver la consultation par défaut et une entrée d’édition explicite.

| Changement | Contrat futur nécessaire |
|---|---|
| Titre/notes de séance/lieu/matériel | Révision de métadonnées limitée, expected complet et acquittement ; la méthode de #29 reste limitée au matériel |
| Notes de volée après clôture | Transition spécifique : la séance terminée interdit actuellement toute modification d’enfant ; pas de `copy` reconstruit pour la contourner |
| Score/jugement/position après validation ou clôture | Révision traçable avec avant/après, instant, provenance et motif selon la commande ; jamais réouverture implicite |
| Date/distance/profil déjà utilisé | Parcours exceptionnel distinct, conséquences et recalculs explicités ; pas une simple modification de preset |

Avant de rendre les scores clos modifiables, définir une preuve append-only
conservant la base validée, chaque décision remplacée, les points avant/après et
les invalidations. Une piste conservatrice est une base close immuable plus une
suite ordonnée d’amendements, dont la projection produit le relevé courant.
Le stockage doit valider tout le préfixe contre l’état attendu ; une date récente
ne prouve pas la filiation. Un champ motif libre ou `updatedAt` seul ne suffit pas.

La représentation exacte (valeurs appartenant à l’impact, indexées en séquence,
ou entités UUID v4) nécessite son ADR avant implémentation. Ne pas transformer
les seules traces de repositionnement actuelles en journal général fictif : les
anciennes décisions successives à position constante ne sont pas récupérables.
Les anciennes valeurs du catalogue matériel ne sont pas non plus des snapshots
des réglages au moment du tir.

Tous les événements restent des `Instant`, Clock injectable UTC, nanosecondes
préservées et chronologie monotone. La date civile de séance, fuseau IANA et
offset enregistrés restent distincts des instants de révision. Clôture et validation
originales ne sont pas déplacées pour faire tenir une correction tardive.

## Migration Room : proposition 2→3 non destructive

1. Figer les schémas exportés 1 et 2 et leurs fixtures indépendantes. Ne pas créer
   une base future puis modifier artificiellement son `user_version` pour tester.
2. Introduire une table de configuration possédée par la volée, clé `endUuid`, FK
   vers la volée et le profil exact, champs complets distance/nombre/modes/origine.
   Conserver les dix tables existantes et toutes leurs lignes, colonnes, clés et
   références. Garder `Session.bowUuid`/`arrowSetUuid` sans nouvelle FK.
3. Pour **chaque** volée existante, copier exactement les valeurs stockées de sa
   séance et son profil/édition. Ne pas déduire distance/nombre depuis le diamètre,
   un preset actuel ou le nombre d’impacts. Conserver la configuration initiale
   historique sur la séance. Marquer cette dérivation, sans inventer une ancienne
   décision humaine ni un nouvel instant historique de capture.
4. Vérifier cardinalités, profils, propriétaires, registre et intégrité avant de
   rendre la migration acquise. Incohérence : échec/rollback, aucune réparation.
5. Adapter lecteurs, writers, validation et collecte globale dans les fabriques
   JVM **et Android**. La colonne actuelle `archiveSchemaVersion` des séances est
   un contrat de représentation à auditer ; ne pas la remplacer mécaniquement
   par le numéro Room ou celui de l’enveloppe export. Définir le mapping des
   anciennes/nouvelles lignes explicitement et tester leurs reconstructions.
6. Sauvegarde et import prennent le même instantané/verrou SQL que séances,
   configurations et matériel. Toute erreur tardive annule l’archive entière.
   Aucun REPLACE, effacement, downgrade destructif ou réinterprétation de profil.

Le journal de révisions post-clôture devra avoir son propre contrat de stockage
et de migration ; ne pas supposer qu’une table de configuration le fournit.
S’il est livré après publication du schéma ci-dessus, il demande une nouvelle
version, plutôt que de réécrire un schéma déjà diffusé.

## Archive successeur : proposition JSON 3

Définir des DTO non fiables et un domaine validé distincts, avec configuration
initiale et configuration effective obligatoire pour chaque volée. Inclure toutes
les définitions exactes de profils référencés et non référencés, séances,
matériel et historique effectivement disponible. Ne pas publier puis enrichir
silencieusement JSON 3 : un ajout incompatible ultérieur exige une autre version.

Lire V1/V2 **sans changer leurs règles ni leurs octets d’export figés**. Leur
conversion crée explicitement la configuration héritée de séance pour chaque
volée, sans fiche matériel inventée, `null` réparé ou journal historique inventé.
Préserver les références opaques, UUID, Float, dates civiles, UTC et nanosecondes.
Une archive nouvelle ne doit jamais être rétrogradée vers V1/V2 en perdant les
configurations ou révisions. Un ancien export doit refuser les données qu’il ne
peut représenter fidèlement, sauf futur convertisseur explicite et prouvé.

Conserver les budgets stricts globaux, diagnostics typés et chemins précis.
Tout aperçu/import compare l’ensemble, configurations et éventuelles révisions
incluses, sous transaction unique, avec collisions globales et idempotence sans
écriture. Les configurations appartenant à une volée doivent être vérifiées même
si elles ne possèdent pas leur propre UUID global.

## Découpage proposé et tests d’acceptation

| PR future | Livraison minimale et preuves |
|---|---|
| Contrat domaine | Valeur effective, héritage, contexte par volée, transitions et agrégats ; régressions homogènes inchangées, profils classique/poulies/X/M et limites exactes |
| Archive successeur | DTO, reconstruction stricte, lecture V1/V2 et goldens inchangés, aller-retour mixte, manque/configuration contradictoire, UUID/chronologie/limites malveillants ; pas d’UI productrice |
| Room et migration | Vrais fichiers 1→2→3 et 2→3 riches, toutes données et traces comparées, réouverture, conflits entre magasins, rollback tardif/cancellation réelle, collecte/restauration atomiques |
| SAF et confirmation | Version annoncée, quatre catégories et nouvelles valeurs correctement comptées, aucun succès anticipé, flux fermés/relus, candidat exact, rotation logique et erreur fournisseur |
| Création/presets/volées | 3/6/libre, distance indépendante, héritage sans formulaire, changement volontaire, annulation sans écriture, pas de changement rétroactif ; essais humains distincts |
| Révisions post-clôture | ADR/historique/version nécessaires livrés avant le parcours UI ; refus d’effacement/préfixe invalide, ancienne décision conservée, projection et totaux avant/après vérifiés |

Les tests de migration doivent contenir séances en cours et closes, volées
vides/validées, décisions manuelles, plusieurs repositionnements, profils exacts,
matériel et références opaques. Comparer les dix tables originales et les exports
historiques avant/après ; vérifier `foreign_key_check` et `quick_check`.

## Arbitrages restant au propriétaire

Le cap et l’héritage sont définis ; restent à valider avant les PR concernées :
la présentation des maximums planifiés hétérogènes, les nombres fixes autres que
3/6 dans l’UI, la forme du journal d’amendements et ses motifs obligatoires, et le
parcours exceptionnel de correction d’une configuration déjà utilisée. Ces choix
ne sont pas implémentés implicitement dans les PR Android de cette mission.
