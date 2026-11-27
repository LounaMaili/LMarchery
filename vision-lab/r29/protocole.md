# R29 · Plan figé de lecture de la chaîne des boîtes

**Question.** À quelle opération une identité du train perd-elle sa dernière boîte géométriquement suffisante, et à quel étage plusieurs identités doivent-elles partager trop peu de boîtes ?

R28 a déjà exploré ces neuf photos. Au pas 768, les graines 9302 et 9303 séparent chacune 17/31 identités. Leurs bornes finales un à un valent 19/31, pour 20 puis 23 identités individuellement possibles. Huit fûts de Bernard Gagnon n’ont de boîte suffisante dans aucune des deux listes finales. Les concurrences chez Lynch et jrladia et les différences chez 4028mdk09 sont connues au dernier étage. Les étapes intermédiaires de ces passages sont l’objet de R29. La lecture est rétrospective sur le train, avec connaissance des annotations et des sorties finales. [Rapport immuable R27](https://github.com/LounaMaili/LMarchery/blob/3eff2b26f857808f60920db7c78157e9162bd71b/vision-lab/r27.md) · [Rapport immuable R28](https://github.com/LounaMaili/LMarchery/blob/4774a6978c340c0b9d7a4bdb0b5fe333d94a980f/vision-lab/r28.md)

## Population, paramètres et budget

Les deux seuls checkpoints sont les états persistants R27 à 768 de 9302 puis 9303. Chacun lit exactement les neuf rasters BGR d’apprentissage préparés en R27, dans l’ordre du manifeste R26. Ils couvrent huit provenances et 31 identités de masque évaluables. Les deux photos Crigges partagent un support et chaque graine relit les mêmes vues. Les entrées, coordonnées et annotations conservent leurs repères historiques. [Configuration, empreintes et inventaire des checkpoints](configuration.json) · [Entrées R27](../r27/entrees.json) · [Identités R18](../r26/train.json) · [Environnement](../r27/environnement.json)

Le budget commun aux deux graines est de **18 passages sur photo entière**, un processus lourd à la fois. Un marqueur persistant est réservé avant chaque appel du modèle. Une exception ultérieure consomme ce passage. Un dossier de lancement neuf empêche la reprise silencieuse du lot. Les vérifications d’empreintes et les essais synthétiques des opérateurs seuls précèdent la lecture. En cas d’incident, conserver les traces acquises et annoncer le dénominateur réalisé. Toute divergence avec R27 suspend l’interprétation jusqu’à explication à partir des sorties déjà recueillies.

L’appel autonome original du Mask R-CNN R50-FPN 3x CPU reste l’unique chemin de lecture. Il utilise la normalisation, le redimensionnement déjà réalisé, les boîtes et les opérateurs R27 avec FP32 déterministe, quatre threads Torch intra-op et un inter-op. OpenCV, OMP, BLAS et MKL reprennent les réglages R27. Les poids et les buffers sont restaurés depuis les états figés. Le RPN conserve sa sélection historique par niveau (NMS 0,7, présélection 1 000 en évaluation), puis au plus 1 000 propositions. Le score final doit être strictement positif. La NMS finale élimine les recouvrements strictement supérieurs à 0,5 et la sortie garde les douze premiers survivants. [Passage autonome historique](../r27/modele.py) · [Instrumentation publiée](capturer.py) · [Témoin synthétique des opérateurs](controle_local.py)

## Trace d’une seule photo

1. Attribuer à chaque proposition RPN retenue son rang `j`, son indice d’ancre, son logit et ses coordonnées natives et modèle. Ces indices valent uniquement pour le couple graine–photo.
2. Observer les sorties de la tête RoI déjà appelée, avec ses logits de classes, ses quatre deltas de régression, la boîte régressée avant bornage et la probabilité originale de premier plan. Le tri décroissant stable garde l’ordre d’origine pour les scores égaux.
3. Conserver la boîte après bornage flottant aux dimensions de l’image, les indicateurs de surface strictement positive et de score strictement positif, et le rang d’admission. Une valeur non finie provoque l’arrêt historique. Le sous-étage de bornage permet de distinguer l’effet sur le support de l’exclusion d’une proposition.
4. Observer la liste complète réellement renvoyée par la NMS historique, dans son ordre. Pour chaque élimination, reconstruire le **premier survivant antérieur** qui la supprime, l’IoU dans le repère modèle FP32 et les deux scores. Une boîte déjà éliminée ne peut supprimer la suivante. Comparer la totalité de la séquence reconstruite à celle de l’opérateur. La reconstruction est une explication de la trace capturée. [Reconstruction et vérification](selection.py)
5. Conserver le rang NMS avant limite, le q local pour le préfixe final, l’ancêtre RPN et le motif de retrait de toute autre ligne. L’unique appel historique à la tête de masque reçoit seulement ce préfixe. Comparer **dtype, dimensions et octets** de chacun des huit champs autonomes avec le NPZ R27 de la même photo, notamment probabilités 28 × 28, bits de masques natifs, ancres, rangs et scores. Aucun passage de référence supplémentaire.

## Capacité et attribution

Calculer les six colonnes **RPN retenu → régression avant bornage → bornage → admission → NMS avant limite → douze sorties**. Les coordonnées xyxy passent du modèle au natif par le produit FP32 de R27 avec l’échelle par axe. Pour une boîte régressée hors cadre, le collage utilise sa géométrie originale sur la grille d’observation de la photo. La colonne suivante utilise la boîte bornée. Une surface dégénérée ou une fenêtre de collage sans intersection raster a un support vide.

Pour chaque boîte, Ω est le support du collage historique, sur centres de pixels, d’une grille 28 × 28 de 1, seuillé à ≥ 0,5. L’opérateur interne CPU de Detectron2 utilise la même fenêtre native que R28. Traiter une boîte à la fois, compter les intersections dans cette fenêtre, puis libérer le masque. Les étapes de sélection héritent de la géométrie bornée du même j. Vérifier les 216 supports des sorties finales pixel par pixel contre R28 ainsi que les intersections par identité. [Implémentation de collage](supports.py) · [Convention R28](../r28/protocole.md) · [Registre des supports R28](../r28/supports/index.json)

Pour l’identité i, prendre T = nombre de ses pixels positifs R18 et I = nombre de ceux compris dans Ω. La borne idéale de Dice vaut **2I/(T+I)**. La comparaison inclusive à 90 % du plafond Dice individuel utilise les produits entiers R27. Les pixels 255 gardent leur statut ignoré. Une boîte suffisante autorise une solution idéale. La capacité de la tête de masque et la fidélité de ses contours en zone inconnue restent des questions distinctes. [Plafonds individuels](../r20/plafonds.json) · [Borne et appariement R28](../r28/couples.py)

Par photo et étage, publier l’ensemble des j suffisants de chaque identité, le meilleur Dice optimiste, l’attribution et les deux effectifs de capacité. L’attribution maximise d’abord la cardinalité puis la somme des fractions de plafond bornées à 1, avec l’ordre d’identité R18 et les départages historiques. Utiliser l’ordre RPN pour la première colonne, le rang stable de score pour régression et bornage, puis l’ordre historique conservé. La sortie finale doit reproduire également les q attribués dans R28. Un changement de témoin dans un maximum n’établit aucune impossibilité individuelle.

Pour chaque composante déficitaire du graphe entre identités individuellement possibles et boîtes suffisantes, publier un ensemble S d’identités et tous ses voisins N(S) dont le déficit |S|−|N(S)| égale la perte de cardinalité. Parmi ces certificats prendre le plus petit S puis l’ordre R18. Vérifier par énumération des sous-ensembles (au maximum neuf identités par photo) que ce déficit égale celui de l’appariement. Les identités sans arête sont comptées à part.

Suivre gains et pertes d’arêtes de même j après régression et bornage, même si une autre boîte reste suffisante. Pour chaque disparition de possibilité individuelle, relever le dernier étage possible, le premier impossible et les boîtes correspondantes, en distinguant les réapparitions. Publier les causes de retrait observables et les variations de cardinalité par photo et par provenance. Pour chaque identité finalement sans arête, préciser sa dernière possibilité ou son absence depuis le RPN. Détailler les quatre provenances signalées dans la question. Les causes en amont du RPN et les raisons apprises d’un score ou d’un delta restent indéterminées.

## Reproduction et décision

Depuis `vision-lab/`, les commandes suivantes utilisent l’environnement CPU 3.9.19 R27 et le laboratoire léger 3.15.2. La commande de capture est réservée au poste de conservation et à un budget de passages nouvellement autorisé. Le rejeu des traces publiées démarre à la mesure sans charger le réseau.

```bash
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
"$PYTHON_R27" -m r29.controle_local -v
"$PYTHON_R27" -m r29.capturer
"$PYTHON_R27" -m r29.archiver
"$PYTHON_R27" -m r29.supports
"$PYTHON_LAB" -m r29.mesurer
"$PYTHON_LAB" -m r29.publier
"$PYTHON_LAB" -m unittest -v test_r29
```

Le bilan identifie le ou les étages limitants à partir des capacités et de leurs témoins, sans changer les réglages. Une seule prochaine expérience sera proposée après le bilan. L’essai R27 reste techniquement incomplet et sans GO de capacité. Le développement et le test réservé restent fermés, le NO-GO Android reste en vigueur et les contrats Desktop D3 sont conservés.
