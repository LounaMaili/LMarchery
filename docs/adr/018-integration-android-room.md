# ADR 018 — Premier parcours Android Compose et Room

## Portée

Remplacer l'écran en mémoire par une liste de séances, un formulaire court et
une saisie persistée à trois flèches. Les ADR 008 à 017 restent applicables :
aucun calcul UI concurrent du moteur, profil réinterprété, preuve reconstruite
librement ou écrasement automatique selon une date.

## Architecture et cycle de vie

`LMarcheryApplication` possède un `SessionRepository` créé paresseusement une
seule fois par processus. `RoomSessionRepository` ouvre le magasin avec le
**contexte applicatif** et la fabrique Android existante, dans le dossier privé
`getDatabasePath("sessions.db")`. La construction Room reste paresseuse ; les
lectures, reconstructions et écritures passent par `Dispatchers.IO`. Aucun accès
SQL n'a lieu depuis un composable.

Le magasin reste ouvert pendant la vie du processus. Il n'est pas fermé lors
d'une rotation ni dans `Activity.onDestroy`/`ViewModel.onCleared`. Android libère
ses ressources à l'arrêt du processus ; `Application.onTerminate` n'est pas un
mécanisme de fermeture en production. Aucune nouvelle instance n'est créée lors
d'une recomposition ou d'une recréation d'Activity.

`MainActivity` obtient `SessionViewModel` avec une fabrique simple et
`ViewModelProvider`. Le ViewModel conserve `SessionController`, dont les opérations
asynchrones utilisent `viewModelScope`. Le contrôleur reçoit le repository, le
scope, une `Clock`, un fournisseur explicite de `ZoneId` et un générateur d'UUID,
ce qui permet ses tests JVM sans dépendre du dispatcher Main Android.
Ses points d'entrée sont appelés sur le thread UI ; une seule opération est en
cours, les appels supplémentaires sont refusés et ne sont pas mis en file.

Compose observe un `StateFlow` via `collectAsStateWithLifecycle`. Les dépendances
Lifecycle 2.8.3 et coroutines Android 1.7.3 sont déclarées explicitement aux versions
déjà résolues avant cette intégration. `app` dépend maintenant de `core:database` ;
aucun framework DI, navigation supplémentaire ou changement de toolchain.

## Découverte et lecture

`RoomSessionStore.discover()` retourne `SessionDiscoveryResult.Found(uuids)` ou
`TechnicalFailure`. Les UUID sont triés en ordre lexical SQLite déterministe.
La requête lecteur prend l'union des racines `sessions` et des identités de type
`SESSION` : une identité orpheline ne doit pas rendre une séance corrompue invisible.
Aucune donnée n'est écrite. La découverte seule **ne valide pas** une séance.

Le contrôleur appelle `read(uuid)` pour **chaque** UUID et conserve une entrée
par résultat. Une séance n'est présentée comme valide que si la reconstruction
contextualisée réussit. Les entrées illisibles, absentes entre découverte/lecture
ou en erreur technique restent visibles avec diagnostic. Une erreur globale de
découverte est distincte d'une liste vide. L'ouverture relit la séance et son profil
complet avant de constituer l'état attendu. La liste n'est pas une vue atomique de
toutes les séances : chaque lecture est cohérente dans sa propre transaction.

Les statuts sont affichés explicitement. Aucun SharedPreferences, dernier UUID
obligatoire, `SavedStateHandle` ou `rememberSaveable` ne remplace la découverte.
Les schémas Room/archive/scoring ne changent pas ; les API de création et de
révision conservent leurs garanties.

## Création et commandes du premier parcours

Le formulaire choisit explicitement un des quatre profils indoor WA de l'ADR 013 :
classique/poulies, 40 cm/18 m ou 60 cm/25 m. Configuration fixe : trois flèches,
`TRAINING`, indoor, distance par défaut du profil, aucun matériel obligatoire.
Un titre vide devient `Séance salle — <division, diamètre, distance>`.

Le contrôleur utilise `Clock.systemUTC()` par défaut pour les événements. Il
choisit explicitement le fuseau du téléphone fourni par `ZoneId.systemDefault()`
**au moment de la création** et appelle `SessionDateTime.fromInstant`. Le triplet
civil et l'offset effectif sont ensuite conservés, sans conversion lors d'une
reprise. L'horloge des tests et leur fuseau de création sont injectables. Les
horodatages impossibles restent des erreurs, sans incrément ni réparation.

Chaque entité reçoit un UUID v4. La création construit une séance `DRAFT`, la
démarre par `start`, ajoute sa première volée `EDITING`, capture le profil avec
son édition du `BuiltInTargetRegistry`, puis appelle `reconstructArchive` sans
référentiel pour obtenir le `DomainArchive`. `create` doit confirmer avant
l'affichage de la séance comme enregistrée.

