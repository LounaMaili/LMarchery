# Archive JSON V8 — Orientations assistées

V8 conserve la mesure magnétique d’origine et la valeur confirmée pour la ligne favorite, la ligne telle qu’elle a été adoptée et l’orientation effective de la séance. Ces trois valeurs peuvent différer. Modifier le favori pour une séance future préserve les instantanés antérieurs.

Le document porte les clés obligatoires `schemaVersion` (8), `sporting` (document V4 complet), `sites`, `lines`, `sessionPlaces`, `observations` et `estimates`. Les observations suivent le [contrat V6](export-format-v6.md) et les estimations le [contrat V7](export-format-v7.md). Les lieux reprennent le [contrat V5](export-format-v5.md) avec un champ supplémentaire dans chaque objet orientation, `reading`.

| Champ | Signification |
|---|---|
| `degrees`, `reference` | Valeur finalement retenue, en degrés horaires, et nord de référence. |
| `origin` | `MANUAL` pour une saisie indépendante, `SENSOR_CONFIRMED` pour une mesure acceptée, éventuellement corrigée. |
| `confirmedAt`, `uncertaintyDegrees` | Instant de confirmation et incertitude si connue. Une mesure assistée exige un instant de confirmation et une incertitude nulle au sens JSON (`null`). |
| `reading` | `null` pour une orientation manuelle. Pour une mesure confirmée, objet obligatoire avec les quatre clés ci-dessous. |
| `magneticDegrees`, `measuredAt` | Azimut magnétique brut de la fenêtre stable, dans [0°, 360°[, et instant de fin d’observation en UTC. |
| `method`, `quality` | `ROTATION_VECTOR` ou `GEOMAGNETIC_ROTATION_VECTOR`, et qualité Android minimale acceptée dans la fenêtre, `MEDIUM` ou `HIGH`. |

La référence d’une mesure assistée est toujours `MAGNETIC_NORTH`. La confirmation peut retenir une valeur différente de la mesure brute. Une origine incohérente, une clé inconnue ou absente, une date de confirmation antérieure à la mesure et une fausse précision sont refusées. Un lieu ou une ligne sans orientation garde une valeur JSON `null`.

Room 8 ajoute des colonnes nullables sur les lignes et sur les deux orientations des instantanés. La migration 7 vers 8 préserve les lignes manuelles et laisse les mesures absentes. Une sauvegarde V5, V6 ou V7 refuse de perdre une provenance assistée, même si elle existe uniquement dans une ancienne séance. Les archives V1 à V7 gardent leur lecture historique. Le lecteur commun expose la V8 via `ArchiveDocumentRead.V8.archive`. Le graphe sportif, météo et lieux reste accessible aux consommateurs Kotlin. Une restauration exige un aperçu et la comparaison de tout le contenu sous transaction, y compris les preuves de mesure des instantanés. Le partage de coordonnées par SAF conserve la confirmation existante.
