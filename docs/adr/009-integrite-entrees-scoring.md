# ADR 009 — Intégrité des entrées du scoring

## Contexte

Les coordonnées non finies, les multiplications intermédiaires en `Float` et les
définitions de zones incomplètes peuvent produire une erreur tardive ou une
distance infinie. Une liste mutable passée à `TargetType` peut aussi changer une
cible déjà validée et son empreinte dans une collection utilisant `hashCode`.

## Décision

- `NormalizedPoint` accepte toutes les coordonnées finies, y compris hors du
  disque. Le calcul du rayon utilise `Double` avant retour en `Float`. Un rayon
  supérieur à `Float.MAX_VALUE` provoque `IllegalArgumentException`, sans
  plafonnement. Les distances de `ScoreResult` et `ArrowImpact` sont finies et
  non négatives, même en reconstruction ou copie.
- Les zones ont une largeur positive et des rayons finis dans `[0, 1]`.
  `TargetType` vérifie la couverture complète du centre au bord et l'égalité
  exacte des limites voisines, sans tolérance de raccordement. L'ordre fourni
  reste conservé ; les contrôles utilisent un classement radial.
- Les scores de zone sont positifs, dans les bornes déclarées, et strictement
  décroissants vers l'extérieur. La zone centrale vaut `maxScore`. La couverture
  complète concerne les rayons, pas chaque entier de `minScore..maxScore`.
  `minScore = 0` reste possible pour inclure le miss dans la borne déclarée,
  mais le miss n'est pas une zone. Le rayon X est fini et contenu dans la zone
  maximale ; il est strictement positif si activé. Un X désactivé peut conserver
  sa configuration.
- `TargetType` devient une valeur immuable avec une copie défensive non
  modifiable des zones au constructeur comme par `copy`. Le constructeur,
  les paramètres de copie, `equals`, `hashCode`, `toString` et les 13 composants
  restent disponibles. Une `data class` privée porte l'égalité par contenu,
  selon le principe de l'[ADR 008](008-integrite-horodatages-domaine.md).
  La classe publique n'est plus une `data class` pour empêcher la conservation
  de la liste mutable par le constructeur ou la copie générée.

## Limites et conséquences

Les quatre fixtures classiques restent inchangées. À une limite exacte, le
rayon public en `Float` appartient toujours à la zone intérieure, bord `1`
inclus ; un rayon supérieur à `1` est un miss. La précision reste celle d'un
`Float` public : cette décision ne prétend pas mesurer le contact d'un fût.

Ces contrôles sont structurels et ne valident pas un profil réglementaire. Les
blasons réduits et multispots nécessiteront une définition explicite du rayon de
référence, de la surface active et des spots ; une couverture tronquée est rejetée.
Les futurs adaptateurs Room, import ou reconnaissance devront construire ces
valeurs validées. Aucun adaptateur ni schéma de stockage n'est ajouté ici.

Le recalcul d'un impact reconstruit, les scores manuels autorisés, les modes,
la détection/résolution du cordon et les indicateurs finaux X/M restent séparés.
En particulier, la promotion actuelle à partir d'une tolérance UX n'est pas une
règle métier validée par cette décision.

*14 octobre 2026*
