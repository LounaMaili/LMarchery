# Rapport C — Apprentissage et abstention R4

Le réseau R3 de référence conserve ses trois sorties, masque de tube, carte de contact et visibilité. Le fichier FP32 est inchangé, 296 831 octets, SHA-256 `fd4c8490eed9ad37602479bcbee6b8d8d7077730fc48aaed95b8c3e0fb03cece`. Les seuils R3 restent 0,65 en global et 0,42 en fenêtres. Aucun modèle R4 n’a été entraîné. Le [corpus réel](r4-corpus.md) ne fournit aucun groupe d’entraînement admis et la seconde lecture du test public reste en attente.

L’expérience R4 ajoute un filtre spatial aux propositions R3 déjà au-dessus du seuil. Pour chaque point, il exige une extrémité proche et unique, du support de segmentation continu du côté du tube et peu de support de l’autre côté. Le bord de l’image, les extrémités concurrentes, les discontinuités et les axes qui traversent le candidat conduisent à un refus explicite. Le filtre renvoie un sous-ensemble exact des positions R3. Il ne déplace aucun point, ne réévalue pas les coordonnées et n’attribue aucun score sportif. Ses paramètres ont été fixés avant le comparatif. L’épreuve personnelle R3 n’a servi ni à les définir ni à les régler. Les photographies personnelles sont exclues du comparatif.

## Résultats tenus à l’écart

L’appariement un à un accepte un écart maximal de 10 px dans le repère de travail plafonné à 704 px. Les erreurs ci-dessous sont ensuite mesurées dans les pixels de l’original. Les millimètres ne sont disponibles que pour le rendu synthétique dont la projection est connue. Les valeurs de confiance ordonnent les candidats et ne sont pas des probabilités étalonnées.

| Lot et mode | Points annotés appariés R3 → R4 | Propositions | Faux selon l’annotation | Témoins vides avec abstention |
|---|---:|---:|---:|---:|
| 40 synthétiques, global | 1/30 → 0/30 | 3 → 0 | 2 → 0 | 15/15 → 15/15 |
| 40 synthétiques, fenêtres 704 px | 4/30 → 3/30 | 49 → 17 | 37 → 10 | 7/15 → 12/15 |
| 5 clichés publics R4, global | 0/4 → 0/4 | 0 → 0 | 0 → 0 | 2/2 → 2/2 |
| 5 clichés publics R4, fenêtres 704 px | 0/4 → 0/4 | 30 → 8 | 10 → 3 | 0/2 → 0/2 |
| 4 clichés historiques R3, global | 0/4 → 0/4 | 0 → 0 | 0 → 0 | 1/1 → 1/1 |
| 4 clichés historiques R3, fenêtres 704 px | 1/4 → 1/4 | 13 → 5 | 5 → 2 | 0/1 → 0/1 |

Les quatre contacts du lot public R4 sont sur le même cliché. L’annotation partielle et la première lecture empêchent d’extrapoler un rappel ou une précision sur photos réelles. Dans ce lot, les 20 autres propositions R3 en fenêtres, puis 5 R4, sont non appariées à un contact visible et hors témoins vides. Elles comprennent les proximités d’axes masqués, les autres axes indéterminés et des points sans adjudication. Sur les quatre clichés historiques R3, répartis en trois groupes, le seul contact conservé reste à 4,64 px d’écart dans l’original avec les deux méthodes.

### Blasons usés, occultation et leurres

| Cinq scènes synthétiques de chaque type, fenêtres 704 px | Contacts appariés R3 → R4 | Propositions R3 → R4 |
|---|---:|---:|
| Propre, tube unique | 3/5 → 3/5 | 4 → 3 |
| Vieux trous seuls | 0 | 2 → 0 |
| Déchirures et vieux trous, témoin vide | 0 | 4 → 1 |
| Leurs équivalents avec leurres linéaires, témoin vide | 0 | 6 → 2 |
| Papier usé, tube unique | 1/5 → 0/5 | 8 → 2 |
| Papier usé, trois tubes groupés | 0/15 → 0/15 | 9 → 3 |
| Papier usé, contact masqué | Aucun point attribuable | 8 → 4 |
| Papier usé, tube clair | 0/5 → 0/5 | 8 → 2 |

