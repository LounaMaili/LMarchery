# ADR 048 — Annulation logique définitive d’une saisie d’impact

- Date : 15 novembre 2026.
- Statut : candidate locale, sans commit ni publication.
- Base : `main` `ccb372761ce215b7d104cbb3bf67fa394e1e4177`.

## Décision métier approuvée

Le ticket 2.5 corrige une **saisie par erreur**, pas une flèche réellement tirée
hors cible. M reste un impact actif, de valeur zéro, occupant une place.
Une annulation est définitive en V1 produit ; aucune réactivation, suppression
physique, correction après validation ou réouverture de séance n’est ajoutée.
Les [ADR 011](011-decisions-score-explicites.md),
[012](012-repositionnement-explicite-impacts.md),
[037](037-contrat-archive-seances-configurables-v3.md),
[040](040-ecritures-room-v3-configurables.md) et
[047](047-deplacement-tactile-candidat-impacts.md) restent applicables.

## Domaine partagé et preuves

`End` conserve tous ses `impacts` et une liste immuable `cancellations`.
`ImpactCancellation` contient propriétaire `endUuid`, référence `impactUuid`,
`cancelledAt: Instant`, motif `SAISIE_PAR_ERREUR` et commentaire facultatif.
La preuve n’a pas d’UUID autonome. UUID, index, coordonnées Float32, profil,
création/révision de l’impact, scores, décision et repositionnements restent
intégralement identiques ; l’annulation avance les révisions des parents.

`cancelImpact` est une transition explicite sur les agrégats `Session` et
`ConfiguredSession`, limitée à `IN_PROGRESS` / `EDITING`. L’instant ne précède
ni la révision de séance/volée ni celle de l’impact. Une preuve doit appartenir
à un impact présent de sa volée, suivre le début de séance et précéder la
validation/clôture éventuelle. Les listes de preuves sont en ordre chronologique
d’ajout. Deux annulations du même impact, un retrait, une réécriture ou un
déplacement de preuve sont refusés. L’impact annulé est gelé, décisions incluses.
La reconstruction d’un instantané ne confère aucune autorisation de révision.

`End.activeImpacts` centralise la participation. Score, X/M/9/maxima, moyennes,
sélection, Canvas et capacité s’appuient exclusivement sur cette vue ; le lecteur
de stockage et les archives continuent de conserver **tous** les impacts.
La géométrie et la résolution des décisions individuelles ne changent pas.
FIXED conserve sa capacité déclarée ; FREE utilise le nombre d’impacts actifs.
Un maximum nul produit un pourcentage indéfini. Les scores validés et provisoires
restent séparés ; aucune nouvelle politique de remplissage n’est introduite.

Les anciens numéros restent stables. `nextImpactIndex()` utilise le maximum de
tous les impacts, annulés compris, plus un avec contrôle de débordement. Le numéro
abandonné n’est jamais repris. Une volée FIXED 3 peut donc conserver #1, #2 annulé,
#3 et #4 avec trois actifs. L’éditeur historique accepte cette représentation ;
il ne teste plus la contiguïté des numéros ni le nombre physique de lignes comme
capacité. Une révision d’une volée portant des annulations interdit aussi de
renuméroter ses autres impacts ou d’insérer un nouvel index dans son historique.
Les contrats antérieurs sans annulation conservent leurs comportements.

## Room 4, migration additive et révisions

Room passe de 3 à **4**, avec une treizième table `impact_cancellations`.
Clé primaire : UUID de l’impact ; FK `RESTRICT` vers impacts et volées ; position
unique par volée pour l’ordre des preuves ; instant en seconde/nanoseconde,
motif et commentaire nullable. Aucun nouvel enregistrement d’identité n’est
nécessaire : l’identité originale reste dans `entity_identities` et `impacts`.

`MIGRATION_3_4` valide toute la source V3, ajoute la table et son index, puis
reconstruit V4 et compare à la conversion sans annulations. Les douze tables
existantes ne sont ni recréées ni mises à jour. La migration et sa validation
restent dans la transaction Room, avec contrôle des FK. Les fabriques Android/JVM
enregistrent 1→2→3→4 ; les schémas publiés 1/2/3 gardent leurs octets.

Les colonnes historiques `sessions.archiveSchemaVersion` 1/3 continuent de
décrire leur **payload homogène/configurable**, pas la version de la sauvegarde
complète. Les marqueurs `LEGACY_HOMOGENEOUS` et `CONFIGURED` restent distincts.
Le contrat historique autorise déjà des révisions globales homogènes : ce chemin
synchronise ses seules projections redondantes `LEGACY_DERIVED` avec les colonnes
autoritaires, via `syncLegacyConfigurations`, après validation de toute la source.
Cela ne répare aucun état incohérent et n’autorise aucune révision de capture
`CONFIGURED`, même issue d’un import historique. Une modification de métadonnées
seule ne provoque toujours aucun UPDATE des captures.
Seuls le nouveau lecteur et l’archive V4 peuvent inclure les preuves supplémentaires.
Une annulation ne change ni les captures de configuration ni leurs origines.

Le writer reprend une seule `immediateTransaction`, comparaison intégrale
`expected/proposed`, registre global, contrôles métier, relecture complète et
empreintes SQL hors delta. Une annulation insère sa preuve et révise ses parents ;
elle ne modifie aucune ligne d’impact, de décision ou de repositionnement.
Les preuves existantes sont append-only. Toute erreur après DML sort de la
transaction. Conflit, intervention, échec technique et commit non confirmé restent
distincts. Une réconciliation reconnaît `proposed` intégralement présent sans DML ;
un troisième état reste conflictuel. Aucun retry automatique ni date/UUID régénéré.

