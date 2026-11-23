# Journal des corrections

Le premier lancement au commit a5a949b a calculé une carte sur la première photo historique puis s’est arrêté au verrou de parité, avant tout calcul de reclassement ou sauvegarde de résultats. La comparaison Python portait sur des objets de types différents (couples du décodeur et listes de JSON). La correction compare le nombre de candidats, les coordonnées et chaque score par représentation flottante exacte, puis vérifie séparément le SHA-256 de la carte complète. Les modèles, coordonnées, paramètres, partitions, critères et annotations du gel restent ceux du protocole. Le journal local de cet arrêt est conservé. Compter aussi ce passage dans le budget total.

La mise en forme de la copie GitHub sépare ensuite les lignes des candidats pour leur lecture. Une comparaison après décodage JSON a confirmé l’identité de tous les enregistrements avec les six sorties finales locales. Aucune carte supplémentaire n’a été calculée pour cette mise en forme.
