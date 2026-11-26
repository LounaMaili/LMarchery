# Reproduire R26

## Analyse locale ou depuis un checkout GitHub

Depuis `vision-lab/`, utiliser Python 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0 (roue headless 4.13.0.92). Les SHA-256 des roues Linux sont figés dans les exigences du laboratoire. Aucune image originale, dépendance de réseau neuronal ni clé d’accès n’est nécessaire au calcul des bornes. [Dépendances](../requirements-ci.txt) · [Sources des calculs avec SHA-256](mesures/sources.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
out_r26=$(mktemp -d)
python -m r26.archives "$out_r26/mesures"
diff -r r26/mesures "$out_r26/mesures" --exclude=README.md
python -m unittest -v test_r26
```

Le test de revue reproduit tous les JSON des bornes et la figure au contenu près, contrôle les seuils inclusifs de densité et compare les solutions d’appariement à une programmation dynamique indépendante. Il reproduit aussi les boîtes natives et le manifeste du train, les ensembles historiques à protéger et simule la décision future sur des identités fictives. Les 264 cartes sont identifiées par leur SHA-256 avant lecture. Aucune exécution de modèle n’intervient dans ces commandes. [Tests de revue](../test_r26.py) · [Code des bornes](archives.py) · [Code de la figure](figurer.py) · [Code du manifeste](preparer.py)

Pour écrire aussi la figure et les trois manifestes dans le même répertoire temporaire :

```bash
python - "$out_r26" <<'PY'
from pathlib import Path
import sys
from r19.donnees import read
from r26.figurer import draw
from r26.preparer import prepare

out = Path(sys.argv[1])
draw(read(out/'mesures/bilan.json'),out/'figures/bornes.svg')
prepare(out)
PY
```

## Sources et empreintes

Les matrices d’origine sont celles de Dice R22 et bloc 8 R24 au pas 768. Le recensement des observations PCA et les logits natifs viennent du dossier R25. La première publication R26 des calculs porte le SHA `01705933b8e6a91b78a5968f866bf5917fe8c5fd`. Les constats de la revue externe ont précédé ce recalcul rétrospectif. [Premiers calculs et tables R26](https://github.com/LounaMaili/LMarchery/tree/01705933b8e6a91b78a5968f866bf5917fe8c5fd/vision-lab/r26/mesures) · [Référence R25](https://github.com/LounaMaili/LMarchery/blob/5cee1e0b287faab138f0d8ae4e961dec7b0b9ed8/vision-lab/r25.md)

L’inventaire donne nom, rôle, taille, SHA-256 et emplacement des neuf fichiers de travail train et des six checkpoints historiques. Les licences et crédits des neuf sources y sont conservés. Les captures volumineuses déjà constituées sont identifiées dans l’inventaire R25. R26 ajoute uniquement documents, code et mesures légères. [Inventaire R26](inventaire.json) · [Capsules R25](../r25/inventaire-local.json) · [Licences d’origine R18](../r18/sources.json)

Les deux poids candidats ont été identifiés sur les serveurs des éditeurs. Les octets ont été lus en flux pour vérifier tailles et empreintes, sans charger les modèles. La vérification optionnelle ci-dessous relit uniquement les deux fichiers de poids officiels, conserve zéro fichier binaire et échoue dès qu’un digest ou une taille diffère. [Poids, URL, SHA-256 et MD5 constatés](poids-editeurs.json) · [Licences et sources techniques](references.md)

```bash
python - <<'PY'
import hashlib, json
from urllib.request import urlopen

with open('r26/poids-editeurs.json') as f:
    weights = json.load(f)
for name, record in weights.items():
    h, n = hashlib.sha256(), 0
    with urlopen(record['url'], timeout=60) as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
            n += len(chunk)
    assert (h.hexdigest(), n) == (record['sha256'], record['octets']), name
    print(name, n, h.hexdigest())
PY
```

Le protocole prospectif documente le seul candidat, la conversion des annotations, l’initialisation, les budgets, les métriques et les règles de décision. Il ne lance aucun apprentissage. [Protocole](protocole.md) · [Configuration](configuration.json)
