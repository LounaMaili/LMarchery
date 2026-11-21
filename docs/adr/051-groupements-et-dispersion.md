# ADR 051 — Groupements et dispersion des impacts archivés

Statut proposé. Le contrat est cohérent avec le repère de l’[ADR 002](002-coordonnees-normalisees.md), les configurations exactes de l’[ADR 034](034-scoring-par-volee-statistiques-mixtes.md), les annulations de l’[ADR 048](048-annulation-logique-durable-impacts.md) et la proposition de l’ADR 003 Desktop.

## Décision

Le package JVM `com.lmarchery.core.scoring.grouping` expose deux fonctions pures.

- `calculateEndGrouping(context, missPolicy)` renvoie un `EndGrouping` pour un
  `EndScoringContext` valide, avec son UUID, son index, son statut, sa configuration
  exacte, le filtre et les effectifs. `normalized` contient le centrage et la dispersion.
- `summarizeEndGroupings(contexts, missPolicy)` renvoie des `EndGroupingSeries`
  dont les listes sont immuables. Chaque série couvre une configuration exacte,
  un statut et le filtre demandé.

Les calculs lisent les points courants des `End.activeImpacts`. Une annulation
retire toujours l’impact et ne compte pas parmi les M inclus ou exclus. La
politique `INCLUDE_MISSES` est la valeur par défaut. `EXCLUDE_MISSES` retire un
impact actif si sa décision finale indique `isMiss`. Un M décidé à l’intérieur
est donc filtrable. Une flèche créditée par décision manuelle peut rester
active hors du blason. Les points hors blason gardent leurs coordonnées et
les positions historiques restent dans leurs traces. Le calcul ne résout, ne
recalcule ni ne modifie les scores et décisions.

## Mesures et unités

Le centre du blason est l’origine, le bord extérieur est de rayon 1,
`x` augmente vers la droite et `y` vers le bas. Le centrage est distinct de la
dispersion. Un nuage serré peut avoir un centre éloigné de l’origine.

| Mesure | Définition pour les `n` impacts retenus |
|---|---|
| Effectif | Nombre de points retenus. Deux impacts au même endroit comptent deux fois. |
| `center.x`, `center.y` | Moyennes arithmétiques des deux axes et décalages signés par rapport à l’origine. |
| `center.distanceFromTargetCenter` | Distance euclidienne du centre moyen à l’origine. |
| `dispersion.standardDeviationX/Y` | Écarts-types de population par axe. Racine de la somme des écarts au carré au centre moyen, divisée par `n` sous la racine. |
| `dispersion.meanRadius` | Moyenne des distances euclidiennes des impacts au centre moyen. |
| `dispersion.diameter` | Plus grande distance euclidienne entre deux impacts retenus. |

À zéro impact, `center` et `dispersion` sont absents. À un impact,
le centrage est défini et `dispersion` reste absent. À partir de deux impacts
la dispersion est définie. Elle vaut zéro pour des points tous coïncidents.
L’interface peut afficher un indicateur d’effectif insuffisant pour les valeurs
absentes.

`measurementsIn(GroupingUnit.CENTIMETERS)` multiplie les mesures de longueur
par le rayon nominal `diameterCm / 2.0` du profil enregistré de la volée.
`MILLIMETERS` multiplie ce rayon par 10. La conversion se fait depuis le résultat
normalisé et conserve les absences, sans arrondi d’affichage. Les valeurs physiques
décrivent les coordonnées archivées sur ce blason nominal. Une mesure
photographique indépendante demanderait sa propre calibration.

## Algorithme et précision

Les `Float` archivés sont élargis exactement en `Double`. Les zéros signés ont
le même sens géométrique, sans changement de leurs bits dans l’archive. Un tri
lexicographique des points stabilise l’ordre des opérations pour toute permutation
des impacts. Le centre et les sommes d’écarts utilisent une sommation compensée
de Neumaier. Une deuxième passe calcule les écarts par rapport au centre,
ce qui évite de soustraire deux moments bruts presque égaux. Les différences,
leurs carrés, les sommes pour des effectifs `Int` et les conversions tenant compte
des diamètres `Int` restent dans l’intervalle fini représentable par `Double`
à partir des points admis par le domaine. Les mesures sont arrondies par
l’arithmétique `Double`.

Pour le diamètre, seules les positions distinctes servent à construire
l’enveloppe convexe par chaîne monotone. Des points collinéaires intermédiaires
peuvent disparaître de l’enveloppe sans modifier le diamètre. Dans le cas
totalement collinéaire, le diamètre est la distance des extrémités. Pour un
polygone strictement convexe, les antipodes parcourent l’enveloppe en avançant
sur l’arête opposée. Les deux sommets opposés sont examinés en cas d’égalité.
Cette recherche couvre une paire la plus éloignée sans échantillonnage ni scan
quadratique. Le coût est `O(n log n)` pour le tri, `O(n)` après tri et `O(n)` en
mémoire. [Andrew, chaîne monotone](https://doi.org/10.1016/0020-0190(79)90072-3) ·
[Toussaint, antipodes](https://www-cgrl.cs.mcgill.ca/~godfried/publications/calipers.pdf)

Le signe de chaque déterminant est vérifié par une borne d’erreur conservatrice
en `Double`. Les valeurs dans la borne sont recalculées exactement avec
`BigDecimal` construit depuis la valeur binaire. Le test de signe reste ainsi
exact pour les coordonnées `Float` archivées, y compris en cas d’annulation
numérique près d’une ligne. La longueur finale est restituée en `Double`.
[Shewchuk, filtrage numérique](https://people.eecs.berkeley.edu/~jrs/papers/robust-predicates.pdf)

## Séries et intégration

L’égalité complète d’`EndConfiguration` et l’`EndStatus` enregistré définissent
un groupe. Le tri canonique `(index, UUID)` conserve chaque index archivé et
fixe l’ordre des groupes à leur première occurrence. Une volée vide ou filtrée
reste dans sa série. Deux volées de même index peuvent coexister avec des UUID
différents. La validation des collections reprend les règles du moteur mixte
pour les identités et les définitions de profils. Des archives indépendantes
s’analysent par appels distincts, avec leur provenance gérée dans le consommateur.
Aucun indice global de régularité ou de tendance n’est dérivé.

Android peut utiliser `configuredSessionScoringContexts` pour construire une
série. Desktop peut réutiliser le contexte déjà associé à la volée analysée,
ou créer un `EndScoringContext` à partir de son `End` et d’un
`EndConfigurationBinding` vers la configuration exacte de cette volée. Pour V1
et V2, employer la configuration homogène et l’édition présentes dans l’archive.
Construire les séries et le rendu en dehors du fil UI lorsque les volumes le
justifient. Les résultats sont des projections de lecture sans changement de
contrat JSON V1 à V4, de Room ou du score. L’activation des vues et la mise à jour
du sous-module Desktop feront l’objet d’un lot distinct.
