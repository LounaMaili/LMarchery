# Rejouer le laboratoire R6

Partir de `vision-lab/` dans le worktree R6. Les commandes utilisent les worktrees de référence R3 à R5 avec leurs artefacts locaux. Le [guide R3/R4](../README.md) décrit leur reconstruction. Laisser chaque exécution créer ses répertoires de sortie.

```bash
R3=../../vision-lab-r3/vision-lab
R4=../../vision-lab-r4/vision-lab
R5=../../vision-lab-r5/vision-lab
R3_PY="$R3/data/r3-cpu-venv/bin/python"
R3_MODEL="$R3/artifacts/model-r3-cuda/model.pt"
R3_POLICY="$R3/artifacts/r3-calibration/policy.json"
mkdir -p artifacts
"$R3_PY" -m unittest -v
"$R3_PY" -m r6.corpus data/r6-boards
"$R3_PY" -m r6.diagnose "$R3/data/r3-dev-corpus" "$R4/data/r4-public-test" \
  "$R3_MODEL" "$R3_POLICY" artifacts/diagnostics-final.json
"$R3_PY" -m r5.review starter "$R5/data/r5-seconde-lecture" data/r6-lecture-aveugle
cp r3/annotate.html r3/annotate.js data/r6-lecture-aveugle/
```

Le manifeste des 104 rendus Fedora est figé dans `transfer.MANIFEST`. Les pixels, masques et poids initiaux sont vérifiés par empreinte. Les versions du runtime historique sont CPython 3.15.2, PyTorch 2.14.1+cpu, NumPy 2.5.4 et OpenCV 4.13.0. Les [dépendances CPU R3](../requirements-r3-cpu.txt) et [dépendances du laboratoire](../requirements-ci.txt) sont épinglées. Un manifeste différent demande d’établir la reproductibilité du rendu avant une comparaison.

## Conversion de diagnostic avant entraînement

Deux environnements locaux évitent de modifier l’environnement historique. L’interpréteur 3.12 est nécessaire ici à la roue Linux ONNX Runtime choisie. Régler `PY312` sur le chemin de cet interpréteur. Les paquets sont installés pendant la préparation. L’exécution des graphes fonctionne hors ligne.

```bash
"$R3_PY" -m pip install --only-binary=:all: --target artifacts/export-libs \
  onnx==1.23.2 onnxscript==0.7.2 numpy==2.5.5
"$PY312" -m venv artifacts/ort-env
artifacts/ort-env/bin/pip install --only-binary=:all: \
  onnxruntime==1.23.2 onnx==1.20.0 opencv-python-headless==4.13.0.92 numpy==2.5.5
PYTHONPATH=artifacts/export-libs "$R3_PY" -m r6.export data/r6-boards \
  "$R3_MODEL" artifacts/ort-env/bin/python artifacts/r6-export
"$R3_PY" -m r6.transfer data/r6-boards "$R3_MODEL" artifacts/transfer
for variant in float int8; do
  artifacts/ort-env/bin/python -m r6.offline "$R4/data/r4-public-test" \
    "artifacts/r6-export/$variant.onnx" "artifacts/offline-$variant-final.json"
done
```

`artifacts/transfer/{scratch,r3}-{6021,6022}/` contient `model.pt`, la politique sélectionnée et ses pertes, puis les résultats dev/test. Toutes les politiques sont enregistrées avant la première lecture des métriques de test. Le graphe flottant et le graphe à convolutions quantifiées du témoin R3 sont dans `artifacts/r6-export/`. Les résultats complets par support sont dans les JSON locaux. Les empreintes de départ et d’arrivée sont dans le [relevé](mesures.json).

Les branches, PR et contrats produit gardent leur cycle propre. Pour relancer la vérification de référence du dépôt, exécuter `./scripts/dev-check.sh` à sa racine. La [procédure R5](../r5.md) décrit le retour des lectures et l’adjudication.
