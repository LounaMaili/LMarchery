# ADR 057 — Répartition géométrique par zones du blason

Statut proposé. L’API JVM `calculateTargetZoneDistribution(contexts, missPolicy)`
complète les sélections et fréquences de l’[ADR 056](056-statistiques-selection-et-spatiales.md)
dans le package `com.lmarchery.core.scoring.grouping`. Elle fournit les
effectifs par zone pour une intégration Desktop ultérieure.

## Sélection et provenance

Les `EndScoringContext` fournis partagent une configuration exacte et un
statut archivé communs, y compris pour les volées vides. La même validation
que pour la grille et les groupements rejette les UUID répétés et les
collisions entre types d’entités. Le consommateur conserve la provenance
et appelle l’API séparément pour chaque archive indépendante.

Le calcul utilise la position courante de chaque impact actif. Un
repositionnement est donc pris en compte à sa position d’arrivée. Les
annulations ont un effectif distinct. Le filtre porte sur le M final
`isMiss` et la politique par défaut inclut ces impacts. Un M final peut
contribuer à une zone intérieure. Un impact crédité par jugement humain
peut contribuer à l’effectif extérieur.

## Zones, X et dénominateurs

- `zones` restitue chaque `TargetZone` exacte du profil, du centre au bord,
  avec son score géométrique, ses deux bornes radiales normalisées et sa
  fréquence. Les zones vides figurent dans le résultat. Les valeurs peuvent
  être non consécutives et le score central peut différer de 10.
- `xRing` existe pour un profil avec X activé. Il indique le libellé, le
  rayon normalisé et la fréquence à l’intérieur du X. Cet effectif est déjà
  inclus dans la zone centrale. Si le rayon X couvre toute cette zone, les
  deux effectifs sont égaux.
- `insideFace` totalise les zones. `outsideFace` et `outsideImpacts` décrivent
  l’extérieur. La liste conserve UUID de volée, UUID d’impact et coordonnées
  d’origine dans l’ordre de parcours. La somme des zones et du hors blason
  égale l’effectif retenu. Les deux listes retournées sont immuables.
- `counts` indique les impacts retenus, M inclus, M exclus et annulations.
  `selectedEndCount` inclut les volées vides. Chaque `SpatialFrequency`
  donne son effectif et son dénominateur, tous les impacts retenus hors
  blason compris. À dénominateur nul, le comptage vaut zéro et le pourcentage
  est absent. Sans volée, le profil est inconnu et la liste de zones est vide.
  Avec une volée vide ou entièrement filtrée, le profil et ses zones restent
  disponibles avec des comptages nuls.

## Frontières et scores archivés

La zone centrale couvre `[0, r₀]`. Les suivantes couvrent `(rᵢ₋₁, rᵢ]`.
La frontière appartient à la zone intérieure, le bord de rayon 1 appartient
au blason et le X inclut sa frontière. Les coordonnées et rayons `Float`
sont interprétés selon leur valeur binaire exacte. Leur élargissement
en `Double` donne des carrés exacts. Pour comparer deux carrés au carré
du rayon, on utilise leur maximum. En dessous de la moitié du seuil, les
deux termes tiennent dans le cercle. Entre la moitié et le seuil, la
soustraction du maximum au seuil est exacte. Au-delà du seuil, le point est
extérieur. Le même prédicat sert au bord de la grille, y compris lorsque
`(1f, Float.MIN_VALUE)` est géométriquement extérieur.

Cette convention prolonge celle de l’ADR 056 à tous les rayons du profil.
Le score calculé archivé suit le rayon arrondi de son contrat
([règles de score](../regles-score.md)), ce qui peut classer un point
différemment près d’une limite. Le score final et le X final suivent la
décision archivée. Un jugement de cordon modifie la décision à position
constante. La répartition des zones dépend de la position et, pour inclure
ou exclure l’impact, de la politique M.

## Coût et intégration

Pour `n` impacts archivés, `p` impacts retenus dans le blason, `v` volées et
`z` zones, la validation et le classement coûtent
`O(n + v + z log z + p log z)` en temps. Les compteurs prennent `O(z)`.
La validation utilise `O(n + v)` en mémoire. Le rapport conserve les
positions extérieures et les définitions des zones. Les tests couvrent un
oracle indépendant à arithmétique exacte et une sélection synthétique de
65 536 impacts.

L’API est une projection JVM pure. Room, les formats JSON V1 à V4 et les
parcours Android gardent leurs contrats. L’intégration dans l’explorateur
Desktop fera l’objet d’un lot distinct.
