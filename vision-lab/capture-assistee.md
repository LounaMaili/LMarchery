# Étude CameraX — Capture assistée future

Prévoir un écran de capture optionnel, distinct de l’appareil photo système de l’[ADR 050](../docs/adr/050-saisie-photo-manuelle.md). Pour le premier essai, conserver l’aperçu et les aides de cadrage hors du parcours de score.

## Coordonnées et identité d’image

1. Regrouper `Preview`, `ImageAnalysis` et `ImageCapture` sous un même `ViewPort` CameraX. Associer chaque résultat d’analyse à sa génération de caméra, ses dimensions, son `cropRect`, sa rotation, son miroir éventuel et son horodatage.
2. Rétablir le repère du buffer à partir des coordonnées analytiques redimensionnées ou du recadrage du modèle. Passer du buffer d’analyse au repère aperçu avec les `OutputTransform` correspondant à cette génération et le `CoordinateTransform` du même `ViewPort`. La mise à l’échelle, les marges et le miroir sont des transformations d’affichage.
3. Pour la photo finale, prendre en compte son cadrage et la rotation EXIF lors du décodage. Recalculer ou contrôler les candidats sur ce JPEG et son identifiant. Un aperçu peut précéder la capture ou différer par le zoom, l’orientation ou le recadrage. Comparer d’abord les points sur une mire de référence sous ces changements.
4. Présenter les points et leur incertitude dans le repère de la photo orientée. Appliquer une conversion vers les coordonnées du blason seulement après validation de l’échelle et du plan. Le moteur de score et la confirmation explicite restent les autorités existantes. [Transformation CameraX](https://developer.android.com/media/camera/camerax/transform-output) · [CoordinateTransform](https://developer.android.com/reference/androidx/camera/view/transform/CoordinateTransform) · [Règles LMarchery](../docs/regles-score.md)

## Fluidité et abstention

Un analyseur borné, exécuté hors du fil UI avec `STRATEGY_KEEP_ONLY_LATEST`, peut sauter des trames et doit fermer chaque `ImageProxy`, y compris après erreur. Traiter à une résolution et une cadence mesurées. Invalider les résultats lors d’un changement de zoom, de caméra, de rotation, de contexte ou d’un délai dépassé. La capture finale reste indépendante de l’inférence.

Un contour stable peut apparaître après plusieurs observations concordantes. Un suivi spatial à courte durée et deux seuils d’apparition et de disparition évitent le clignotement. Montrer un point unique seulement si l’entrée est distinguable de l’autre extrémité. En cas de doute, afficher une aide courte de cadrage ou un axe sans point et laisser la photo accessible en saisie manuelle. Diagnostic, vue stabilisée, photo validée et écriture métier sont quatre étapes distinctes. [ImageAnalysis](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis)

La première recette CameraX doit vérifier la correspondance sur une mire sur plusieurs systèmes, puis mesurer latence, fraîcheur, stabilisation et mémoire sur appareil. Elle ne nécessite aucun transfert au score.


## Premier prototype Android isolé

Utiliser une variante de laboratoire avec sa propre identité et ses propres fichiers de test. Le parcours actuel de l’ADR 050 reste le choix par défaut et peut toujours déléguer la prise de vue à Google Camera.

- **Aperçu rapide** — Analyser une image réduite à cadence bornée pour estimer le contour, la netteté, l’angle et les zones utiles. Dessiner des cadres fins autour des régions candidates. Conserver un cadre seulement si le suivi le rattache à la même région sur des trames fraîches. Masquer un point dès que l’entrée devient incertaine.
- **Analyse de la photo capturée** — Travailler à partir du JPEG orienté et des régions à résolution suffisante. Refaire l’identification du profil et des points sur cette image. Consigner séparément le temps du décodage, du repérage, de l’inférence et de la projection. L’utilisateur revoit les propositions et décide de chaque point.
- **Objectifs à mesurer** — Viser moins de 10 Mo pour le modèle quantifié et moins de 500 ms par photo, de la lecture du JPEG jusqu’aux propositions affichables, en détaillant aussi les sous-mesures par étape. Relever médiane et p95, mémoire, rappel, fausses propositions et comportement sur plusieurs orientations. Ces objectifs restent à vérifier sur un téléphone cible.

Commencer par la mire et des fichiers synthétiques. La qualification suivante exige un corpus réel qui sépare les contacts visibles, masqués et proches d’un cordon, ainsi que des témoins vides.
