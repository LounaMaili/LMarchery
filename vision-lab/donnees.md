# Données et vérité terrain

## Périmètre et droits d’utilisation

Le pilote synthétique compte 186 images analysables, chacune accompagnée de sa référence sans flèche. Les 30 blasons indépendants fournissent 36 images d’entraînement, 18 de développement et 132 de test. Le générateur couvre le blason multicolore et deux variantes campagne jaune/noir et jaune/bleu-vert. Les paramètres font varier couleur de tube, regroupement, usure, niveau de lumière, éblouissement, flou, cadrage, inclinaison et résolution. Une des couleurs de tube est réservée au test. Les matériaux et les ombres restent des approximations graphiques.

Le lot public contient six images pour l’évaluation seule. Les fiches des originaux indiquent les licences ci-dessous. Les autorisations de copie et d’adaptation des licences BY-SA restent assorties d’attribution et, lors du partage d’une adaptation, des conditions de partage à l’identique. Le lot conserve ses crédits et ses sources localement. Il ne sert à entraîner aucun poids distribué. Les termes applicables aux images, aux annotations et à la redistribution d’éventuels poids doivent être revus ensemble avant un entraînement réel. [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

| Source vérifiée | Droit indiqué | Décision de ce lot |
|---|---|---|
| [Cordon, Casito](https://commons.wikimedia.org/wiki/File:WA_archery_target_with_arrows.jpg) | CC BY-SA 3.0, alternative à GFDL | Évaluation du recadrage serré avec deux flèches |
| [WA 80z, Wacky1976](https://commons.wikimedia.org/wiki/File:Archery_Arrows_Target_WA_80z.jpg) et [WA 122, Wacky1976](https://commons.wikimedia.org/wiki/File:Archery_Arrows_Target_WA_122cm.jpg) | CC BY-SA 4.0 | Même groupe de source, cas partiels ou inclinés |
| [Papier, Jack Soley](https://commons.wikimedia.org/wiki/File:Archery_Arrows_in_Paper_Target.jpg) | CC BY-SA 4.0 | Blason usé et entrées ambiguës |
| [Cible entière, Rodrigo Ndeahh](https://commons.wikimedia.org/wiki/File:World_Archery_target.jpg) | CC BY-SA 4.0 | Témoin vide multicolore |
| [FITA 80 cm, Simon A.](https://commons.wikimedia.org/wiki/File:FITA_80cm.jpg) | CC BY-SA 3.0, alternative à GFDL | Témoin vide campagne jaune/noir |
| [Archery target FITA 60cm, Haozhe Ma](https://www.kaggle.com/datasets/haozhema/archery-target-fita-60cm) | CC BY-NC-SA 4.0 déclarée dans l’article. La page Kaggle ne permettait pas de relire le champ de licence. | Exclu de l’entraînement. Bon candidat méthodologique grâce aux labels ponctuels, avec 480 originaux annoncés. La contrainte NC et les groupes d’acquisition d’origine restent à traiter. [Article](https://zenodo.org/records/8220337) |
| [Target and Arrow detection v6](https://universe.roboflow.com/archery-zrbei/target-and-arrow-detection/dataset/6) | MIT affichée pour le projet | Exclu. Les licences à l’image, la provenance des variantes et le sens de la « pointe » demandent un audit avant usage. |
| [Archery-arrows v15](https://universe.roboflow.com/roman-pqfz2/archery-arrows-tsldi/dataset/15) | CC BY 4.0 affichée pour le projet | Exclu pour les mêmes vérifications. Une boîte de flèche ne suffit pas comme référence ponctuelle. |

Les six originaux utilisés ne représentent que cinq sources. Le corpus public n’inclut pas de campagne jaune/bleu-vert avec flèches ni de paires réelles enregistrées avant/après.

## Schéma d’annotation

`manifest.json` porte `schema: 1`, l’origine de l’annotation et les images. Chaque enregistrement conserve un identifiant stable, un groupe de source, le split, les dimensions de l’image orientée, la famille de face, le scénario, le matériau s’il est connu, la condition d’acquisition, les chemins relatifs et les empreintes. La référence sans flèche, si elle existe, a sa propre empreinte.

- **Géométrie** — Conserver le centre et le rayon dans le plan de référence, un diamètre mesuré en millimètres et la transformation plan vers image. Pour une future géométrie réelle, conserver les correspondances ou le relevé qui l’établissent et sa précision. Utiliser `null` lorsque l’échelle ou le plan est insuffisamment connu.
- **Entrée** — Annoter le premier contact visible du tube avec le papier. L’encoche ou l’extrémité du tube côté observateur est une annotation distincte. Quand les deux points sont connus, leur segment décrit l’axe dans l’image.
- **Visibilité** — `visible`, `occluded`, `ambiguous` ou `out_of_frame`. Seul `visible` porte une coordonnée d’entrée et une incertitude en pixels. Les points artificiels proviennent du rendu avant toute exécution du détecteur.
- **Cordon** — `near_boundary` signale un point à proximité d’une séparation identifiée. Cela ne tranche aucune décision de cordon. Utiliser `null` si l’anneau est indéterminable.
- **Révision** — Annoter sans afficher la proposition du modèle. Sur des clichés réels, demander une deuxième lecture avant de figer un lot de validation. Conserver l’identité de la source et les désaccords plutôt que moyenner des points qui désignent des objets différents.

L’évaluation publique utilise la revue d’images du 20 novembre et le relevé des deux encoches visibles de la photo Cordon. Trois entrées ont une référence approximative exploitable, dont deux avec encoche, et cinq entrées attendues restent ambiguës. La taille physique et la perspective n’y sont pas établies. Le petit lot ne permet pas une qualification indépendante par plusieurs annotateurs.

## Isolation des partitions

Le groupe est attribué avant transformation. Les variantes d’un même blason et de ses références restent dans la même partition. Les fichiers publics restent exclusivement en test. Le lecteur refuse les groupes qui traversent les splits et les octets identiques dont les empreintes apparaissent dans des splits différents. Les prochains jeux réels devront grouper les rafales, séances, blasons réutilisés et auteurs communs. La vérification de quasi-doublons entre sources distinctes reste à ajouter avant apprentissage réel.
