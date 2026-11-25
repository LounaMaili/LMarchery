# Rejouer R21 depuis GitHub

Le rejeu des sorties et de la décision nécessite les références train déjà versionnées, les PNG de masques décodés et les journaux de R21. Il s’exécute sans photographie source, poids de modèle, inférence ni entraînement. Les versions de calcul sont CPython 3.15.2, NumPy 2.5.4 et OpenCV headless 4.13.0.92. L’exécution originale des ajustements a utilisé PyTorch 2.14.1+cpu avec quatre threads, un interop, un OpenCV et un BLAS. Les figures sont des SVG autonomes. [Environnement minimal et empreintes des roues](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [Bibliothèques du tronc](../requirements-r8-cpu.txt)

Depuis `vision-lab/`, avec l’environnement minimal du laboratoire :

```sh
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
OUT=$(mktemp -d)
python -m r21.inventorier "$OUT"
python -m r21.mesures "$OUT"
python - "$OUT" <<'PY'
from pathlib import Path
import sys
from r21.publier import histories
histories(Path('r21/archives'),Path(sys.argv[1]))
PY
python -m r21.tableaux "$OUT"
python -m r21.figurer "$OUT"
for f in decision.json inventaires.json lecture-sorties-R20.json parite-R19.json pertes-et-expositions.json groupes.md photos.md identites.md pertes.md
do
    cmp "r21/$f" "$OUT/$f" || exit 1
done
diff -r r21/mesures "$OUT/mesures"
diff -r --exclude=README.md r21/figures "$OUT/figures"
python -m unittest -v test_r21 test_r21_archives
```

Les comparaisons d’octets s’entendent avec les versions indiquées. La vérification des pertes différentiables et des gradients sur tenseurs synthétiques utilise en plus `python -m unittest -v test_r21_cpu`. Les quatre contrôles CPU doivent tous s’exécuter. Les contrôles des archives rejouent les affectations et les 4 608 lignes de journal, recomptent les couplages admissibles par un second algorithme de cardinalité et comparent les 27 sorties décodées du témoin 96 à R20 A. [Tests du gel](../test_r21.py) · [Tests des pertes](../test_r21_cpu.py) · [Tests de livraison](../test_r21_archives.py)

## Registres publics

| Bras et graine | Journal des 768 mises à jour | Mesures au pas 96 | Mesures au pas 768 |
|---|---|---|---|
| Témoin 9301 | [Pertes et requêtes positives/négatives](archives/temoin/9301/progression.jsonl) | [État 96](mesures/temoin-9301-96.json) | [État 768](mesures/temoin-9301-768.json) |
| Témoin 9302 | [Pertes et requêtes positives/négatives](archives/temoin/9302/progression.jsonl) | [État 96](mesures/temoin-9302-96.json) | [État 768](mesures/temoin-9302-768.json) |
| Témoin 9303 | [Pertes et requêtes positives/négatives](archives/temoin/9303/progression.jsonl) | [État 96](mesures/temoin-9303-96.json) | [État 768](mesures/temoin-9303-768.json) |
| Candidat 9301 | [Pertes et requêtes positives/négatives](archives/candidat/9301/progression.jsonl) | [État 96](mesures/candidat-9301-96.json) | [État 768](mesures/candidat-9301-768.json) |
| Candidat 9302 | [Pertes et requêtes positives/négatives](archives/candidat/9302/progression.jsonl) | [État 96](mesures/candidat-9302-96.json) | [État 768](mesures/candidat-9302-768.json) |
| Candidat 9303 | [Pertes et requêtes positives/négatives](archives/candidat/9303/progression.jsonl) | [État 96](mesures/candidat-9303-96.json) | [État 768](mesures/candidat-9303-768.json) |

Chaque dossier de graine contient un registre d’exécution et deux sous-dossiers `96/` et `768/`. Dans chaque état, `predictions.json` donne les coordonnées en repère 704, les segments et, pour les douze requêtes, la probabilité de présence, le pic spatial et leur produit, le score final. Le plan PNG uint16 de chaque photo contient un bit par masque de requête. `pertes.json` conserve les logits de présence, les champs supervisés par identité, le coût d’affectation, la requête positive de chaque cible et les négatifs de présence. `controle.json` donne l’exposition et les empreintes des poids et de l’optimiseur. Le journal des mises à jour publie l’affectation courante. [Exemple de prédictions](archives/candidat/9302/768/predictions.json) · [Pertes et logits correspondants](archives/candidat/9302/768/pertes.json) · [Contrôle des empreintes](archives/candidat/9302/768/controle.json)

Le [registre local](inventaire-local.json) fournit les noms, rôles, octets, SHA-256 et emplacements du tronc R9, des trois poids R19 de parité, des douze paires réseau/optimiseur et de la capsule complète. Les sources et les photographies train conservent les droits et les empreintes indiqués dans R18. Le registre des vues documente les corps actifs à entrée inconnue et les identités documentaires écartées. [Inventaire local des acquisitions](../r18/inventaire-local.json) · [Sources et licences](../r18/sources.json) · [Registre des neuf vues](inventaires.json)

## Exécution d’origine du 25 novembre 2026

Le registre et le protocole ont été publiés et leur arbre GitHub vérifié au SHA `54769728ff2e02949841b29f0ba74519b1683130` avant le premier ajustement. L’ordre réel a été trois témoins puis trois candidats. La commande suivante décrit les six opérations historiques, déjà achevées, avec le dossier de sortie de l’exécution originale. Le conditionnement et le rejeu ci-dessus permettent de les auditer.

```sh
for bras in temoin candidat
do
    for graine in 9301 9302 9303
    do
        python -m r21.executer "$bras" "$graine" ../.. artifacts/r21/comparaison || exit 1
    done
done
python -m r21.publier artifacts/r21/comparaison r21
```

La parité est calculée sur la totalité des paramètres et tampons, triés par nom, avec type, forme et SHA-256 des octets contigus CPU. Le SHA canonique est distinct de celui du fichier sauvegardé par Torch. La phase de conditionnement vérifie les comptes de la perte et les fichiers locaux avant publication. Les douze états mesurés et la décision publiée constituent la première série R21. [Parité des trois témoins](parite-R19.json) · [Gel scientifique antérieur](https://github.com/LounaMaili/LMarchery/commit/54769728ff2e02949841b29f0ba74519b1683130) · [Verdict R21](../r21.md)
