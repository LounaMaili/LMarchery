# R17 · Relecture des associations et inventaire par flèche

La question est de savoir ce que les pixels permettent de confirmer dans la supervision R16 et quelles annotations par instance sont déjà utilisables. La [base R16](https://github.com/LounaMaili/LMarchery/commit/8ec45fbf88c30fbbdcc09c9eee769aa1f1abe5ea) conserve le verdict d’écarter le vérificateur dans la configuration testée. Le lecteur assisté connaît le résultat agrégé de R16 et certaines acquisitions des revues antérieures. La nouvelle lecture est assistée, sans adjudication humaine indépendante. Les scores individuels du vérificateur et ses décisions sont masqués pendant cette lecture.

## Population arrêtée avant la lecture

Retenir exclusivement ordinary-9301. La [liste figée](cas-geles.json) contient les coordonnées, les identités, les rangs et la filiation de chacun des 28 cas. Le [programme de sélection](figer.py) les extrait des archives R15/R16. Les photographies seront ouvertes après publication de ce protocole et de la liste. Budget de 28 géométries, zéro passage neuronal, zéro ajustement et zéro recherche de seuil.

| Origine | Unité de sélection | Cas initiaux |
|---|---|---:|
| Positifs R16 | Représentant effectivement pondéré du fit | 12 |
| Train exclu | Sortie ayant une correspondance stricte et exclue pour intersection avec une région ignorée | 4 |
| Développement évalué par R16 | Toute sortie compatible avec l’un des dix axes stricts appariables | 12 |

Dédupliquer une géométrie par l’empreinte des pixels et la paire non orientée des extrémités de la sortie, en conservant tous ses liens d’origine. Compter séparément les géométries, les associations et les identités physiques d’annotation. Le gel donne **28 géométries, 29 liens segment–instance et 27 identités**, sur 12 acquisitions de 11 provenances. Le segment 15 est compatible avec deux identités. Les cas 22 et 24, puis 26 et 27, partagent respectivement une identité. Deux vues d’un même support Crigges forment un bloc de pondération dans R16. L’identité d’une flèche reste celle de sa séquence. Les onze acquisitions évaluées en R16 sont désormais un développement exploré. Leur affectation d’origine est conservée comme historique.

## Lecture visuelle

Pour chaque géométrie, ouvrir le contexte puis le détail en pixels seuls. Comparer ensuite aux calques séparés de l’axe nommé, du segment émis, du point d’entrée visible et des régions ignorées. Consulter leur superposition sur le même repère. En présence de deux références possibles, inspecter les deux et maintenir les deux liens. Examiner la continuité, la matérialité du tube, ses contours, les croisements, les limites de la zone lisible et la jonction au support. Un voisinage géométrique ou une distance au bout ne tranche pas la lecture de la matière ou de l’identité.

Consigner les champs suivants avec un indice photographique propre au cas.

| Champ | Décisions possibles |
|---|---|
| Matière au droit du segment | Tube physique confirmé, autre matière démontrée, indécidable |
| Association à chaque identité | Correcte, incorrecte démontrée, ambiguë |
| Étendue du segment | Fragment correct mais incomplet, suit la partie visible, sans portion attribuable, indécidable |
| Relation du bout à l’entrée | Bout pertinent sur jonction visible, bout sans relation établie, entrée de coordonnée inconnue |
| Décision documentaire | Maintien de la lecture, maintien de l’exclusion avec sa raison, ou correction versionnée comportant le champ initial, la nouvelle valeur et l’indice probant |

Une correspondance est confirmée quand la matière et l’association sont identifiables dans les pixels. Une erreur exige un élément positif qui contredit l’association ou la supervision initiale. Une autre matière, un mauvais tube ou un trait de référence mal attribué sont décrits séparément. Si les indices ne départagent pas les hypothèses, classer le cas comme indécidable. Un fragment est utilisable pour la supervision s’il est attribué au bon tube et localisé dans une partie observable du cliché. Le segment entier d’un cas exclu reste hors du fit, même si un tronçon est visuellement reconnaissable. Compter le fragment incomplet séparément d’une trace qui suit la partie visible. Une entrée inconnue reste sans coordonnée.

Conserver la version initiale et tracer toute correction en R17 sans modifier les opérations ni les résultats R16. Agréger par acquisition, provenance et ensemble. Publier séparément le bilan par géométrie et les liens par identité. Les masses concernent uniquement les douze représentants positifs, avec les poids R16 d’origine et pour dénominateur la masse positive totale. Six représentants de Bernard Gagnon donnent 6/12 en nombre et 2,375/14,25 en masse. Les indécisions n’imputent aucune erreur et les concentrations n’établissent aucune cause de l’échec.

## Inventaire indépendant des sorties du générateur

Inventorier les annotations des 195 acquisitions autorisées de R14/R15, en séparant train, développement exploré antérieur et développement R16 désormais exploré. Grouper les occurrences par identité d’instance dans leur séquence ou acquisition, maintenir les sources de chaque occurrence et les répétitions. Distinguer l’axe de la partie visible, le masque ou polygone d’origine, la boîte éventuelle, le point dans cette vue et un point documentaire dans une autre lecture. Une flèche antérieure présente dans une séquence mais sans position documentée sur la vue courante garde ce statut. Ne transférer aucune coordonnée entre vues.

Conserver les supports déclarés de l’acquisition et signaler l’affectation individuelle inconnue sur plusieurs faces. Séparer entrée visible avec coordonnées, occultation explicitement établie et position inconnue ou ambiguë. Publier les nombres d’instances avec axe, masque, entrée et leurs intersections **sur une même vue**, ainsi que les informations manquantes pour chacune de ces cibles de supervision. Les prédictions, les traits LSD et les rectangles d’exclusion ne sont pas des annotations d’instance. Joindre statut de lecture, source, droits et repère. Les figures publiques seront des dérivés crédités dont la licence autorise le partage, avec cadrage des supports pour la confidentialité. La revue s’arrête après les lectures, l’inventaire et la recommandation. Le test réservé reste fermé, le NO-GO Android est maintenu, les contrats Desktop D3 sont préservés.