Les cinq synthétiques masqués contiennent encore des propositions sur quatre vues avec R3 et trois avec R4. Un repère proche d’un trou n’en prouve pas la cause. En fenêtres 704 px, 39 puis 13 propositions non appariées sont proches d’un trou échantillonné ou rendu, 5 puis 1 proches d’une déchirure, sur le lot synthétique. Sur le papier réel très perforé de Warrior Games, les 8 fausses propositions deviennent 2, dont une reste près d’un trou annoté. Le témoin mousse passe de 2 à 1. Le seul contact synthétique perdu en fenêtres se trouve sur papier usé. L’écart médian des contacts appariés en fenêtres est de 2,35 px dans les deux bras, sur quatre points R3 puis trois R4. La disparition de la plus grande erreur vient du rejet de ce point. Les cibles multiples, les contacts hors cadre et les axes occultés du lot public demeurent insuffisamment renseignés. Les carquois, les tubes sombres en gros plan réel et les papiers campagne usés n’ont pas de série publique exhaustive.

### Fenêtres sur l’original

Le mode R3 dit « native » utilise des fenêtres 192 × 160 px sur l’image de travail déjà réduite à 704 px. R4 a également exécuté ce modèle sur les pixels de l’original, en remettant les cartes au repère de travail avant l’extraction de points. Le budget R3 de 300 fenêtres par image est resté actif. Warrior Games et Robin-Hood étaient admissibles. Les trois autres clichés dépassaient la limite avec 2 244, 306 et 1 512 fenêtres pour SAC, Reading et Scioto respectivement. Le cliché positif groupé est donc exclu de cette variante.

Sur ces mêmes deux images, les fenêtres 704 px donnent 16 propositions R3 et 4 R4. Les fenêtres de l’original donnent 10 et 2. Sur le papier très perforé, R3 passe de 8 à 3 fausses propositions et R4 de 2 à 0. Les autres résultats de ce bras concernent des axes hors cadre ou des zones non adjugées. Ce sous-ensemble ne démontre aucun gain sur la localisation de vrais contacts.

## Coût observé

Mesures sur Fedora, Ryzen 9 5950X, PyTorch CPU à deux threads et OpenCV à un thread. L’inférence chronométrée inclut l’extraction des propositions et, pour l’original, la remise à l’échelle des cartes. Elle exclut la lecture de l’image. Le temps du filtre s’ajoute à l’inférence. Les deux premières images sont écartées du résumé pour les lots de plus de quatre images.

| Mesure sur le lot synthétique, 38 mesures par mode | Médiane | p95 |
|---|---:|---:|
| R3 global | 12,68 ms | 19,51 ms |
| Filtre après global | 0,002 ms | 0,16 ms |
| R3 fenêtres 704 px | 63,39 ms | 82,54 ms |
| Filtre après fenêtres 704 px | 0,16 ms | 0,53 ms |

Le pic RSS du processus complet est d’environ 337 Mio sur ce lot. Le lot qui inclut les originaux atteint 542 Mio, décodage, cartes et runtime inclus. Ses deux tests à résolution originale prennent environ 1,00 s par image en médiane, avec un surcoût médian du filtre de 1,45 ms. Ce sont des ordres de grandeur sur PC. Ils ne qualifient ni le temps ni la mémoire sur téléphone. Le filtre ajoute zéro poids entraîné.

## Plan de comparaison quand le corpus le permettra

1. Reprendre `EntryNet`, initialisé aléatoirement, avec le même budget de calcul pour la mesure de référence. Ajouter un encodeur léger préentraîné de type MobileNetV3 dans un bras séparé si les droits des poids de départ sont établis.
2. Comparer une supervision de tube par instance et ses extrémités, une carte de localisation du contact, puis une décision de visibilité indépendante. Pour les vrais pixels annotés, l’incertitude d’un point règle la zone de supervision. Un axe approximatif ne fournit pas le masque exact du tube.
3. Utiliser les anciens trous, déchirures, carquois et objets similaires comme négatifs identifiés. Un tube masqué participe à la segmentation et au protocole de visibilité. Il ne fournit aucune cible positive pour la localisation. Les zones et axes non adjugés sont exclus de la pénalisation.
4. Geler les groupes d’originaux avant les fenêtres et augmentations, puis régler la politique d’abstention uniquement sur `dev`. Comparer photo isolée, fenêtres sur des régions choisies sans étiquettes et, séparément, image avant facultative. La comparaison doit conserver des groupes réels tenus à l’écart et mesurer par condition les faux, les abstentions et l’erreur de position.
5. Choisir un budget d’exploitation sur la base de ces résultats, avant toute adaptation Android. Le produit aurait aussi besoin d’une géométrie fiable de la cible et d’une confirmation humaine explicite.

Le [pipeline exécutable du laboratoire](README.md#rejouer-r4) couvre l’audit, l’annotation à l’aveugle, le contrôle des lectures et la comparaison. L’entraîneur R3 reste synthétique. La collecte et l’adjudication des contacts sur papier usé priment sur l’augmentation de la taille du réseau. Le filtre R4 reste une expérience de réduction des faux. Son rappel sur papier usé et la persistance de propositions sur les témoins réels empêchent une utilisation Android à ce stade.
