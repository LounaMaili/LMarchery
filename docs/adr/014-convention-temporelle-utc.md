# ADR 014 — Instants UTC et date civile de séance

## Contexte et inventaire

`LocalDateTime` ne suffit pas à ordonner des événements entre appareils ni lors
d'un changement d'heure. Il représente pourtant correctement la composante
civile de `Session.date`, qui n'est ni une date seule ni un alias de `startedAt`.
Les ADR 008, 011 et 012 ont établi des invariants à conserver intégralement.

L'audit des usages Kotlin trouve :

| Emplacement | Usages migrés |
|-------------|---------------|
| `Session`, `End`, `ArrowImpact` | Création/révision, début/fin, validation, méthodes métier, copies et décomposition |
| `ScoreDecisionRequest`, `ScoreDecisionProvenance` | `decidedAt` des décisions manuelles |
| `ImpactReposition` | `impactCreatedAt`, `previousUpdatedAt`, `changedAt` et décisions conservées |
| `scoreArrowImpact` | Création et révision explicites |
| `ScoringDemoState.addImpact` | Seul point d'entrée de production créant automatiquement un timestamp |
| Tests de modèle, scoring et démonstration | Horodatages synthétiques et dates civiles de fixtures |

Aucun autre événement `LocalDateTime` ne subsiste. Ce type reste dans la valeur
civile dédiée et ses tests. Les profils World Archery et règles de score ne changent pas.

## Décision

### Événements : Instant

Tous les événements et révisions utilisent `java.time.Instant` : `createdAt`,
`updatedAt`, `startedAt`, `endedAt`, `validatedAt`, `decidedAt`, `changedAt` et
dates conservées dans les traces. Comparaisons, maximums de parents, égalité et
contrôles d'antidatage portent sur des instants, jamais des heures locales ni
des chaînes ISO comparées lexicalement. La précision nanoseconde est conservée.

Les invariants existants restent inchangés : création préservée, révision non
décroissante (égalité autorisée), dates d'événements cohérentes avec leurs enfants,
rejet des révisions obsolètes, décisions antidatées et pertes de traces. Les
constructeurs et copies vérifient les mêmes contraintes. Aucune horloge incohérente
n'est réparée ou artificiellement incrémentée.

Les méthodes du domaine reçoivent leurs `Instant` explicitement. La démonstration
garde `addImpact(point)` et accepte `Clock` pour les tests ; son défaut est
`Clock.systemUTC()` et la création utilise `clock.instant()`. Elle ne choisit
aucun fuseau système. Aucun service d'horloge Android ni dépendance n'est ajouté.

### Date civile : SessionDateTime

`Session.date` conserve sa signification de date **et heure civiles** au moyen
d'une valeur immuable `SessionDateTime` regroupant :

- `localDateTime: LocalDateTime`, sans conversion UTC implicite ;
- `zoneId: ZoneId`, identifiant enregistré IANA (`Europe/Paris`, `UTC`, etc.) ;
- `offset: ZoneOffset`, cohérent avec ce couple civil/fuseau.

Cette rupture de type interne conserve l'information civile et lui ajoute son
contexte explicite. Elle ne transforme pas la date de séance en événement et
n'impose aucune égalité avec son début, sa création ou sa fin. Aucun parcours
de planification n'est introduit.

`SessionDateTime.of(local, zone, offset?)` n'utilise jamais le fuseau par défaut :
si un seul offset est valide, il est conservé ; une heure inexistante est rejetée ;
une heure répétée exige un des offsets valides choisi explicitement. Un offset
incompatible est rejeté au constructeur et par `copy`. Un offset seul ne remplace
pas un identifiant IANA d'origine.