L’éditeur historique utilise `SessionContent` pour une révision complète,
distincte de l’archive V1. `DomainArchive` reste utilisable sans annulations ;
son snapshot refuse explicitement la perte de preuve. Le nouveau chemin de
révision historique conserve le marqueur SQL historique, sans convertir la séance
en `CONFIGURED` et sans utiliser une séance homogène pour représenter une mixte.

## Archive 4 et sauvegarde Android

L’[archive JSON V4](../export-format-v4.md) conserve les payloads exacts V3 et
ajoute une liste obligatoire `cancellations`, ordonnée selon séances/volées puis
l’ordre d’ajout de leurs preuves. Les versions Room 4, archive 4 et scoring **1**
sont indépendantes. Les schémas/codec/golden V1/V2/V3 restent publiés et inchangés.
Le lecteur V4 reconstruit d’abord les profils et payloads avec les règles V3,
puis rattache chaque preuve et revalide les agrégats. Une preuve manquante,
dupliquée, étrangère ou antidatée n’est jamais réparée silencieusement.

`readArchiveV4` collecte les treize tables sur une seule transaction lecteur.
L’aperçu est sans DML ; la confirmation d’import revérifie sous verrou contenu,
empreintes et plan, puis insère seulement l’absent et contrôle toute la base.
Un réimport identique n’écrit rien. Une ancienne archive présentant comme actif
un impact connu annulé déclenche un conflit explicite. Ni une annulation reçue
ni un horodatage supérieur n’autorise l’écrasement d’une révision locale.

Comme dans la restauration V3 publiée, une nouvelle racine restaurée est stockée
`CONFIGURED`, avec sa provenance d’origine intacte, y compris `LEGACY_DERIVED`.
Ce marqueur est une représentation de stockage, absente du contrat d’archive.
Une séance historique déjà présente et identique conserve son marqueur
`LEGACY_HOMOGENEOUS` : le réimport n’exécute aucune conversion ni mise à jour.

Les exports V1/V2/V3 refusent une base dont ils perdraient les preuves ; les
garde-fous sont également présents à la capture métier. Aucun filtrage des
annulés en prétendant produire une ancienne sauvegarde complète. Les contrats
historiques restent lisibles ; une absence de matériel V1 reste « non fourni ».
Une base vierge restaurée depuis **seul** un fichier ancien ne peut pas connaître
une annulation ultérieure absente de ce fichier : aucune authenticité n’est inventée.

Les coordinateurs SAF proposent V4, gardent le candidat exact jusqu’à confirmation,
et conservent le verrou commun et les commits incertains. Export confirmé seulement
après fermeture/relecture identique du document. Pas de nettoyage ni retry de flux.
L’interface d’annulation n’est utilisable qu’avec le stockage et ces sauvegardes.

## Interaction et sécurité

Après sélection explicite : **Supprimer cette flèche**, dialogue expliquant le
retrait des comptes, la conservation de l’historique et le caractère définitif,
puis confirmation séparée. Aucun appui long ajouté. Ouvrir, commenter et fermer
le dialogue n’allouent ni événement métier ni intention SQL.

Le dialogue retient contexte séance/volée/impact, ticket et état attendu. Les
callbacks obsolètes sont ignorés. Une confirmation crée une seule intention,
avec un instant unique. Pendant l’écriture puis après acquittement, le dialogue
reste présent ; confirmer demeure désactivé jusqu’à fermeture dédiée, empêchant
qu’un second appui atteigne une autre commande ou le Canvas. Une erreur conserve
l’intention, offre relecture/comparaison explicites et interdit de fermer comme
si un commit potentiellement acquis avait été annulé.

La contre-revue a renforcé les sorties d’erreur : un commentaire incompatible
avec les limites de sauvegarde reste un formulaire local corrigeable, sans
intention SQL ni troncature. Le champ est borné visuellement et le dialogue
peut défiler. En cas d’échec soumis, « Quitter l’affichage » ouvre l’avertissement
explicite existant : l’intention locale sera perdue et un commit éventuellement
acquis ne sera ni annulé ni répété. Le ticket est revérifié avant cette sortie ;
les seuls états de présentation sont libérés. La relecture reste proposée.

Les saisies annulées restent lisibles, même après validation/clôture : ancien
score, position, profil/édition, provenance, dates, décisions et traces. Elles
ne participent plus aux marqueurs sélectionnables, jugements ou déplacements.
Les verrous de navigation, SAF et matériel, visée précise et tickets du glisser
ne sont pas contournés. Rotation conserve l’état ViewModel ; une mort de processus
ne transforme pas un dialogue local en écriture acquittée.

## Preuves et limites

Tests ciblés : invariants purs, X/M/9, numéros/capacité, dates, preuves falsifiées,
codec strict et aller-retour complet ; SQLite natif sur disque avec migrations
V1/V2/V3, lignes conservées, triggers anti-DML, vrais INSERT/COMMIT, rollback,
annulation coroutine, deux écrivains, aperçu périmé et acquittement perdu.
Les deux vrais contrôleurs et coordinateurs SAF sont reliés à Room et à des
documents synthétiques. Les tests de présentation Android JVM restent distincts.

Un seul contrôle complet est prévu sur la candidate finale, avec XML, builds,
lint, schémas/golden et liens. La démonstration Compose sur `medium_phone` emploie
une identité synthétique indépendante ; seul `/usr/bin/adb -s emulator-5554` est
autorisé. Aucun accès au Pixel. Les résultats effectivement obtenus sont consignés
dans le rapport de validation, sans assimiler l’émulateur à un essai humain au doigt.