Pour un appui, les conversions de `TargetGeometry` restent inchangées et le
blason dessine les zones réelles du profil : le 10 poulies est à 0.05, sans faux
trait de score à 0.10 ni X artificiel. `scoreArrowImpact` produit l'impact ;
`End.withImpact` puis `Session.withEnd` propagent sa révision. La zone extérieure
au cercle dans les coins du Canvas permet M. La saisie s'arrête à trois impacts.

`End.validate` est proposé seulement pour la volée affichée, `EDITING`, avec trois
impacts. Une nouvelle volée n'est créée qu'après validation de la précédente.
`Session.complete` est bloqué si une volée `EDITING` contient des impacts : elle
doit d'abord être complétée et validée. Une volée éditable **vide** n'empêche pas
la clôture ; elle reste enregistrée, sans suppression, et ne compte pas dans le
total validé. Le domaine n'est pas modifié pour ces restrictions de présentation.

Les séances terminées sont consultables en lecture seule, y compris leurs
blasons et scores. D'autres configurations valides existantes restent consultables
sans conversion ; la saisie est limitée aux quatre profils, trois flèches,
`TRAINING`, au plus une volée éditable et des index d'impacts 1..n.
Le total provisoire de la volée et celui des volées validées sont séparés et
utilisent `calculateEndScore` / `summarizeSessionScore`.

## Sauvegarde, échecs et conflits

Le contrôleur conserve le dernier `DomainArchive` **confirmé** comme `expected`.
Chaque commande prépare un `PendingSave` contenant description, `expected`,
`proposed` et volée à afficher. Aucun impact, total ou changement de statut n'est
présenté comme durable avant acquittement du magasin. Les commandes et le retour
sont bloqués pendant l'écriture, y compris par le bouton système Retour.

- `Created` / `Updated` : le confirmé devient exactement `proposed` ;
- `AlreadyExists` / `Unchanged` : même acquittement, avec message d'idempotence ;
- `NotFound` : séance introuvable, aucune recréation automatique ;
- `Conflict` : l'état attendu est conservé, aucune relance avec une nouvelle base ;
- `NeedsIntervention` : diagnostic visible, sans réparation ;
- `TechnicalFailure` : action conservée, nouvelle tentative explicite possible.

Une tentative répétée réutilise **les mêmes** archives, UUID et horodatages. En
cas de résultat incertain après commit, une création peut être acquittée comme
`AlreadyExists` ; une révision peut rencontrer un conflit et nécessiter une
relecture explicite, conformément à l'ADR 017. Il n'y a aucune fusion implicite.

« Consulter la version enregistrée » relit sans abandonner la proposition et
montre les volées/scores stockés. « Abandonner l'action et recharger » avertit
explicitement de la perte de la proposition locale avant relecture. Le confirmé
n'est remplacé et la proposition n'est effacée qu'après réussite de cette lecture.
Revenir à la liste avec une action non confirmée exige aussi confirmation.
L'annulation de coroutine est propagée, et n'est jamais annoncée comme succès.

## Lifecycle et limites

La rotation conserve ViewModel, proposition en attente et opération en cours.
Le passage en arrière-plan suspend l'observation Compose, pas une sauvegarde
associée au ViewModel. Après arrêt complet/recréation du processus, l'accueil
redécouvre les données SQL et permet de rouvrir exactement la volée partielle.
Une opération en mémoire non confirmée n'est pas une sauvegarde ; sa proposition
n'est pas journalisée sur disque dans cette PR. Après arrêt pendant une écriture,
seule la relecture permet de constater ce qui a réellement été committé.

La base reste locale et le manifeste n'ajoute ni INTERNET ni CAMERA. L'export
utilisateur n'existe pas encore ; désinstallation/effacement des données peut
les perdre. Pas de suppression, reset persistant, gestes de déplacement,
correction de score, matériel, presets, import JSON ou synchronisation.
`ScoringDemoState` reste uniquement comme modèle de prototype couvert par ses
tests historiques, sans chemin depuis l'application lancée.

## Vérifications

Les tests du contrôleur emploient un repository simulé et une Clock fixe. Ils
vérifient acquittement, profils, M/10, numérotation, deux volées, totaux, clôture,
reprise par un nouveau contrôleur, doubles appuis, erreurs, conflits, provenance
temporelle, UUID stables et absence d'écriture lors des lectures. Les tests SQLite
sur disque vérifient la découverte déterministe, les entrées corrompues/orphelines,
la lecture seule sous triggers et la fermeture/réouverture. Les suites historiques
restent conservées.

Les tests JVM, la compilation et lint Android ne prouvent ni les gestes sur écran
ni le comportement d'un vrai appareil. Le [protocole téléphone](../tests-terrain-v1.md#protocole-android-persistant-pr-16)
reste à exécuter et à renseigner séparément ; aucun résultat physique n'est présumé.

*15 octobre 2026*
