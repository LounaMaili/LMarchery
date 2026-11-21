# ADR 054 — Observations météo et composantes du vent

L2 adopte la saisie manuelle hors ligne, les observations immuables et l’analyse descriptive des séances clôturées. L3a ajoute le [contrat distinct des estimations de fournisseur](058-estimations-meteo.md).

## Observation et provenance

Une observation facultative porte un UUID, une date de validité `Instant` et éventuellement un intervalle explicite `[début, fin[`. Elle distingue la date ou le créneau de la valeur de l’instant de récupération fournisseur. Lorsqu’il existe, l’instant d’émission d’une prévision est aussi conservé. Le contrat Kotlin/JVM représenterait les grandeurs par valeurs typées et des absences explicites, avec une provenance par valeur ou groupe cohérent.

| Grandeur | Convention canonique |
|---|---|
| Vent moyen, rafale | m/s finis et non négatifs. Granularité et hauteur de référence connues conservées. Une rafale ne reçoit aucune direction déduite du vent moyen. |
| Direction d’origine | Degrés horaires dans `[0, 360[`, nord de référence déclaré. `null` signifie direction indisponible. Vent calme et vent variable se distinguent d’une direction connue. |
| Température | °C, valeur finie, hauteur de mesure fournie conservée. |
| Précipitations | mm non négatifs sur un intervalle connu. Zéro signifie une valeur disponible, aucune valeur est distincte. |

L’observation est associée explicitement à une séance, une volée ou un ensemble de UUID de volées de la séance. Elle peut référencer un lieu ou une position de récupération facultative selon l’[ADR 052](052-lieux-et-lignes-de-tir.md). Une observation de séance et une mesure propre à une volée peuvent coexister. Une vue effective privilégierait une valeur explicitement confirmée pour cette volée, puis la valeur de séance explicitement applicable au même créneau. Une valeur hors créneau est conservée à titre de contexte et présentée comme telle. Plusieurs valeurs concurrentes exigent une sélection visible et ne sont pas fusionnées automatiquement.

Une provenance indique `MANUAL`, `FORECAST` ou `PROVIDER_ESTIMATE` avec fournisseur et modèle lorsqu’ils sont connus. Une mesure ressentie sur la ligne reste distincte d’une estimation de grille. La fraîcheur repose sur deux durées séparées, écart entre instant de tir et validité, âge de récupération au moment de consulter. Une date, un créneau inconnu ou une donnée périmée s’affichent sans certitude inventée. Une lecture historique garde la valeur confirmée même après la péremption de la source.

La pluie qualitative est une information indépendante du cumul mesuré. Une indication « pluie » ou « pas de pluie » renseigne seulement le constat. Seule une quantité saisie avec un intervalle de validité complet alimente les millimètres. Au moment d’un changement de conditions, l’archer ajoute un nouveau relevé. Les séances clôturées présentent les relevés en lecture seule.

- **Manuel** conserve les valeurs entrées avec leur provenance.
- **Automatique** proposerait ponctuellement la météo pour le lieu choisi, sans suivi continu. À défaut, une localisation ponctuelle est facultative et soumise au consentement, y compris pour la transmission à un tiers.
- **Mixte** peut compléter ou corriger certains champs à la main et garde les provenances distinctes. Une récupération ne remplace pas une valeur confirmée précédemment. Une panne réseau laisse la saisie manuelle disponible.

## Projection commune du vent

Pour un vent moyen de vitesse `v`, l’azimut de la ligne `a` et la direction **d’où vient** le vent `b` doivent être exprimés en nord géographique après la conversion de l’[ADR 053](053-azimut-et-boussole.md). Prendre `d = radians(normaliser(b - a))`.

```text
faceDos = v × cos(d)         positif de face, négatif de dos
lateral = v × sin(d)         positif venant de droite, négatif venant de gauche
```

Le sens de déplacement de l’air est opposé. Les composantes d’écoulement vers les cibles et vers la droite valent respectivement `-faceDos` et `-lateral`. Aucune composante ne corrige un score ni un groupement. La prochaine implémentation JVM documentera l’arrondi numérique, avec une tolérance de test pour les axes cardinaux.

| Ligne vers le nord, 4 m/s | Face/dos | Latéral |
|---|---:|---:|
| Vent venant du nord | +4 | 0 |
| Vent venant du sud | −4 | 0 |
| Vent venant de l’est | 0 | +4 |
| Vent venant de l’ouest | 0 | −4 |
| Vent venant du nord-est | +√8 | +√8 |

Un calme établi (`v = 0`) donne deux zéros, pour n’importe quelle orientation. Sans vitesse, ou pour une vitesse positive avec orientation ou direction absente, incertaine au point d’être inutilisable, ou de référence incompatible, les composantes sont indisponibles. Les tests JVM futurs couvrent aussi le franchissement de `0°`, la symétrie nord/sud, le cas oblique, le calme sans direction, l’absence d’orientation avec vent non nul, et les combinaisons de déclinaison. La fonction pure de relecture historique utilise le vent moyen, l’orientation de l’instantané L1 et les références géographiques explicites. L2 ne convertit pas le nord magnétique. Une somme d’incertitudes déclarées atteignant 90° rend la projection indisponible. Pour un vent non nul, une incertitude angulaire non renseignée sur la ligne ou le relevé rend également le résultat indisponible. Les résidus trigonométriques de facteur inférieur à 10⁻¹⁵ sont ramenés à zéro sur les axes. Le résultat est dérivé à la lecture de l’archive après clôture. Desktop réutilisera les types, la décision de disponibilité et la fonction pure pour ses analyses.

## Fournisseur, stockage et export futurs

Open-Meteo fournit notamment des estimations de vent à 10 m et des créneaux de précipitations. Un adaptateur devra vérifier unités, intervalle, zone horaire et disponibilité pour le produit choisi. Les données CC BY 4.0 et les conditions des points d’accès sont distinctes. Les conditions consultées le 21 novembre 2026 limitent l’API gratuite aux usages non commerciaux avec quotas. Avant intégration, vérifier les conditions de l’offre distribuée, l’attribution des données et leur redistribution, et la confidentialité du lieu transmis. [Documentation Open-Meteo](https://open-meteo.com/en/docs) · [Conditions Open-Meteo](https://open-meteo.com/en/terms)

Le [contrat V6](../export-format-v6.md) documente le stockage Room 6, les propriétés transportées et l’ordre de compatibilité Desktop. Un futur lot pourra compléter des observations et leurs associations via des valeurs immuables et des révisions explicites. Les composantes, calculées à la consultation, restent dérivées. Une nouvelle version d’archive devra conserver les observations, provenances, dates, absences et associations. Les formats stricts V1 à V4 gardent leur représentation et ne peuvent pas absorber silencieusement ces informations.
