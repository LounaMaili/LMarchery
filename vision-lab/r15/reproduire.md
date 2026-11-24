# Reproduire et contrôler R15

La PR utilise la [base R14](https://github.com/LounaMaili/LMarchery/commit/0941d6e97b41bc67ae040494bad6e1be826b3ae0). La branche a publié le [protocole](https://github.com/LounaMaili/LMarchery/commit/ce737c4f7e86f2f07733b860878444929790ad8c) avant les admissions, puis le [manifeste et la configuration](https://github.com/LounaMaili/LMarchery/commit/c0ef5b594af7def3f1917e557984c3a3a3894256) avant les caractéristiques. Les 185 lignes de référence proviennent du registre R14. La somme SHA-256 du manifeste porte sur les empreintes des pièces, l’algorithme et l’ordre étant publiés dans le fichier. Les passages portent uniquement sur les 25 identifiants autorisés du gel. [Manifeste](manifeste-scelle.json) · [Configuration, graines et six poids](configuration.json)

## Revue numérique depuis GitHub

Depuis `vision-lab/`, Python standard suffit pour recalculer les effectifs, les deux portes et la somme du manifeste. Le test de revue vérifie aussi l’affectation par provenance, les 110 rangs documentaires, les CSV, les largeurs manquantes, les SHA et les valeurs des figures. Il utilise les dépendances CPU du laboratoire. L’index de partitions associe chaque identifiant à sa photographie et à son fichier de lecture. Les positives sont définies par trait et instance, les négatives par examen du segment. Un rang sans lecture reste sans étiquette. [Partitions](partitions.json) · [Références R14](../r14/partitions.json) · [Delta des lectures](lectures.json) · [Banque R14](../r14/banque-segments.json) · [Delta des segments](banque-segments.json)

```sh
python -m r15.corpus
git diff --exit-code -- r15/partitions.json r15/effectifs.json r15/portes.json r15/manifeste-scelle.json
python -m unittest -v test_r15
```

Le calcul de préparation a utilisé Linux x86-64, CPython 3.15.2, PyTorch CPU 2.14.1, OpenCV 4.13.0 et NumPy 2.5.4. Les bibliothèques de ce calcul et leurs roues sont fixées dans le laboratoire. L’extraction vidéo a utilisé FFmpeg 9.0.2. La figure de portes a utilisé CPython 3.12, NumPy 2.5.5 et Matplotlib 3.11.2. [Dépendances CPU](../requirements-r8-cpu.txt) · [Versions et empreintes R8](../r8/reproduire.md) · [Rendu des figures](figurer.py)

## Fichiers source et extraction des caractéristiques

La capsule d’acquisitions contient les dix fichiers d’entrée, le film de Voltmetro et le calque public. L’autre capsule conserve les fichiers rejetés examinés, les notices de recherche et les supports de lecture. Les deux archives sont locales. Pour les décompresser, leur racine d’extraction est `data/r15/`. Les originaux diffusés de toutes les acquisitions admises restent identifiables individuellement sur GitHub par URL, licence, taille et SHA. Le programme de restauration s’arrête dès que les octets diffèrent. Il peut aussi vérifier un dossier déjà restauré. [Inventaire local](inventaire-local.json) · [Médias admis](lectures.json) · [Médias des galeries Roboflow](echantillons-roboflow.json) · [Inventaire des 185 anciennes photos](../r14/inventaire-pixels.json)

```sh
python -m r15.restaurer data/r15/media
python -m r15.prelever
OUT=$(mktemp -d)
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
python -m r15.caracteristiques "$OUT/caracteristiques" --worktrees ../..
diff -r r15/caracteristiques "$OUT/caracteristiques"
```

Les worktrees historiques doivent contenir les fichiers publics documentés et les six poids vérifiés. L’option `--worktrees` remplace la racine absolue de conservation. La commande `caracteristiques` exige un dossier de sortie neuf. Les entrées de chaque poids sont les 14 photos du train disposant d’un axe tracé ou d’un prélèvement documentaire et les 11 photos du développement indépendant. Un processus lourd, quatre threads Torch, un thread OpenCV, 150 passages. Les six cartes sont des répétitions sur les mêmes images et les mêmes références. Le germe du poids n’affecte ni l’admission ni la lecture. Le générateur de contours, son tri et sa limite R13 de 32 restent ceux du dépôt de référence. [Générateur](../r13/segments.py) · [Préparation](caracteristiques.py) · [Cartes et lignes par poids et acquisition](caracteristiques/index.json)

Chaque CSV contient les sorties du générateur et, en supplément, les rangs déjà choisis pour la banque documentaire avant son tri. Le rang longueur stable, la photo et le poids permettent de joindre les lignes. Un rang de sortie vide signifie que le segment a été prélevé uniquement pour la documentation. La géométrie du segment est en pixels du repère R12 dont le grand côté vaut au plus 704. Les coordonnées des lectures sont exprimées dans l’image orientée de travail, grand côté au plus 1408. Appliquer le rapport *par dimension* entre les deux tailles. Pour chaque rang documentaire, le calcul a exigé l’égalité exacte des extrémités avec la banque après conversion. [Repère](../r12/geometrie.py) · [Lecture géométrique](prelever.py)

| Entrée R14 | Définition fixée pour la préparation |
|---|---|
| Longueur, famille | Longueur euclidienne du segment en pixels et indicatrice de deux contours appariés. |
| Médiane et dispersion de largeur | Sur onze positions dans le recouvrement longitudinal des deux contours constitutifs R13, distance normale interpolée entre bords, puis médiane et médiane des écarts absolus. Les deux cellules restent vides pour un isolé. |
| Contraste transversal absolu | Minimum des écarts absolus entre médiane au centre et profil de chacun des deux flancs à 6 px, comme R13. |
| Continuité longitudinale | Fraction des 21 sondes du segment dans le masque de soutien R12, seuil tube 0,5 puis dilatation de rayon 3. |
| Moyenne du masque | Fraction de ces sondes dans le masque tube binaire avant dilatation. |
| Écart local d’aspect aux bouts | Différence absolue des mesures R12 `surface` aux deux bouts, chacune dans son sens sortant. La convention hors champ vaut 0,5. |

Les neuf colonnes de mesure du gel comprennent également la probabilité tube continue moyennée sur les 21 sondes, conservée pour documenter la binarisation. L’index final explicite les **huit entrées R14** et cette neuvième colonne documentaire. Cette précision de schéma a été apportée après extraction. Elle conserve les lignes numériques, les champs bruts, les requêtes et le manifeste du gel. Aucun résultat scientifique antérieur remplacé. Il n’y a pas eu de choix d’imputation ou de représentant positif, d’ajustement, de sélection de seuil ou d’évaluation du vérificateur. Ces opérations relèvent d’une comparaison ultérieure après revue. [Schéma et empreintes des CSV](caracteristiques/index.json) · [Protocole du vérificateur](../r14/protocole.md)

## Recherche, archives et figures

Les résultats d’une recherche publique peuvent évoluer. Le rapport et les registres publiés permettent de vérifier les décisions sans appeler les plateformes. Les caches des notices et les octets de l’audit permettent de refaire les comparaisons d’images. Les trois nouveaux contrôles photographiques publiés portent leurs crédits, licences et coordonnées de coupe. La collecte a accepté les fichiers publics disponibles sans compte et les liens de médias publiés par leurs propres notices. Les archives dont l’accès était restreint n’ont pas été nécessaires. [Inventaire des catalogues](sources.md) · [Figures et droits](figures/README.md)

```sh
python -m r15.recherche commons
python -m r15.recherche roboflow
python -m r15.flickr
# Avec le cache d’audit et les anciennes acquisitions publiques restaurés
python -m r15.doublons
python -m r15.archiver
MPLBACKEND=Agg python -m r15.figurer "$OUT/figures" --photos
```

La lecture des jonctions et des segments reste une observation assistée datée. Relancer un moteur de vision ne recrée pas cette annotation. Le développement déjà exploré sert uniquement au contrôle documentaire de recouvrement par empreinte. Les données d’un test et les photos personnelles ne figurent dans aucune de ces commandes.
