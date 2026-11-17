# Contrat de sauvegarde JSON V4 — Annulations durables

Voir [ADR 048](adr/048-annulation-logique-durable-impacts.md).
Archive 4, Room 4 et scoring 1 sont trois versions indépendantes.

La racine exige exactement `schemaVersion: 4`, `scoringContractVersion: 1`,
`profiles`, `sessions`, `bows`, `arrowSets`, `cancellations`. Toutes les listes
sont obligatoires, même vides. Un champ absent ou inconnu n’est pas ignoré.
Les payloads complets de profils, configurations, séances, impacts, décisions,
repositionnements et matériel sont ceux de l’[ADR 037](adr/037-contrat-archive-seances-configurables-v3.md)
et du [codec V3](adr/038-codec-json-v3-strict.md), sans perte ni réinterprétation.

Chaque entrée de `cancellations` exige cinq clés :

| Clé | Valeur |
|---|---|
| `endUuid` | UUID v4 canonique de la volée propriétaire |
| `impactUuid` | UUID v4 canonique d’un impact conservé dans cette volée |
| `cancelledAt` | Instant UTC explicite, nanosecondes conservées |
| `reason` | `SAISIE_PAR_ERREUR` |
| `comment` | Chaîne conservée exactement, ou `null` explicite |

Une preuve unique par impact. Aucun UUID supplémentaire. Les entrées suivent
l’ordre source des séances puis des volées, et l’ordre chronologique d’ajout
des preuves dans chaque volée. Un ordre contradictoire est rejeté, jamais trié
silencieusement. Les impacts restent dans leur liste d’origine avec leurs index,
valeurs Float32, scores, décisions et traces exacts. L’annulation est une décision
de participation ; un score zéro ou M n’est pas une preuve d’annulation.

La reconstruction est intégrale, avec diagnostics typés et chemins. Elle vérifie
identités, propriétaires, profils/éditions, dates, chaîne d’héritage et histoire
de position selon les règles existantes, puis la validité de toutes les preuves.
Un brouillon ne peut pas contenir d’annulation ; celle-ci précède toute validation
ou clôture déclarée. Elle ne crée aucun droit d’édition après clôture.

Budgets : 16 MiB UTF-8, profondeur 32, 200 000 valeurs JSON, 20 000 éléments par
tableau, limites historiques de chaînes/clés/nombres. Le budget métier de
200 000 éléments inclut les preuves ; chaque commentaire est borné à 16 384
unités UTF-16, surrogates invalides rejetés. Les limites s’appliquent aussi à
l’encodage, suivi d’un décodage/reconstruction et d’une égalité complète.

Les [V1](export-format-v1.md), [V2](export-format-v2.md) et V3 publiées gardent
leurs règles et golden. Leurs encodeurs/collecteurs refusent la perte d’une
preuve. Aucune rétrogradation silencieuse vers un fichier sans annulations.
Leur lecture reste disponible. Une ancienne référence matérielle opaque n’est
ni effacée ni résolue ; une V1 sans catalogue ne demande aucune suppression.

L’import est additif, atomique et conservateur : reçu identique sans DML ; contenu
connu différent en conflit, même si le reçu contient une annulation ou une date
plus récente. Une vieille archive ne réactive donc pas un impact local annulé.
Une base vierge disposant uniquement d’un ancien fichier ne peut pas inventer
l’historique postérieur à ce fichier.

L’export SAF attend fermeture et relecture identique ; la restauration retient
le candidat exact, revérifie l’aperçu sous verrou et distingue commit confirmé,
conflit, incohérence, erreur technique et acquittement incertain. Aucune relance
automatique. La qualification humaine des interactions reste distincte.
