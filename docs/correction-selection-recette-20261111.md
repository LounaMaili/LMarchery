# Stabilisation des commandes de sélection — correctif P2

Correction initiale du 11 novembre 2026, extraite dans une candidate produit
le 14 novembre 2026. La sélection suit l'[ADR 046](adr/046-selection-tactile-explicite-impacts.md).
La variante TEST reste une proposition indépendante ; elle n'est pas nécessaire
à la compilation ou à l'utilisation de l'application normale.

## Défaut et correction

Lors de la première recette synthétique TEST, deux appuis rapprochés sur la
commande d'entrée activaient puis annulaient le mode sélection historique.
Le bouton de visée disparaissait et la commande Annuler reprenait l'emplacement
de l'activation. Les deux appuis atteignaient donc deux actions différentes :
les gardes contre un deuxième appel d'activation ne suffisaient pas.

Les deux commandes partagées restent désormais présentes dans des colonnes
distinctes, de largeur égale et à libellés constants : « Sélectionner sur le
blason » puis « Annuler la sélection sur le blason ». Seule leur disponibilité
change. Les messages et les candidats ambigus viennent après ces commandes.
La visée reste présente et désactivée pendant la sélection dans les deux éditeurs.

Les commandes d'ouverture de score/repositionnement restent également présentes,
désactivées pendant leur proposition. Fermer/annuler conserve sa colonne distincte
avant les détails variables, y compris après fermeture. Aucun temporisateur,
délai arbitraire, recalcul de score ou changement de ticket ne remplace ces gardes.
Le commutateur rapide/précis reste volontaire : deux activations valides peuvent
changer deux fois le réglage sans enregistrer d'impact.

## Invariants conservés

- Mode de sélection explicite, sans ajout d'impact ni DML métier par un choix ou
  une annulation. Les superpositions restent possibles en mode placement.
- Plusieurs impacts admissibles imposent le choix numéroté d'un UUID ; aucune
  préférence implicite pour le score ou l'impact le plus proche.
- Retour/Annuler conserve le réglage rapide/précis antérieur. Les callbacks
  périmés, doubles choix et changements de propriétaire restent refusés.
- Capacités, volées validées, séances terminées, verrous concurrents et intentions
  `expected/proposed` restent contrôlés par les éditeurs existants.
- Corrections, motifs, décisions manuelles et traces de repositionnement restent
  soumis aux mêmes opérations métier ; aucun schéma ou format n'est modifié.

## Vérifications et limites

Les tests de présentation portent sur les emplacements logiques ordonnés ; ils
ne mesurent pas les rectangles Android. Les tests des deux contrôleurs couvrent
les activations répétées, l'annulation, le contexte antérieur et l'absence d'écriture.
Les tests SQLite utilisent les vrais contrôleurs et Room sur disque, avec gardes
INSERT/UPDATE/DELETE des douze tables pendant sélection et annulation, comparaison
complète des archives/JSON puis contrôle de l'intégrité et des clés étrangères.

La validation logicielle de référence reste :

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
```

Voir l'[environnement](environnement-et-ci.md) pour JDK21 et le SDK, et le
[protocole de sélection](validation-selection-tactile-impacts.md) pour les essais.
Les outils PC de capture, les probes et leurs runners ne font pas partie de ce
lot produit et ne sont pas requis par ses tests.

Les essais antérieurs de la variante TEST ont confirmé partiellement les doubles
activations en modes rapide/précis et des transitions de sélection ambiguë. Le
scénario complet, les deux éditeurs et cet arbre précis ne sont pas qualifiés
par transfert de ces observations. Les déplacements observés par l'ancien
harnais automatisé ne sont pas attribués à Compose sans preuve ; aucune correction
de coordonnées ou règle sportive n'est introduite ici.

Une future recette humaine doit utiliser une APK TEST et des données synthétiques
explicitement autorisées, après vérification de l'identité, de la signature et
de la conservation des données. Ne pas réutiliser des coordonnées acquises avant
un défilement. Arrêter sur toute incohérence sans relancer une intention incertaine.
Rotation, confort au doigt et fournisseurs SAF réels restent des essais distincts.
