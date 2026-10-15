# ADR 008 — Intégrité des horodatages du domaine

## Contexte

La fusion future compare `updatedAt`. Les premières méthodes métier peuvent
antidater une valeur ou abaisser la date d'un parent lorsqu'un enfant plus ancien
est intégré. Les constructeurs acceptent aussi des parents plus anciens que leurs
enfants ou des validations/clôtures antérieures aux données qu'elles couvrent.

Un contrôle limité aux méthodes métier ne suffit pas : la copie automatique d'une
`data class` ne connaît pas la dernière date de sa source. Une collection mutable
fournie au constructeur peut aussi contourner les invariants après construction.

## Décision

Les trois valeurs `ArrowImpact`, `End` et `Session` exposent des constructeurs
validant la cohérence de l'instantané et une méthode `copy` contrôlant son
historique par rapport à la source. Ce sont désormais des classes immuables ; une
`data class` privée porte leur égalité par contenu. Les paramètres publics,
`equals`, `hashCode`, `toString` et les opérateurs de décomposition sont conservés.
Les listes de descendants sont copiées défensivement et non modifiables.

Les règles sont :

1. `createdAt <= updatedAt`, sans modification de `createdAt` lors des révisions.
2. Création de chaque enfant à partir de celle de son parent, et `updatedAt`
   parent supérieur ou égal à celui de tous ses enfants.
3. Validation couvrant les impacts ; clôture couvrant les volées ; dates des
   événements inférieures ou égales au `updatedAt` de l'objet.
4. Rejet d'un `start`, `complete`, `validate`, `withFinalScore` ou d'une transition
   par `copy` antérieur à la dernière modification. L'égalité est autorisée : la
   monotonie est non décroissante, sans incrément artificiel de l'horloge.
5. Pour `withImpact` / `withEnd`, date d'intégration au moins égale au `updatedAt`
   de l'enfant et à la création du parent. Le résultat conserve le maximum entre
   cette date et la dernière modification du parent.
6. Pour un enfant existant identifié par UUID, rejet d'une révision obsolète ou
   d'un `createdAt` différent. Ce contrôle descend jusqu'aux impacts lorsqu'une
   volée complète est remplacée, par méthode métier ou par `copy`.

Le seul maximum automatique concerne l'intégration dans un parent. Il ne répare
pas une date d'événement impossible, un instantané incohérent ou une révision
obsolète. Ces situations lèvent `IllegalArgumentException` avec une cause explicite.

La propagation est composée explicitement : `withFinalScore` → `withImpact` →
`withEnd`. Les anciennes valeurs sont conservées, aucun objet parent n'est muté
par effet de bord. Les instantanés reconstruits peuvent porter une modification
de métadonnées après validation ou clôture, mais leurs enfants ne dépassent pas
la date de l'événement qui les valide ou clôture.

## Conséquences

- Le cas 10 h → modification à 10 h 10 → validation à 10 h 05 est rejeté.
- Intégrer un nouvel enfant plus ancien ne fait reculer ni le parent ni ses autres enfants.
- Le contrôle de `copy` demande du code explicite, couvert par des tests de
  régression et de compatibilité de l'égalité/décomposition.
- La future couche Room/import doit valider ses instantanés puis résoudre les
  conflits avant l'intégration. Aucun stockage ou algorithme d'import n'est ajouté.
- L'[ADR 014](014-convention-temporelle-utc.md) applique désormais ces garanties
  sur des `Instant`, en distinguant la date civile de séance et son fuseau.

*14 octobre 2026*
