# ADR 053 — Azimut de la ligne de tir et boussole

Saisie manuelle adoptée dans L1. La mesure par capteur et la conversion de nord restent proposées pour un lot ultérieur.

L’azimut est l’angle horizontal **de l’archer vers les cibles**, croissant dans le sens horaire à partir du nord indiqué. La valeur finie est normalisée dans `[0°, 360°[`. Une orientation porte sa provenance et sa référence `TRUE_NORTH` ou `MAGNETIC_NORTH`, son incertitude angulaire facultative et l’instant de mesure ou de saisie connu. L’absence est distincte de `0°`. Une référence inconnue ne permet aucune projection géographique.

## Méthodes envisagées

- **Manuelle.** Android demande la valeur et le nord de référence. L’utilisateur indique éventuellement une incertitude ou précise la source, par exemple une carte ou une mesure de terrain. L’application n’invente pas une précision.
- **Capteurs Android.** L’archer aligne le téléphone depuis la ligne vers les cibles. Android adopte explicitement un axe de l’appareil et sa transformation selon la rotation d’écran. Une acquisition brève apprécie la qualité des capteurs et la stabilité circulaire sur plusieurs échantillons. Le résultat est un candidat magnétique, que l’utilisateur confirme ou rejette. Métal, calibration insuffisante, appareil incliné, axe horizontal mal défini, capteur absent et mesure instable peuvent conduire à un refus. Les seuils de stabilité et l’ergonomie seront fixés puis testés sur appareil au moment de l’implémentation. [Capteurs de position Android](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)

## Conversion

Si une position, une date et un modèle de déclinaison utilisables sont disponibles, le futur adaptateur calcule `azimut géographique = normaliser(azimut magnétique + déclinaison)`. La déclinaison est positive à l’est. Si l’altitude requise est estimée, cette approximation doit être connue de l’adaptateur et reflétée dans la qualité signalée. Conserver la valeur brute, la déclinaison, la provenance du modèle, la position et l’instant utiles à la conversion, ainsi que l’incertitude disponible. En l’absence d’une conversion justifiée, l’orientation reste magnétique et le vent géographique ne peut pas être projeté sur elle. Android propose une conversion via `GeomagneticField`, en tenant compte de la validité du modèle embarqué. Desktop consomme la valeur et sa provenance au lieu de refaire une conversion historique. [API Android GeomagneticField](https://developer.android.com/reference/android/hardware/GeomagneticField)

Les tests JVM futurs couvriront la normalisation autour de zéro, une déclinaison est et ouest, les références incompatibles, les valeurs non finies et la conversion impossible. Les tests Android couvriront la rotation d’écran, le refus d’une mesure instable ou perturbée et l’absence de valeur sans confirmation. L’instantané retenu suit l’[ADR 052](052-lieux-et-lignes-de-tir.md).
