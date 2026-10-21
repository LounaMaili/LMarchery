# ADR 036 — Réconciliation Android / domaine configurable

Statut : intégration Git isolée, en revue. **Aucune activation fonctionnelle des séances mixtes**, aucun essai physique revendiqué.

## Sources et graphe

Ancêtre commun des branches : `e4c7e56817baa3f5a5a366bdf5895b3d38f6b798` (`main` à la création).

- Ligne Android : #23 → #24 → #25 → #27 → #28 → #29 → #30, puis fusion contrôlée de #22 dans #32, tête `2c3afeb81982bf9e0a0fa12d0abe3e6bbf7fa9c5`.
- Ligne domaine : #33 (`a70c05f07175664b94283cb8287b1070a941a7a1`) → #34 (`af3598abff20bfc1960ae802cae33bf80d6f0c2b`) → #35 (`8a763e451bdd793ad78145ebc52f846a1e83bb73`).
- #36 : commit de réconciliation à **deux parents**, d'abord #32 puis #35. PR en brouillon, ciblant `codex/integrate-precision-equipment`, sans modification ou fusion des branches sources.

## Résolution des écarts

Les nouveaux fichiers Kotlin du domaine configurable (`core:model` et `core:scoring`) n'écrasent aucun fichier des fonctionnalités Android de #32. Le scoring mixte conserve son paquet séparé et n'influence pas les calculs historiques.

`docs/README.md` : regrouper les ADR 024–027, 029–032, 033–036, le catalogue, les sauvegardes et le protocole Pixel.

`docs/modele-donnees-v1.md` : conserver la description **Room V2 et ses dix tables** de #32 tout en ajoutant les contrats autonomes `EndConfiguration` et `ConfiguredSession` des #33–#35. Ne pas reprendre l'ancienne description Room 1 de la branche métier. Mettre à jour l'indication devenue obsolète sur l'absence d'interface SAF.

`docs/regles-score.md` : retenir la documentation de calcul mixte de #34, absente de #32.

Les ADR historiques des deux branches sont conservés sans fusion ni réécriture. Aucun schéma Room, codec V1/V2, DAO, migration, interface Android, dépendance ou contrat de scoring historique n'est modifié.

## Garanties des contrats

- `Session` reste homogène et reste l'unique agrégat accepté par le stockage Android Room V2.
- `ConfiguredSession` représente des configurations **immuables par volée** et leurs origines, avec conversion depuis une `DomainArchive` **V1 préalablement validée** et complète. La conversion n'utilise jamais un profil « similaire », une édition devinée ou une date historique inventée.
- `DomainArchiveV2` reste **distincte** de `DomainArchive` : les arcs, jeux de flèches et références opaques demeurent dans le parcours V2. Une lecture historique V1 explicite du magasin, lorsqu'elle est demandée séparément, n'autorise ni projection silencieuse de V2, ni suppression de catalogue.
- Les archives V1/V2 et `scoringContractVersion = 1` restent inchangés. Aucune sauvegarde V1/V2 ne peut prétendre représenter une séance mixte.
- Le registre d'identités, les transactions atomiques, les conflits `expected/proposed`, les rollbacks, les traces, les décisions et les protections contre les callbacks obsolètes restent ceux de #32.

## Preuves ajoutées

- Test natif sur **fichier SQLite Room V2** peuplé : séance close et traces, matériel, réouverture, conversion depuis la lecture historique V1 distincte, statistiques, égalité intégrale des dix tables avant/après.
- Test sur fichier Room V2 : création d'une prochaine volée *en mémoire seulement*, aucune ligne `ends` ni autre table modifiée.
- Golden JSON V1 : conversion fidèle, profil et édition, références opaques et bytes originaux inchangés.
- JSON V2 : matériel réel, lecture/réencodage, rejet par décodeur V1 et préservation intégrale des références.
- JSON V2 avec catalogues explicitement vides : références opaques préservées, sans création implicite de fiches.
- JSON V2 à profil officiel contradictoire : rejet intégral typé.

Les suites historiques des deux parents restent exécutables ensemble sur cette branche, notamment migrations Room 1→2, lecture/import V2 transactionnels, traces et validations de scoring. Les nouveaux tests ne remplacent pas ces preuves antérieures.

**Exécution réelle des tests/CI** : à relever dans les rapports GitHub sur le SHA final. Ne pas déduire un nombre de tests ou une réussite de l'addition des suites parentes.

## Limite ADB

L'accès ADB au poste Fedora et au Pixel 8 n'est pas disponible depuis l'environnement d'intégration GitHub utilisé pour cette réconciliation. **Aucun appareil n'a été interrogé ni modifié.** Aucun APK installé et aucune migration Room physique lancée. Le protocole de la #32 reste à suivre ultérieurement, en installation indépendante, avec confirmation humaine séparée. Les gestes, la rotation physique et les fournisseurs SAF restent NON TESTÉS ici.

## Étapes suivantes — sans démarrage automatique

1. Définir le **contrat d'archive successeur** représentant une configuration initiale, une configuration effective pour chaque volée, la provenance et le catalogue complet ; décider les versions indépendantes du scoring et de Room ; lire V1/V2 sans altération et sans écrasement.
2. Étendre **Room** depuis la vraie version publiée (2 sur cette branche) : propriétaire/configuration/provenance par volée et migration non destructive 1→2→suivante, profils/éditions exacts, historique intact, comparaison complète sous verrou.
3. Ajouter collecte/import **atomiques** de l'archive successeur, idempotence, concurrence, rollback après INSERT et état de commit incertain.
4. Adapter enfin les contrôleurs et écrans Android avec candidat de prochaine volée, héritage/changement volontaire, exclusions visée/matériel/SAF et réessai strict, sans activer de flux producteur avant persistance/export fidèles.

## Arbitrages ouverts

- Volées FIXED vides, incomplètes ou excédentaires ; validation et clôture.
- Plafonds UI pour comptes personnalisés, presets d'épreuves et terminologie « personnalisé » / officiel.
- Choix du maximum affiché, dénominateurs, arrondis, groupes et limites numériques des séances mixtes.
- Correction exceptionnelle d'une configuration existante, raison obligatoire et journal non falsifiable.
- Révisions post-clôture et rétention des anciennes décisions ; version et provenance de capture persistées.

Aucun arbitrage n'est implicitement décidé par cette fusion.
