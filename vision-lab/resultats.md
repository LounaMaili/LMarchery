# Essai initial sur Fedora

## Comparaison

**A** utilise des masques de couleurs et des ellipses pour estimer une géométrie candidate. Sur une paire, un recalage ECC en translation et la différence d’images alimentent l’extraction de composantes allongées. Sur une photo, la référence radiale estimée remplace le cliché sans flèche. L’asymétrie des ailettes sert à distinguer l’encoche du point d’entrée. En cas de symétrie, seul l’axe reste candidat.

**B** ajuste une régression logistique sur les descripteurs des deux extrémités. L’ajustement a retenu 34 tubes candidats appariés à des entrées synthétiques dans le split d’entraînement. Les propositions de tubes sont communes à A et B. La valeur logistique est un indicateur de classement. Sa calibration sur des données réelles reste à mesurer.

| Test | Tubes retrouvés | Entrées retrouvées | Fausses propositions | Erreur d’entrée p95 | Extrême |
|---|---:|---:|---:|---:|---:|
| Synthétique, paire A | 109/224 | 100/228 (43,9 %) | 13 | 1,86 px / 2,45 mm | 5,89 px / 11,56 mm |
| Synthétique, paire B | 109/224 | 101/228 (44,3 %) | 5 | 2,01 px / 2,55 mm | 5,89 px / 11,56 mm |
| Synthétique, photo seule A | 9/224 | 0/228 | 8 | — | — |
| Synthétique, photo seule B | 9/224 | 0/228 | 24 | — | — |
| Public, photo seule A | 0/2 | 0/3 | 0 | — | — |
| Public, photo seule B | 0/2 | 0/3 | 1 | — | — |

Pour le tube, l’appariement un à un vérifie l’axe, l’écart transversal et au moins 50 % de recouvrement longitudinal. La référence exige une entrée et une encoche visibles. Pour l’entrée, l’appariement maximise le nombre de correspondances, puis minimise la distance dans un rayon de 10 pixels travaillés. Les erreurs en pixels sont rapportées à l’image originale et les millimètres au plan synthétique connu. Ces distributions décrivent les entrées appariées. Le rappel rend visibles les échecs de détection. Sur une photo où des flèches restent ambiguës, une proposition non appariée est indiquée comme non attribuable. Sur les témoins vides synthétiques, aucune fausse proposition avec paire. En photo seule, deux en A et cinq en B. Sur les deux témoins vides publics, une fausse proposition en B. Les deux variantes se sont abstenues sur cinq des six images publiques, faute de géométrie reconnue.

Avec une paire, B retrouve 84/129 entrées proches d’une limite et 34/76 pour la couleur de tube tenue à l’écart. Les groupes serrés restent le défaut dominant, avec **0/99** entrées dans cette strate. Les ombres et les tubes fusionnent dans les composantes. Ce protocole n’a pas évalué l’exactitude du score ni l’effet de la matière réelle des tubes.

Environnement Fedora 45, Ryzen 9 5950X, Python 3.15.2, NumPy 2.5.4, OpenCV 4.13.0, un fil de calcul. En mode paire, médiane d’environ 44,5 ms et p95 d’environ 69 ms pour A et B, hors décodage et sur la série synthétique. La tête B sérialisée occupe 460 octets avec son protocole et ses métadonnées. Le pic RSS de l’expérience complète vaut 108 Mio sur synthétique et 284 Mio sur les originaux publics décodés. Il inclut Python, OpenCV, les images et les mesures. Une inférence sur téléphone et son pic mémoire restent à mesurer.

## Cadrage

| Condition synthétique | B par paire | Observation géométrique |
|---|---:|---|
| Face entière, de face | 11/21 | Cercle candidat sur 4/4 faces multicolores. Écart radial de 0,66 à 1,61 px. |
| Rapproché, centre et anneaux identifiés | 10/21 | Cercle sur 4/4 faces multicolores. L’estimation s’appuie sur des anneaux suffisamment visibles. |
| Cadrage partiel insuffisant | 6/18 | Aucune des 4 faces multicolores ne livre de cercle. La présence d’un tube ne fournit pas l’échelle. |
| Incliné | 11/21 | 4/4 cercles candidats malgré un écart radial de 9,53 à 10,32 px. La présence d’un cercle ne valide donc pas la projection. |
| Flou | 6/21 | Erreur p95 de 10,64 mm sur les six entrées appariées. |
| Reflets / surexposition | 2/21 | Aucune géométrie multicolore retenue, trois fausses propositions. |
| 352 × 284 px | 11/21 | p95 de 0,62 px, soit 2,30 mm. À 704 × 568 en face entière, p95 de 0,80 px, soit 1,57 mm. |
| Rapprochement simulé et recadrage numérique | 11/21 dans les deux cas | Les mêmes scènes sont rendues à un grossissement de 1,35×. Le rendu agrandi échantillonne directement la scène et le recadrage interpole une image déjà échantillonnée. Le pilote ne distingue pas les deux conditions en rappel. |

L’homographie a été évaluée séparément avec quatre repères synthétiques identifiés, perturbés de moins d’un pixel, et cinq autres points du plan utilisés pour le contrôle. Elle est constructible dans 120/132 cas et refuse les douze recadrages insuffisants. Le plus grand p95 par image est 1,64 mm sur cette simulation contrôlée. Ces correspondances sont fournies au test géométrique. Elles ne proviennent pas de la photo détectée. Le pilote ne qualifie ni une homographie automatique ni une perspective réelle.

