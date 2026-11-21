# Archive V6 des observations météorologiques

Le sélecteur de fichiers Android peut exporter un document V6 lisible en clair. Il conserve la [sauvegarde V5](export-format-v5.md) complète dans `base` et les relevés manuels dans `observations`. La racine contient exactement ces deux propriétés et `schemaVersion` égal à 6. Le consentement avant partage de coordonnées géographiques s’applique à tout le contenu V5, y compris aux lieux historiques.

## Contrat de lecture

Chaque propriété est obligatoire dans le JSON. Les valeurs facultatives inconnues sont explicitement nulles.

| Propriété | Valeur |
|---|---|
| `uuid`, `sessionUuid` | UUID v4 du relevé et de la séance propriétaire |
| `endUuids` | Ensemble ordonné par UUID pour le transport. Vide pour une observation de séance. Sinon chaque volée doit appartenir à cette séance |
| `observedAt`, `validFrom`, `validUntil` | Instants ISO avec décalage explicite. Les deux premiers sont obligatoires. Une fin définit un intervalle semi-ouvert de durée strictement positive |
| `meanMps`, `gustMps` | Vitesse moyenne et rafales en m/s, positives ou nulles lorsqu’elles ont été mesurées |
| `from`, `variableWind` | Direction d’origine facultative avec `degrees` dans [0, 360[, `north` géographique ou magnétique (`TRUE_NORTH` ou `MAGNETIC_NORTH`) et `uncertaintyDegrees` facultative. Un vent variable ou calme ne porte pas de direction fixe |
| `temperatureC` | Température mesurée en °C |
| `rain`, `precipitationMm` | Constat facultatif `RAIN` ou `NO_RAIN`, et cumul mesuré facultatif en mm sur l’intervalle. Un constat de pluie laisse le cumul inconnu |
| `origin` | `MANUAL` pour toutes les valeurs du relevé |

Un relevé doit comporter au moins une observation. Les relevés sont immuables. Une nouvelle mesure a son propre UUID même si ses valeurs sont identiques. L’instant de saisie et la période de validité peuvent différer. Les valeurs simultanées restent consultables individuellement.

## Conservation et restauration

Room 6 ajoute `weather_observations` et `weather_ends`. La migration 5→6 les crée vides. L’ajout manuel vérifie sous le verrou écrivain que la séance est en cours et que chaque volée choisie lui appartient. Il inscrit l’identité, les valeurs et toutes les associations dans la même transaction. Les flottants sont conservés par leurs bits. Le décompte des associations protège contre la perte silencieuse d’une volée. L’interface peut relire l’UUID exact si un acquittement est incertain.

La restauration confirmée relit le contenu et les empreintes sous verrou. Un UUID météo identique est idempotent. Une divergence de contenu ou d’attribution bloque la restauration. Un document ancien reste lisible et ne retire aucun relevé existant. Un export V1 à V5 en présence de météo est refusé. Les schémas JSON publiés avant V6 conservent leurs règles.

## Consommation Desktop

Prévoir d’abord la lecture V5. Dans la version suivante, utiliser `ArchiveDocumentDecoder.decode(bytes)` dans `:core:export`, puis `ArchiveDocumentRead.V6.archive`. `DomainArchiveV6.base` porte le graphe V5 inchangé. Les observations Kotlin/JVM sont définies dans `com.lmarchery.core.model.weather`. Pour l’analyse d’une séance clôturée, `historicalWind(observationUuid)` utilise l’orientation effective adoptée pour cette séance et le relevé désigné. Pour un vent positif, le calcul exige les deux références géographiques et deux incertitudes déclarées dont la somme est inférieure à 90°. Une absence de résultat est distincte d’une composante nulle. L’application Android affiche les saisies brutes. Voir l’[ADR 054](adr/054-observations-meteo-et-vent.md).

## Recette Android restante

Dans une séance synthétique en cours, ouvrir « Séance et volées » puis « Conditions de tir ». Enregistrer une observation de séance, puis une observation portant sur deux volées. Annuler un troisième formulaire, fermer l’application et relire les deux premiers relevés. Vérifier les libellés de l’orientation historique, la distinction pluie/cumul et le refus d’ajout après clôture. Vérifier le parcours équivalent en historique, le comportement du clavier et de la rotation, puis le sélecteur SAF réel pour V6.
