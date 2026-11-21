# Archive V7 des estimations météorologiques

La racine possède trois clés obligatoires. `schemaVersion` vaut 7. `base` est le document [V6 complet](export-format-v6.md). `estimates` est une liste canonique par UUID. Chaque entrée appartient à une séance du document. Toutes les clés de l’entrée sont présentes, avec `null` pour une valeur facultative inconnue.

| Clés | Signification |
|---|---|
| `uuid`, `sessionUuid` | UUID v4 de l’estimation et de la séance. L’identité de l’estimation est unique dans toute l’archive. |
| `provider`, `kind` | Source et type obligatoires. `PROVIDER_ESTIMATE` pour une condition modélisée, `FORECAST` pour une prévision. |
| `modelSelection`, `model` | Politique de sélection demandée et modèle effectivement identifié, chacun facultatif. `best_match` désigne une sélection. |
| `retrievedAt`, `issuedAt`, `validAt` | Instants avec décalage UTC explicite. Récupération et validité obligatoires. L’émission peut être inconnue. Le temps de calcul du serveur ne renseigne aucune de ces dates. |
| `intervalSeconds` | Durée facultative du pas de temps. Obligatoire pour un cumul de précipitations, qui couvre la période précédant la validité. |
| `requested`, `grid` | Deux objets WGS84 en degrés avec `latitude` et `longitude`. Le point demandé est obligatoire. La maille entière peut être inconnue. |
| `windHeightM`, `temperatureHeightM` | Hauteurs de référence au-dessus du sol, en mètres lorsqu’elles sont connues. |
| `windMps`, `gustMps`, `fromTrueDegrees` | Vitesse, rafales et direction d’origine horaire depuis le nord géographique dans [0°, 360°[. Pour Open-Meteo, vitesse à l’instant et rafale maximale sur le pas précédent à 10 m. Le calme a une direction indéfinie. |
| `temperatureC`, `precipitationMm` | Température en °C et cumul estimé en mm. Le zéro et l’inconnu sont distincts. Aucune probabilité n’est convertie en quantité ou en constat de pluie. |

## Lecture Desktop et sauvegarde Android

Le consommateur Kotlin utilise `ArchiveDocumentDecoder` puis `ArchiveDocumentRead.V7.archive`. `DomainArchiveV7.base` expose le graphe V6, y compris les relevés manuels. `estimates` expose les `SessionEstimate` immuables avec leur `WeatherEstimate`. Les lecteurs des versions antérieures continuent de lire les versions qu’ils prennent en charge. Afficher l’attribution Open-Meteo et la licence lors de la réutilisation de ces données. [Décision et conditions fournisseur](adr/056-estimations-meteo.md)

Le fichier contient les coordonnées en clair. Le consentement au partage pour cette sauvegarde couvre le catalogue, les lieux historiques, les points transmis et les mailles. Les exports V1 à V6 refusent la présence d’une estimation. Une restauration V7 vérifie le graphe, les UUID et les contenus, puis recalcule son aperçu sous verrou. Un UUID déjà présent à l’identique ne produit pas de nouvelle écriture. Toute divergence ou modification depuis l’aperçu exige une nouvelle décision. Une archive ancienne ne supprime aucune estimation connue.
