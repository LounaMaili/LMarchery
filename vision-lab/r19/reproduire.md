# Rejouer R19 depuis les archives publiques

Le [protocole exécutable figé](https://github.com/LounaMaili/LMarchery/blob/c8154f931d01a78957547b06c91c026f4ff3ee53/vision-lab/r19/protocole.md) contient l’architecture, les règles et les commandes des trois ajustements d’origine. Le [rapport](../r19.md) décrit les essais effectivement exécutés. Toutes les commandes ci-dessous se placent dans `vision-lab/` du checkout des preuves.

## Mesures, tableaux et figures depuis GitHub

Le calcul des résultats lit uniquement le périmètre public R18, ses masques et annotations, les [sorties B archivées](../r16/predictions/ordinary-9301.json) et les [prédictions R19](predictions/). Il n’ouvre aucun fichier de poids ou d’acquisition originale. Pour les courbes d’apprentissage, les mesures de processus sont dans [executions.json](executions.json) et chaque ligne des [trois journaux](apprentissage/) représente une mise à jour. Les planches utilisent les JPEG autorisés publiés dans R18 et leurs matrices de projection. Le programme conserve pour Moo.The.Cow la zone restreinte de diffusion.

Utiliser CPython 3.15.2, NumPy 2.5.4, OpenCV headless 4.13.0.92. Pillow 13.0.0 et DejaVu Sans sont nécessaires aux seules planches photographiques. Le SVG est autonome. Les empreintes des roues CPU utilisées lors des ajustements sont celles des fichiers de versions du laboratoire. [Environnement minimal](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [Torchvision et Pillow](../requirements-r8-cpu.txt)

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
OUT=$(mktemp -d)
cp -r r19/predictions r19/apprentissage "$OUT/"
python -m r19.bilan "$OUT" --archives "$OUT/predictions"
python - "$OUT" <<'PY'
from pathlib import Path
from r19.publier import readable
import sys
for f in (Path(sys.argv[1])/'mesures').glob('*.json'):
    readable(f)
PY
python -m r19.tableaux "$OUT"
python -m r19.tracer "$OUT"
python -m r19.planches "$OUT"
diff -r r19/mesures "$OUT/mesures"
cmp r19/decision.json "$OUT/decision.json"
cmp r19/tableaux.md "$OUT/tableaux.md"
cmp r19/identites.md "$OUT/identites.md"
diff -r r19/figures "$OUT/figures"
python -m unittest -v test_r19_archives
```

Les comparaisons d’octets des JPEG s’entendent avec l’environnement de rendu utilisé. Le test de livraison revérifie les empreintes des planches publiées et le contenu numérique du rejeu. Il utilise aussi un second algorithme de cardinalité pour contrôler le choix exact des quatre seuils. Le contrôle de livraison couvre la topologie des fusions, l’absence d’entrée localisable et l’entrée sans masque dans les tests préparatoires. [Contrôles du gel](../test_r19.py) · [Contrôles des archives](../test_r19_archives.py)

## Formats d’archive

- Dans chaque graine, `predictions.json` donne la taille de grille, le repère 704 réel, les douze coordonnées, les deux facteurs du score, le score final et les segments de chaque requête. Les coordonnées et les segments directs sont déjà **en repère 704**, en centres de pixels.
- Les [masques de 9301](predictions/9301/masques/), [9302](predictions/9302/masques/) et [9303](predictions/9303/masques/) sont des PNG mono-plan uint16. Le bit de poids `1 << q` représente la cellule positive de la requête q. Plusieurs masques peuvent couvrir la même cellule. L’empreinte numérique porte sur le plan uint16 little-endian. Lire les PNG avec `cv.IMREAD_UNCHANGED`. Les masques reviennent aux pixels R18 par appartenance du centre natif à la cellule de grille.
- Les résultats par graine décrivent le point de fonctionnement et la couverture avant sélection. La distance d’appariement est en pixels du repère 704. Les tables de courbes par photo sont en escalier. Pour un seuil global entre deux scores locaux, conserver le dernier état rencontré en descendant les seuils. L’abstention porte la valeur 2.
- Le [registre local](inventaire-local.json) donne les octets, l’emplacement et le SHA-256 de chaque poids. L’[inventaire des photographies et de la capsule R18](../r18/inventaire-local.json) et les [sources et licences](../r18/sources.json) restent les références pour les pixels de départ. Le [gel R19 des acquisitions et des empreintes](donnees.json) contient seulement les neuf vues train et les onze vues de développement autorisées.

Le [conditionnement des sorties d’origine](publier.py) a vérifié les trois suites de 96 tirages effectivement exécutés, la filiation des poids, les plans uint16 et les mesures recalculées. La mise en lignes des grands JSON ne change aucune valeur. Les durées d’inférence comprennent le prétraitement, le réseau et la reconstruction des fragments sur ce poste CPU. Elles excluent la lecture du JPEG et l’écriture des prédictions. Le coût de B n’a pas été remesuré.

Le prévol sur coordonnées entières synthétiques a nécessité la conversion en flottants attendue par OpenCV. Cette correction précède le gel et les trois ajustements. Les trois exécutions utilisent toutes le code du protocole publié. Les jobs GitHub et le SHA de leur checkout sont consignés dans la [PR R19](https://github.com/LounaMaili/LMarchery/pull/85).
