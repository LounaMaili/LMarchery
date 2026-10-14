# ADR 010 — Suggestions géométriques et décisions de cordon séparées

> Suite : l'[ADR 011](011-decisions-score-explicites.md) supprime la promotion
> automatique et ajoute les décisions contextualisées. Le maintien de l'ancienne
> résolution décrit ici correspond au périmètre de la PR #8.

## Contexte

Les champs historiques `isNearCordon` et `cordonZoneAbove` détectent certaines
limites d'un seul côté, sans le X ni le bord/M. En mode `COMPETITION`,
`resolveFinalScore` utilise encore cette proximité UX pour promouvoir
automatiquement le score. Cette promotion reste un défaut connu à corriger dans
la prochaine PR ; elle ne prouve aucun contact physique du tube avec le trait.

La préparation des futures décisions manuelles nécessite une recherche complète
et symétrique, sans changer simultanément le contrat de résolution historique.

## Décision

Une API Kotlin pure est ajoutée dans `core:scoring` :

```kotlin
fun findBoundaryHints(
    targetType: TargetType,
    point: NormalizedPoint,
    touchToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
): List<BoundaryHint>
```

Elle reçoit directement le point validé, plutôt qu'un `ScoreResult` ou un objet
de géométrie dont le rayon, la zone et les indicateurs pourraient se contredire.
Elle n'appelle ni `calculateScore` ni `resolveFinalScore` et n'est pas appelée
par le chemin historique. Seul le calcul numérique existant du rayon est
mutualisé dans une fonction interne, avec le même résultat public en `Float`.

Chaque suggestion contient :

- `type` : `SCORE_ZONES`, `X_RING` (X/non-X, habituellement X/10) ou `TARGET_EDGE` ;
- `radiusNormalized` : rayon de la limite dans la définition de cible ;
- `inner` et `outer` : valeurs immédiatement du côté du centre et du côté de
  l'extérieur, avec `score` et `isX` distincts. `isMiss` est dérivé de `score == 0`.
  Le X n'ajoute aucun point et le bord utilise le score périphérique réel ;
- `distanceToBoundaryNormalized` : écart radial absolu, en `Double`.

Les limites sont construites depuis les zones réellement voisines ; les scores
ne sont pas supposés consécutifs. Les côtés sont lus dans les définitions, sans
échantillonnage par epsilon, même pour une zone d'une largeur d'un seul `Float`.
La limite X n'existe que si elle est activée. Si X et une autre limite coïncident,
deux suggestions distinctes par leur type décrivent les mêmes côtés physiques.
Par exemple, un X couvrant toute la zone 10 peut donner 10/X vers 9/non-X ;
aucune région 10/non-X de largeur nulle n'est inventée.

### Convention numérique et ordre

La tolérance UX par défaut reste provisoirement `0.015f`. Elle doit être finie
et non négative ; sinon l'API lève `IllegalArgumentException`.

Le rayon suit l'[ADR 009](009-integrite-entrees-scoring.md) : calcul intermédiaire
en `Double`, résultat arrondi en `Float`, erreur si le rayon ne peut pas être
représenté dans la plage finie de `Float`. Cela conserve les conventions du
scoring géométrique, y compris pour les diagonales.

Les deux rayons `Float` sont convertis en `Double` **avant** soustraction.
Une limite est retenue si `abs(r - rayonLimite) <= tolérance`, depuis chacun de
ses côtés. Aucun epsilon supplémentaire ne modifie ce seuil. À tolérance nulle,
seule une égalité exacte du rayon public est retenue. L'écart de sortie reste
en `Double` pour ne pas arrondir le résultat de la comparaison en `Float`.

Toutes les limites couvertes sont restituées, triées par distance croissante,
puis rayon croissant, puis ordre des types : `SCORE_ZONES`, `X_RING`, `TARGET_EDGE`.
L'ordre initial de la liste de zones n'influence pas le résultat.

## Conséquences et limites

- Une suggestion n'est ni une preuve réglementaire ni une décision finale.
  Elle ne choisit aucun côté et ne modifie aucun score ou indicateur.
- `calculateScore`, ses champs historiques, `resolveFinalScore`, les modes,
  les modèles de séance et les fixtures conservent leur comportement. La
  suppression de la promotion automatique en `COMPETITION` sera une PR distincte.
- Les tests de seuil utilisent des rayons et tolérances binaires exacts ainsi
  que `Math.nextUp` / `Math.nextDown`, sans assertions sur des additions décimales
  supposées exactes en `Float`.
- Seuls les profils concentriques complets validés par `TargetType` sont
  concernés. Aucun support des blasons réduits, multispots, diamètre de tube,
  décision manuelle, persistance, import/export ou intégration UI n'est ajouté.

*15 octobre 2026*