Pour une première aide dans l’application, les indications utiles sont **photographier de face**, **garder la face entière et les limites visibles**, **réduire les reflets**, puis **refaire la mise au point si les tubes se confondent**. Un rapprochement qui ajoute des détails à la capture mérite d’être distingué de l’agrandissement d’un fichier déjà pris. Pour une paire, garder le même cadrage et des conditions d’éclairage proches. Si le centre ou l’échelle reste incertain, proposer de reprendre la photo ou de poursuivre en annotation manuelle comme dans l’[ADR 050](../docs/adr/050-saisie-photo-manuelle.md).

## Complément sur les arcs et l’occultation

Les deux rapports du 20 novembre 2026 et le CSV de simulation ont été relus. Dans cette étude antérieure, un arc isolé de 20° donnait un résidu médian de 1,35 px et une erreur radiale p95 de 105,93 mm. Sur la photo WA 80z, le bord visible est celui d’une face réduite et inclinée. Le reconnaître comme bord extérieur d’une face complète donnerait une fausse échelle.

Le nouvel essai ajuste deux rayons connus sur un plan déclaré frontal. Il contrôle l’étendue angulaire, le conditionnement et un troisième anneau exclu de l’ajustement. Les 120 simulations par cas utilisent des repères bruités à 1 px par axe. Les seuils du laboratoire servent à comparer ces cas synthétiques.

| Cas | Aperçus candidats | Résidu médian du fit | Erreur p95 du rayon | Écart médian sur l’anneau indépendant |
|---|---:|---:|---:|---:|
| Deux arcs répartis | 120/120 | 0,90 px | 0,97 px | 1,59 px |
| Deux arcs de 20° dans le même secteur | 0/120 | 0,93 px | 6,06 px | 3,42 px |
| Deux anneaux réétiquetés à la moitié de leur rayon relatif | 0/120 | 0,90 px | 205,62 px | 164,30 px |

Cinq cas d’arcs courts passent le contrôle d’étendue seul. Les points indépendants et les autres contrôles restent donc nécessaires. Une série entière d’identifiants d’anneaux erronés, y compris pour les points de contrôle, peut conserver une cohérence interne. Il faut une référence dont l’identité est établie séparément. Une vue oblique requiert une étude de projection distincte.

Le complément blanc réutilise les poids précédents sur six nouveaux blasons. En paire avant/après, A retrouve 5/6 entrées visibles et B en retrouve 6/6. L’erreur de B atteint 0,98 px et 1,04 mm au p95 sur ces six points synthétiques. En photo isolée, les deux obtiennent 0/6. Sur les six contacts masqués, les deux produisent **six propositions non attribuables en paire**. La vérité terrain porte une entrée nulle. Le détecteur doit donc encore savoir s’abstenir face à un tube tronqué par un masque.

## Modèle préentraîné et lot suivant

L’ajustement d’un réseau préentraîné sur des photographies est reporté faute d’un corpus réel autorisé, suffisamment annoté et indépendant. B mesure uniquement si apprendre l’orientation améliore les mêmes propositions OpenCV. Une comparaison suivante peut geler une partie d’un petit encodeur préentraîné puis affiner une tête à carte de chaleur d’entrée et une segmentation de tube. Un MobileNetV3 Small est une option d’encodeur. SSDLite à 320 px peut fournir une référence de boîte, avec une tête ponctuelle appliquée sur un recadrage à résolution native. Une boîte seule perd trop d’information sur l’extrémité. Vérifier les droits des poids et des jeux de préentraînement avant usage. [MobileNetV3 Small](https://docs.pytorch.org/vision/0.24/models/generated/torchvision.models.mobilenet_v3_small.html) · [SSDLite](https://docs.pytorch.org/vision/0.24/models/generated/torchvision.models.detection.ssdlite320_mobilenet_v3_large.html)

Le prochain lot doit réunir des paires réelles et des images isolées de plusieurs séances avec droits consignés, inclure des cibles vides, des faces campagne et des flèches groupées. Faire annoter séparément l’entrée et l’encoche par deux personnes sur un lot de test intact. Séparer ensuite les axes de tubes superposés ou produire une abstention explicite, puis comparer OpenCV et la tête préentraînée sur ce même lot. La mesure du score et d’un export léger hors ligne viennent après la validation de la localisation, de l’incertitude près des cordons et du temps de calcul sur téléphone.

Le poste expose 32 processeurs logiques et 31 Gio de RAM. Le pilote NVIDIA détecte la RTX 3090 de 24 Gio, version 620.70.06. Le paquet OpenCV chargé expose OpenCL, avec zéro périphérique CUDA OpenCV. PyTorch et ONNX Runtime sont absents du venv. Les essais sont réalisés sur un fil CPU. Les capacités et performances d’un entraînement GPU restent à qualifier.

Treize tests automatisés passent. Le manifeste synthétique initial se régénère à l’identique. Les deux évaluations du pilote initial donnent les mêmes poids, prédictions et associations. Les mesures des arcs, le manifeste du lot blanc et ses prédictions se reproduisent à l’identique. Les durées sont exclues des comparaisons d’égalité.
