# ADR 015 — Sauvegarde versionnée et reconstruction contextualisée

## Contexte et écarts constatés

Les ADR 008 à 014 définissent les invariants des modèles, des décisions, des
déplacements et du temps. Aucun format de fichier ni base utilisateur n'a été
publié. Le document d'export présentait pourtant des IDs Room, des dates sur les
profils immuables et une mise à jour automatique par UUID : ces éléments ne
correspondent pas aux modèles Kotlin et ne constituent pas un format à migrer.

`TargetType` a 13 propriétés, sans `id`, `createdAt`, `updatedAt` ni édition.
`TargetZone` n'a ni ID local ni UUID ; `Bow`, `ArrowSet`, `SessionPreset` restent
prévus. Les agrégats utilisent leurs enfants imbriqués et un contexte de cible
complet. Les totaux sont des fonctions dérivées, pas des propriétés stockées.
Les décisions de score et traces sont des valeurs contrôlées, impossibles à
reconstruire sûrement à partir des seuls scores ou booléens historiques.

## Décisions

### Contrat 1 sans stockage

Ajouter des snapshots Kotlin purs dans `core:scoring.archive`, qui dépend déjà
des modèles et connaît les catalogues. Aucun nouveau module, dépendance Gradle,
sérialiseur, lecteur de fichier ou accès disque n'est nécessaire. Les valeurs
de transfert peuvent contenir des données non résolues ; seules les valeurs
retournées dans `ArchiveReconstruction.Accepted` forment un `DomainArchive` validé.

`schemaVersion = 1` désigne la structure décrite dans
[le contrat de sauvegarde](../export-format-v1.md).
`scoringContractVersion = 1` désigne séparément la sémantique actuelle des ADR
009, 011, 012 et 014 : précision et frontières existantes, décision explicite,
invalidations tracées, événements UTC. Aucun ancien contrat n'est exécuté par
défaut. Version absente ou différente : intervention, sans migration devinée.

Ces numéros sont distincts de l'UUID du profil, de sa définition complète et de
son édition réglementaire éventuelle. L'édition WA `2026-01-27` est une métadonnée
d'enveloppe ; les fixtures historiques ont explicitement `null`, sans certification
rétroactive. Un changement incompatible de sémantique devra avoir son propre
contrat ; un simple changement de représentation n'est pas une nouvelle règle.

### Identités immuables et définitions complètes

`BuiltInTargetRegistry` référence les huit définitions publiées, inchangées, et
leur édition. Il reste dans `core:scoring` : aucune dépendance inverse du modèle.
La validation compare toute la définition, ordre des zones et couleurs compris,
puis l'édition. Un UUID officiel altéré est un conflit, quel que soit le booléen
`isBuiltIn`. Un built-in inconnu est non résolu, jamais remplacé par un profil
ressemblant. Une définition complète est obligatoire même pour un UUID connu.

Les cibles personnalisées complètes avec identité propre et `isBuiltIn=false`
sont admissibles structurellement. Leur éventuelle édition est déclarative.
Une collision de contenu/édition sous un UUID est explicite. Un futur changement
réglementaire ou personnalisé reçoit une nouvelle identité ; il ne réinterprète
pas les séances existantes. Aucun modèle de migration n'est ajouté.

Les identités d'archive sont des UUID v4 canoniques et uniques globalement entre
profils, séances, volées et impacts. Les références sont des UUID ; pas d'IDs Room.
Les références de matériel existantes sont conservées comme UUID opaques, sans
prétendre reconstruire des modèles de matériel encore absents.

### Rejeu contrôlé des décisions et des traces

Résoudre d'abord les profils. Pour chaque impact, partir du point précédent de
la première trace avec sa création et son origine. Reconstituer chaque demande
depuis sa provenance explicite, via `FinalScoreDecision.resolve`, vérifier ses
assertions puis utiliser `withFinalScore` et les copies de métadonnées contrôlées.
Appeler ensuite `reposition` : seule cette méthode existante émet l'évidence opaque.

Le rejeu contrôle chaque contexte de trace, sa continuité spatiale et temporelle,
le motif d'invalidation et les dates de décisions ; il vérifie enfin le point et
la décision courants. Aucun constructeur brut d'`ImpactReposition` n'est ouvert.
Une décision identique peut conserver sa date ancienne à travers une révision de
métadonnées, comme le domaine actuel le permet. Une nouvelle décision doit être
au moins aussi récente que l'étape précédente du rejeu.

La valeur choisie est nécessaire même en géométrie et doit correspondre au
résultat recalculé. Les assertions dérivées facultatives sont toutes confrontées
au résultat, jamais corrigées. Les frontières sont retrouvées dans le profil,
sans confiance dans des côtés fournis ni restriction par tolérance UX.
Construire ensuite les volées et séances avec leurs invariants existants.
Les aller-retour doivent préserver l'égalité complète, traces et provenance comprises.

Les événements transitent par les conversions strictes d'`UtcTimestamps`, avec
nanosecondes conservées et émission `Z`. Le triplet civil reçu est validé tel quel,
sans `fromInstant` réparateur ni fuseau par défaut. Une incompatibilité avec les
règles IANA disponibles conserve le snapshot non résolu.

### Conflits conservateurs

`NeedsIntervention(source, issue)` conserve la source et le premier problème
typé avec son chemin ; aucune séance partielle n'est exposée comme acceptée.
Les dates naïves, provenance absente, profils inconnus, collisions, incohérences
parent/enfant ou valeurs dérivées contradictoires sont bloquantes.

Pour évaluer des révisions, fournir `known: DomainArchive`, référentiel connu
complet concerné. Vérifier les préfixes de traces, leur suppression et les
identités à travers les parents ; puis traiter **toute divergence** d'identité
connue comme conflit, même si les horodatages semblent ordonnés. L'égalité de
contenu est seule acceptée automatiquement. Le timestamp n'est pas une preuve
de descendance causale entre horloges d'appareils différents.

Cette politique ne réalise ni fusion ni mise à jour. Une archive partielle ne
supprime pas les racines absentes. La validité interne d'une restauration sans
référentiel ne prouve pas l'absence d'une histoire antérieure : aucune méthode ne
peut déduire une trace absente de toutes ses entrées. Le futur adaptateur doit
toujours fournir le connu avant remplacement ; une éventuelle résolution explicite
de conflit sera une autre tâche, sans contourner les modèles.

## Conséquences et limites

L'architecture et les API métier existantes ne changent pas. Le contrat est
additif et n'ajoute ni règle, ni profil, ni écran. Les snapshots ne sont pas des
preuves inviolables, et les déplacements ne sont pas un journal général : toutes
les décisions successives à position constante ne sont pas historisées.

Avant Room et JSON : définir le schéma réel, le matériel/presets, le traitement
syntaxique strict et des champs inconnus, les limites de taille, la conservation
des nombres, l'intervention et la filiation des révisions. Garder les couples
entiers secondes/nanosecondes et le triplet civil de l'ADR 014. Les vieilles dates
sans contexte, les provenances perdues et les conflits IANA exigent une source
fiable et une décision explicite, jamais une migration inventée.

*15 octobre 2026*
