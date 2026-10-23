# ADR 042 — SAF JSON V3 et consultation des séances configurables

Statut : implémenté, validation automatisée ; essais fournisseur SAF, rotation et
appareil reportés. Aucun accès ADB pendant cette mission.
Base exacte : `c2391443de6a59b15ca3d43658458deb47725bfd` (#41).
PR en brouillon vers `codex/room-v3-archive-restore`, sans fusion ni auto-merge.

## Références et périmètre

Les [ADR 021](021-export-json-versionne-android.md),
[022](022-import-json-controle-transactionnel.md) et
[029](029-saf-android-archives-v2.md) définissent les coordinateurs SAF, leurs
flux bornés et le verrou commun. Les [ADR 037](037-contrat-archive-seances-configurables-v3.md),
[038](038-codec-json-v3-strict.md), [039](039-room-v3-migration-lecture.md),
[040](040-ecritures-room-v3-configurables.md) et
[041](041-restauration-archives-v3-atomique.md) fournissent les contrats V3,
lecture cohérente et restauration atomique.

Cette étape raccorde ces API à Android. **Room reste 3**, scoring 1 et JSON
V1/V2/V3 inchangés. Aucun schéma, migration, DAO, codec historique, golden,
dépendance, règle sportive, Canvas ou geste de visée n’est modifié. Les écritures
locales `createConfiguredSession` / `updateConfiguredSession` ne sont pas exposées
par les contrôleurs Android : aucun formulaire de séance mixte dans cette PR.

## Dispatch strict sans conversion

[`ArchiveDocumentDecoder`](../../core/export/src/main/kotlin/com/lmarchery/core/export/ArchiveDocumentDecoder.kt)
est un adaptateur additionnel de lecture. Le parseur strict existant examine le
document entier et son `schemaVersion` obligatoire, unique et entier. Il libère
l’arbre de dispatch avant le décodage par le codec sélectionné :

| Version reçue | Codec existant | Parcours Room |
|---|---|---|
| 1 | `ArchiveJsonCodec.decodeVersioned` | import historique V1 |
| 2 | `ArchiveJsonCodec.decodeVersioned` | aperçu/confirmation V2 |
| 3 | `ArchiveV3JsonCodec.decode` | aperçu/confirmation V3 |
| Autre, absent ou mal formé | rejet explicite | aucun appel d’import |

Aucun essai d’une version inférieure après un rejet du codec choisi. Les erreurs
conservent code, chemin et détail, ainsi que `JsonIssue` ou `ArchiveV3JsonIssue`
original ; ce dernier garde aussi le diagnostic métier typé de reconstruction.
Le lecteur SAF borne à 16 MiB avec au plus un octet supplémentaire de détection,
ferme le flux avant de publier les octets, puis les budgets de profondeur,
valeurs, collections et textes des codecs s’appliquent. Nom et MIME ne prouvent
ni version ni validité. Le dispatch implique une seconde analyse bornée par le
codec existant ; il ne crée pas de décodeur permissif alternatif.

V1 garde le sens « matériel non fourni ». V2 garde ses listes explicitement
vides et ses références opaques. Aucune fiche, association ou configuration n’est
inventée ; aucun V3 n’est projeté en V2/V1 pour obtenir un succès.

## Export V3 complet

Le choix principal devient « Exporter la sauvegarde complète V3 ». Le repository
utilise **une seule** collecte `RoomSessionStore.readArchiveV3()` sur le magasin
unique du processus. Tous les profils, éditions, séances, captures initiales et
effectives, volées vides, décisions, traces et équipements y figurent. Une erreur
bloque l’ensemble avant l’ouverture du sélecteur. Ce n’est pas une boucle de
lectures individuelles de la liste Android.

Le codec V3 vérifie l’aller-retour avant `CreateDocument`. Les octets préparés
sont figés pendant le choix de destination. Le nom `lmarchery-v3-...` est daté
par Clock UTC ; aucune date de sauvegarde n’est ajoutée au contenu. Le writer
existant écrit, ferme, relit et compare chaque octet ; un succès nécessite aussi
la fermeture de la relecture. Une erreur laisse le document potentiellement
incomplet, sans effacement ni réutilisation automatique.

V2 et V1 restent des choix de compatibilité distincts, V1 explicitement sans
matériel. Leurs collecteurs refusent toute base contenant `CONFIGURED`, même
homogène visuellement ; aucun export partiel ou changement automatique de version.
Une archive V3 vide, matériel seul ou profils seuls reste complète et valide.

## Import V3 et candidat exact

`RoomSessionRepository` ajoute les capacités `ArchiveV3Source` et
`ArchiveV3Importer`. `PreparedV3Import` est opaque : archive validée et plan
lisibles, `commit()` capturant l’aperçu Room exact. Aucun constructeur de preview
SQL ni SQL de restauration dans Compose.

Le coordinateur conserve le candidat en mémoire dans le ViewModel. L’aperçu
présente **six catégories** nouvelles/identiques/conflictuelles : séances,
profils, volées, impacts, arcs, jeux de flèches. Une divergence bloque l’ensemble.
Une confirmation V3 porte un identifiant de présentation spécifique à cet aperçu
(pas un UUID métier). Un bouton d’un aperçu ancien ne confirme pas son successeur.
Le fichier n’est jamais rouvert à la confirmation.

La transition synchrone vers `Confirming`/`Importing` empêche les doubles appels.
La capacité appelle une seule restauration transactionnelle 041 : état global,
représentation SQL et plan recontrôlés sous verrou, INSERT seuls, union validée,
relecture entière et préservation des anciennes lignes avant commit. Le succès
Android est publié seulement après l’acquittement. Un échec d’actualisation de
liste après ce succès n’en change pas l’issue.

Les résultats du magasin restent distincts au passage Android :

| Résultat | Présentation / conduite |
|---|---|
| `Imported` | comptes des ajouts et identiques confirmés |
| `AlreadyPresent` | zéro écriture ; archive vide distinguée des identiques |
| `Conflict` | nouveau choix/aperçu explicite requis, aucune fusion |
| `NeedsIntervention(NOT_ATTEMPTED)` | diagnostic précis ; aucune écriture métier tentée par cet appel |
| `TechnicalFailure` | échec avant DML selon le contrat 041 |
| `NeedsIntervention(UNCONFIRMED)` / `CommitUnconfirmed` | candidat exact conservé, résultat non confirmé |
| Annulation pendant la confirmation sans preuve contraire | résultat non confirmé, aucun rollback supposé |

Un résultat V3 incertain conserve le verrou de navigation/écriture/import/export/
matériel et le candidat. Deux choix explicites sont proposés :

1. **Relire et préparer un nouvel aperçu V3** : même archive en mémoire, nouvelle
   lecture Room, aucune confirmation ni écriture automatique. Le nouvel aperçu
   nécessite un nouveau consentement. Si le commit précédent est déjà présent,
   la confirmation suivante donnera `AlreadyPresent`, sans DML.
2. **Fermer sans relancer** : avertissement explicite que cela n’annule aucune
   transaction antérieure ; abandon de la capacité locale et actualisation de
   liste. Aucun retry implicite ni candidat rebasé en arrière-plan.

Un échec de cette relecture conserve l’incertitude et le candidat. Un conflit
dans le nouvel aperçu reste bloquant. Fermer cet aperçu ne prétend pas annuler
la transaction antérieure. Les preuves de commit acquis puis acquittement perdu
et de rollback réel restent celles de 041, exécutées avec les nouvelles suites.

## Sélecteurs et cycle de vie

Les launchers restent au niveau racine, hors du contenu recyclé. Chaque retour
et erreur de lancement porte le ticket de la requête SAF qui l’a lancé. Les
coordinateurs rejettent les tickets obsolètes et ne consomment une requête qu’une
fois. Le ticket en vol est conservé par `rememberSaveable`, l’archive/les octets
par le ViewModel. Le retour du launcher correspond à son unique lancement en vol
selon le contrat Android ; aucun nouveau sélecteur ne remplace ce lancement.

Chaque coordinateur libère seulement un verrou qu’il a acquis. Un callback reçu
après perte de processus ne peut pas libérer le verrou d’un autre écran matériel.
Une nouvelle Activity du même ViewModel ne relance pas le sélecteur ; une mort
de processus perd le candidat et produit un état interrompu, jamais une reprise
ou un acquittement inventé. Les tests JVM ne constituent pas une preuve de cette
rotation sur un véritable fournisseur Android.

## Consultation configurable strictement sans édition

La liste conserve sa découverte historique par UUID et la lecture de chaque
séance. Une lecture historique refusée peut être consultée **uniquement** si
`readArchiveV3()` a reconstruit intégralement la base et contient l’UUID demandé.
Ce résultat V3 est affiché en lecture seule. Aucun test de similarité de cible,
parsing de texte de diagnostic ou utilisation des alias historiques ne décide
d’une configuration effective. En l’absence de lecture V3 valide, l’incident reste
visible ; aucun agrégat partiel, réparation ou profil de substitution.

La découverte UI n’est pas présentée comme une sauvegarde atomique. Les deux
exports complets et la restauration conservent leurs propres transactions
intégrales. Dans une liste, un incident ne fait pas disparaître les autres
entrées lisibles ; une corruption globale empêche toujours la sauvegarde complète.

`SessionUiState` distingue `saved: DomainArchive` de `configuredSaved` sans les
faire coexister. Le premier seul alimente l’éditeur historique. Le passage à une
séance configurable efface sélection/correction/candidats tactiles et ne donne
aucune capacité de modification, même pour une séance commencée homogène. Le
retour à une ancienne séance conserve son fonctionnement existant. Les entrées
de présentation incohérentes restent des incidents affichables, pas des exceptions
de constructeur masquant la liste.

La consultation montre date civile/fuseau, statut, initiale/origine, chaque
configuration effective avec UUID/édition, distance, FIXED/nombre ou FREE,
indoor/mode de score, origine/héritage, blason exact, scores géométriques/finals,
décisions et repositionnements. Les références matériel restent opaques et
non modifiables dans cette vue. Aucun ajout de flèche, décision, repositionnement,
validation, clôture ou changement de matériel n’est raccordé à ces agrégats.

Les agrégats utilisent `summarizeConfiguredSession` et le contexte effectif de
chaque volée, sans recalculer les décisions. Scores validés/provisoires sont
séparés, regroupements par configuration exacte, compteurs X/M/9/maxima issus
des valeurs finales. Les capacités FIXED et flèches présentes FREE sont nommées
séparément du maximum des flèches réellement enregistrées ; aucun maximum planifié
ni pourcentage réglementaire choisi implicitement. Une somme mixte n’est pas
annoncée comme comparable sportivement. Un dépassement Long devient un diagnostic
de calcul indisponible, pas zéro, NaN, saturation ou crash de l’écran.

## Preuves automatisées et limites

- Tests de dispatch strict sur golden V1 et V3, source V2 figée par les suites
  historiques, versions/types/clés invalides, budgets et diagnostics originaux.
- Doubles des coordinateurs/flux : collecte V3 complète, gel du candidat et des
  octets, toutes phases de fermeture/relecture, six catégories, annulations,
  tickets obsolètes, doubles confirmations, verrou commun réel des contrôleurs,
  perte d’acquittement, relecture délibérée et succès non rétrogradé par refresh.
- Doubles de présentation : historiques encore éditables, configurables toujours
  read-only, navigation, refus de toutes les commandes, corruption visible,
  décisions/traces et compteurs inchangés, héritage, FREE vide et dépassements.
- **SQLite natif sur disque** : vrais documents locaux synthétiques, sources V1
  réellement migrées en 3, coexistence/import V1/V2 avant V3, restauration complète
  V3 et réouverture, anciennes lignes conservées, exports lecteurs/idempotence sous
  gardes DML, erreurs tardives après véritable INSERT, aperçu périmé, corruption,
  vérifications FK/intégrité et conservation du catalogue/profils non référencés.

Les fichiers du PC ne simulent pas un fournisseur SAF Android. Aucun test ADB,
Pixel, installation APK, geste, rotation réelle, sélecteur système ni émulateur
n’est exécuté. Le [protocole reporté](../validation-saf-v3-pr42.md) distingue ces
cas **NON TESTÉS** des résultats automatisés ci-dessus.

Validation de référence : `./scripts/dev-check.sh --rerun-tasks --console=plain`,
XML réellement exécutés, SQLite natif disque, compilations et lint Android,
empreintes des schémas/migrations/codecs/golden historiques, liens documentaires
et `git diff --check`, puis Quality gate sur le commit publié exact. Les résultats
mesurés sont consignés dans la PR ; aucune CI ancienne ne vaut pour ce commit.

## Étapes futures, non engagées

Création/édition de séances mixtes, candidat de configuration suivante, choix
sportifs ouverts en 033–035 et parcours de révision contrôlée demandent une PR
séparée. Aucun Room 4, JSON 4, effacement, fusion de données, réouverture après
clôture ou correction rétroactive de configuration dans cette livraison.