`fromInstant(instant, zone)` fournit une autre entrée non ambiguë et conserve
l'offset effectif. Pour reconstruire un triplet civil reçu, utiliser le constructeur
ou `of(local, zone, offset)` et vérifier les trois valeurs : ne pas employer
`fromInstant` pour ignorer une heure locale ou un offset contradictoire du fichier.
`toInstant()` utilise l'offset stocké. `atZone(viewZone)` produit
une vue de consultation de ce même instant ; la valeur civile d'origine reste
intacte, même si la consultation change de jour. Les règles sont fondées sur
[`ZoneRules.getValidOffsets`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/zone/ZoneRules.html#getValidOffsets(java.time.LocalDateTime)),
sans le déplacement automatique des heures inexistantes de `LocalDateTime.atZone`.

### Conversions et format prévu d'export

`UtcTimestamps.parse` lit une date ISO 8601 avec offset explicite, via le parseur
strict `ISO_OFFSET_DATE_TIME`, puis produit un `Instant`. Ainsi
`2026-04-28T18:02:00+02:00`, `17:02:00+01:00` à la même date et `16:02:00Z`
désignent le même instant. L'export prévu écrit systématiquement `Z` :
`2026-04-28T16:02:00Z`, avec fractions de seconde conservées si présentes.

Une date sans offset, une date invalide, une heure `24:00`, une seconde intercalaire
écrite `:60`, un offset inconnu négatif zéro (`-00:00`) ou un format ambigu sont
rejetés explicitement. Pas de trim, correction calendaire ni inférence UTC.
Le formateur et le parseur partagent la plage calendaire d'`OffsetDateTime` ;
un `Instant` extrême non représentable dans cette plage échoue explicitement.
Ce sont des conversions temporelles pures, pas un import/export de fichier.

La date civile est prévue comme un triplet JSON `localDateTime`, `zoneId`,
`offset`, conservé tel quel. Un offset d'événement permet de trouver son instant,
mais ne suffit pas à retrouver le fuseau IANA d'une séance.

### Format Room prévu, sans implémentation

Pour les `Instant`, conserver le couple d'entiers `epochSecond: Long` et
`nano: Int` (`0..999999999`), afin de ne pas perdre les nanosecondes par une
conversion implicite en millisecondes ou un nombre flottant. Comparer les couples
dans cet ordre lors des futures requêtes, puis reconstruire un `Instant` validé.
La date civile conserve séparément la date/heure ISO locale, le nom IANA et
l'offset choisi. Aucun schéma, convertisseur Room ou migration opérationnelle
n'est ajouté.

### Compatibilité et données historiques

Tous les appels du dépôt sont migrés. Les anciennes signatures événementielles
`LocalDateTime` et `Session.date: LocalDateTime` ne sont pas conservées comme
adaptateurs : elles ne pourraient fournir le fuseau absent de manière fiable.
Les fixtures temporelles artificielles utilisent désormais des littéraux `Z`
explicites et des dates civiles en `UTC`. Leur heure numérique de test est une
nouvelle référence synthétique UTC, **pas** l'interprétation rétrospective de
données utilisateur. Les nouveaux exemples français choisissent explicitement
`Europe/Paris` ; ce choix ne constitue pas une règle de migration.

Une ancienne date sans offset/fuseau doit rester identifiée comme non résolue
hors des modèles d'événements validés. La future migration devra demander ou
recevoir une provenance fiable, puis un choix explicite en cas d'heure répétée.
Une heure inexistante ou un contexte manquant ne sont pas réparés automatiquement.
Il n'existe aucune base utilisateur à migrer dans cette PR.

## Limites avant Room

UTC donne un référentiel commun, pas une horloge distribuée fiable. Un appareil
peut avancer/reculer, deux modifications peuvent partager la même date, et un
`updatedAt` supérieur ne prouve pas la causalité entre appareils. La politique
de conflits devra couvrir les égalités de dates avec contenus différents et les
horloges décalées ; aucune synchronisation ni résolution de conflits n'est ajoutée.

Les changements de règles IANA peuvent rendre un ancien triplet incohérent avec
une nouvelle base de fuseaux. Il faudra signaler ce conflit, conserver les valeurs
reçues et définir sa résolution avant Room, sans réécrire silencieusement l'offset
original. Versionnement des futurs fichiers et traitement des anciennes données
sans fuseau restent à spécifier. Aucun écran ni changement de dépendance,
scoring, jugement, profil ou contrat de repositionnement n'est introduit.

*15 octobre 2026*
