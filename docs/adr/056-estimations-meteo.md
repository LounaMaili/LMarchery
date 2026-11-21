# ADR 056 — Estimation météo ponctuelle d’une séance

L2 conserve ce que l’archer a observé. L3a ajoute une source indépendante pour des valeurs calculées sur une maille météorologique. Le pas de temps, la hauteur et le lieu peuvent différer d’une mesure sur le terrain. Les importations et le journal affichent les deux provenances séparément. [Observations manuelles et conventions](054-observations-meteo-et-vent.md)

## Choix du fournisseur

Le port Kotlin `WeatherProvider.current(point)` accepte uniquement un point WGS84. L’adaptateur expérimental Open-Meteo prépare un appel HTTPS à Forecast API avec les variables `current`, une réponse en temps Unix GMT et les unités m/s, °C et mm. Le transport est injecté. L’application distribuée ne fournit aucun transport. Le test utilise une réponse déterministe en mémoire. La réponse est décodée avec une limite de 16 Kio. Le transport d’une éventuelle distribution connectée devra aussi borner la lecture avant allocation, annuler les appels, refuser les redirections et éviter de journaliser les coordonnées. [Paramètres et variables](https://open-meteo.com/en/docs)

Open-Meteo réserve l’API gratuite à l’usage non commercial. Les limites annoncées sont inférieures à 600 appels par minute, 5 000 par heure et 10 000 par jour. L’offre affiche également 300 000 appels par mois. L’accès commercial passe par abonnement. Les données sont sous CC BY 4.0. L’affichage indique la source avec lien et licence. Toute redistribution doit conserver l’attribution et signaler les transformations, notamment la normalisation de la direction. L’éligibilité de la distribution LMarchery et la gestion des quotas restent à établir avant activation. Aucune clé dans l’application. [Conditions](https://open-meteo.com/en/terms) · [Offres](https://open-meteo.com/en/pricing) · [Licence](https://creativecommons.org/licenses/by/4.0/)

## Consentement et conservation

Dans « Conditions de tir », l’enregistrement manuel reste disponible hors ligne. Pour un fournisseur configuré, l’archer voit le destinataire et le point du lieu adopté pour cette séance. Si le point manque, il peut entrer une paire de coordonnées limitée à cette demande. « Autoriser et demander » vaut une fois. L’ouverture de l’écran, son actualisation et les gestes sur le blason ne déclenchent aucune requête. La fermeture du dialogue abandonne la demande ou l’aperçu. Un fournisseur déjà contacté a pu recevoir le point avant l’annulation.

L’aperçu indique la source, le type, les dates et les valeurs inconnues. « Conserver l’estimation » ajoute l’enregistrement exact sous son UUID dans la séance en cours. Une observation manuelle complémentaire garde son propre UUID. L’écriture et la vérification sont transactionnelles. En cas de résultat incertain, la seule action de résolution relit l’UUID initial. Une divergence conserve le contenu existant.

L’adaptateur refuse une horloge future, une réponse trop ancienne, des unités différentes de celles demandées ou une maille située à plus de 100 km. La fraîcheur maximale de 30 minutes est vérifiée à réception et à la confirmation. Le seuil de distance détecte une réponse manifestement étrangère. La précision locale et l’incertitude angulaire restent inconnues. Les estimations ne sont pas des entrées de la projection historique L2.

Room 7 ajoute une table et son index de séance. La sauvegarde V7 conserve les valeurs de la maille et le point transmis. Son export exige le consentement aux coordonnées même si le lieu historique en est dépourvu. [Contrat partagé JSON V7](../export-format-v7.md)
