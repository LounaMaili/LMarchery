# ADR 013 — Profils indoor World Archery, édition du 27 janvier 2026

## Références vérifiées

Les PDF officiels suivants ont été consultés directement le 15 octobre 2026 :

- [Livre 2, édition 2026-01-27](https://extranet.worldarchery.sport/documents/index.php/Rules/Rule_Book_versions/2026-01-27/EN-Book_2_-_2026-01-27_Version.pdf),
  §7.2.2.1 à §7.2.2.3, pages imprimées 40 à 43 : dimensions, valeurs et limites.
- [Livre 3, édition 2026-01-27](https://extranet.worldarchery.sport/documents/index.php/Rules/Rule_Book_versions/2026-01-27/EN-Book_3_-_2026-01-27_Version.pdf),
  §12.4 et §12.5, pages imprimées 23 à 26 : compteurs et départages.

Cette référence est volontairement datée ; elle ne désigne pas automatiquement
la dernière édition publiée par World Archery.

## Décision

### Quatre définitions explicites de blasons complets

`WorldArcheryIndoorTargetProfiles` dans `core:scoring` publie quatre `TargetType`
immuables. `RULEBOOK_VERSION` vaut `2026-01-27`.

| Propriété | Division | Blason | Distance par défaut | Rayon 10 normalisé | Diamètre / rayon physique du 10 |
|-----------|----------|--------|---------------------|--------------------|--------------------------------|
| `recurve40` | Classique | 40 cm | 18 m | 0.10 | 4 cm / 2 cm |
| `compound40` | Poulies | 40 cm | 18 m | 0.05 | 2 cm / 1 cm |
| `recurve60` | Classique | 60 cm | 25 m | 0.10 | 6 cm / 3 cm |
| `compound60` | Poulies | 60 cm | 25 m | 0.05 | 3 cm / 1.5 cm |

Le rayon de référence reste celui du blason entier, normalisé à `1`.
En classique, 9 couvre `]0.10, 0.20]` ; en poulies, `]0.05, 0.20]`.
Les limites extérieures de 8 à 1 restent `0.30, 0.40, …, 1.00`.
À une limite exacte, la zone intérieure conserve le score, selon l'ADR 009.
Le trait imprimé à `0.10` ne sépare pas deux scores pour les poulies.

Le profil classique de 40 cm emploie aussi le diamètre du 10 prévu pour W1
par le §7.2.2.3 ; cela n'implémente pas l'ensemble des règles de cette division.
Ces profils décrivent la géométrie complète, sans prétendre couvrir les formats
d'épreuve qui imposent des faces multiples. Les distances sont des valeurs de
configuration par défaut ; aucune nouvelle validation d'épreuve n'est ajoutée.

### Choix du profil et X

Le code appelant choisit explicitement le profil classique ou poulies. Ni
`diameterCm`, ni `discipline`, ni `Session.isIndoor` ne déclenchent une règle
implicite. Les noms publiés indiquent division et édition ; aucun nouveau champ
obligatoire ni changement de `TargetType` n'est nécessaire.

Sur ces quatre définitions, `xRingEnabled = false`, `xRingLabel = ""` et
`innerTenRadiusNormalized = 0`. Ce dernier champ configure le X, pas le rayon
de la zone 10 : celui-ci vient de `TargetZone`. Un 10 poulies n'est jamais un X.
Les règles génériques X, suggestions et décisions des ADR 010 et 011 restent
inchangées ; elles utilisent les zones et indicateurs explicites du profil.

### Statistiques

Le Livre 3 distingue les compteurs de 10 et de 9 pour les courtes distances.
Hors exceptions du §12.5.2, un total égal est départagé par les 10 puis les 9.
Ces compteurs ne remplacent pas les barrages exigés dans les cas spécifiques.

`summarizeSessionScore` conserve `maxScoreCount` (donc les 10 sur ces profils)
et ajoute `nineCount`, compté sur `scoreFinal == 9` dans les seules volées validées,
comme les autres statistiques existantes. `xCount` reste générique et vaut zéro
pour les nouveaux profils. Le nouveau champ est ajouté en fin de résumé avec
une valeur par défaut zéro pour préserver les appels Kotlin existants ; la
fabrique de résumé le calcule toujours. Aucun classement, barrage ou pénalité
n'est implémenté. Décisions et repositionnements mettent à jour ces compteurs
par les valeurs finales, sans conserver un score ni un X devenu inapplicable.

### Identités publiées stables

Les UUID v4 ont été générés une fois puis inscrits comme constantes de définition :

| Profil | UUID |
|--------|------|
| `recurve40` | `5d98a517-4b7b-4308-8f4f-af3bd992a285` |
| `compound40` | `142a7cf4-1bee-427d-b351-c002c7003b98` |
| `recurve60` | `bb401e90-3818-4ed0-a926-06055b01ad25` |
| `compound60` | `39da4393-d4f9-45e4-86b7-36115ecd06ec` |

Chaque identité désigne cette définition publiée et cette édition. Une évolution
réglementaire doit créer une nouvelle identité de profil datée, en conservant
l'ancienne ; elle ne remplace pas silencieusement les zones sous un UUID connu.
Le catalogue et les listes de zones sont non modifiables. `TargetType.copy`
reste générique ; une variante personnalisée doit recevoir un autre UUID et ne
constitue pas une nouvelle définition officielle sous l'identité publiée.

Les impacts conservent leur contexte complet. Les contrôles existants rejettent
notamment un impact classique intégré dans une séance poulies, même avec le même
diamètre, et les remplacements qui réinterpréteraient sa cible.

### Compatibilité historique

`ClassicTargetFixtures` reste strictement inchangé : UUID, zones, X activé et
distances par défaut, dont le 60 cm à 18 m. Ce sont des fixtures génériques
historiques, sans distinction réglementaire de division indoor. Leur libellé
ou diamètre ne suffit pas à en faire les nouveaux profils officiels. Les séances
qui les utilisent ne sont ni converties ni réinterprétées automatiquement.

## Limites

Pas de blason réduit, triple/double face, gestion de spots, sélection UI,
pénalité, migration de séance, Room ni import/export opérationnel. La future
persistance devra contrôler les définitions associées aux identités publiées et
rejeter un contenu contradictoire ; les conflits et migrations des anciens
fichiers restent à définir. Le jugement du cordon et le contrat de repositionnement
de l'ADR 012 ne changent pas. Les couleurs hexadécimales restent des approximations
d'affichage, sans certification des couleurs ou tolérances de fabrication.

*15 octobre 2026*
