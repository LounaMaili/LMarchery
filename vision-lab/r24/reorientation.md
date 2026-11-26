# Motif de réorientation · recomptage avant ajustement

Les six états à 768 pas couvrent chacun les 13 entrées connues des sept photos autres que celles de Gagnon et Lynch. Sur les neuf photos, **aucun des 136 triplets manquants cumulés sur les six états** ne dispose d’un masque et d’un axe admissibles sur la même requête. Ce sont 136 observations identité–état, pour 26 identités à entrée connue. Les 5 identités à entrée inconnue sont conservées dans chaque inventaire et exclues du dénominateur des triplets.

| Matrice | Entrées sur les sept autres photos | Entrées sur les neuf | Triplets manquants | Parmi ces manquants, masque et axe disponibles séparément | Sur une même requête |
|---|---:|---:|---:|---:|---:|
| [R21, 9301](diagnostic/R21-9301.json) | 13/13 | 26/26 | 24 | 1 | 0 |
| [R22, 9301](diagnostic/R22-9301.json) | 13/13 | 26/26 | 22 | 0 | 0 |
| [R21, 9302](diagnostic/R21-9302.json) | 13/13 | 25/26 | 24 | 0 | 0 |
| [R22, 9302](diagnostic/R22-9302.json) | 13/13 | 25/26 | 21 | 0 | 0 |
| [R21, 9303](diagnostic/R21-9303.json) | 13/13 | 26/26 | 23 | 1 | 0 |
| [R22, 9303](diagnostic/R22-9303.json) | 13/13 | 26/26 | 22 | 1 | 0 |

Les trois cas de champs répartis concernent la photo 18105652008. En R21–9301, le masque de nº 1 est admissible sur q9 et son axe sur q7. En R21–9303, le masque de nº 2 est admissible sur q8 et son axe sur q1, q4, q6 et q9. En R22–9303, le masque de nº 2 est admissible sur huit requêtes et son axe sur la seule q5, extérieure à ces huit.

Chaque fichier donne la provenance, le numéro d’identité, les listes de requêtes admises par champ, leur intersection et les affectations géométriques un à un. Le masque doit atteindre 90 % de son plafond individuel, et l’axe au moins 11 sondes sur 21. Le compteur d’entrées utilise l’appariement avant seuil. Les matrices et leurs SHA-256 sont identifiés dans les fichiers. La commande `python -m r24.diagnostic`, depuis le laboratoire, utilise seulement les matrices archivées et les noms de provenance du registre R21. [Code](diagnostic.py) · [Totaux](diagnostic/synthese.json)

**Décision.** Déplacer seulement les points d’entrée ne peut ajouter un triplet à ces identités avec les masques et axes disponibles. La comparaison des maxima secondaires sur fond vérifié proposée en R23 est mise en attente. R24 examine si une adaptation limitée des représentations visuelles améliore la coexistence des champs. R22 reste historiquement écarté. [Diagnostic R23](https://github.com/LounaMaili/LMarchery/blob/c1044ea17e3c24fd83fff3ce1266582279f5eca9/vision-lab/r23.md)
