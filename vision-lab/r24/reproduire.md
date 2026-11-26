# Reproduire R24

Depuis `vision-lab/`, utiliser CPython 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0. Le calcul neuronal utilise Torch 2.14.1+cpu et TorchVision 0.29.1+cpu sur x86-64. Les exigences du dépôt fixent les versions et les SHA-256 des roues. [Dépendances minimales](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [TorchVision CPU](../requirements-r8-cpu.txt)

## Vérification du dossier GitHub

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
out_r24=$(mktemp -d)
python -m r24.diagnostic "$out_r24/diagnostic"
diff -r r24/diagnostic "$out_r24/diagnostic"
python -m r24.auditer
```

L’audit reconstruit les matrices du candidat depuis les douze sorties décodées de chaque photo, les bilans, les tableaux et les trois figures. Il vérifie les critères indépendamment en partant des paires de couverture et des triplets de décision. Le PNG uint16 d’un état code les douze masques par bits. Il ne contient aucun pixel de photo. Seules les distances géométriques bénéficient des deux ULP de rejeu R22. Les règles d’appariement et les autres champs restent exacts. Les références sont le témoin historique R21 et le candidat Dice de R22. [Validation](auditer.py) · [Motif initial](reorientation.md)

## Commandes de l’exécution originale

Le répertoire `worktrees` doit contenir les capsules identifiées dans les manifestes. Le répertoire de sortie des expériences doit être neuf. Les contrôles de comparabilité et les tests synthétiques précèdent les passages sur les données réelles. `figer` constitue la configuration lors de la préparation du protocole.

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
worktrees=../..
out_r24=$(mktemp -d)
python -m r24.verifier "$worktrees" "$out_r24/comparabilite.json"
cmp r24/comparabilite.json "$out_r24/comparabilite.json"
python -m unittest -v test_r24 test_r24_cpu test_r22_cpu test_r21_cpu
for graine in 9301 9302 9303
do
    python -m r24.executer "$graine" "$worktrees" "$out_r24/experience/bloc8" || exit 1
done
python -m r24.conditionner "$out_r24/experience/bloc8" "$out_r24" --worktrees "$worktrees"
python -m r24.mesures "$out_r24" --archives "$out_r24/archives/bloc8"
python -m r24.presenter "$out_r24" --archives "$out_r24/archives/bloc8"
```

Les journaux complets enregistrent les pertes et les normes avant/après écrêtage par groupe, ainsi que le déplacement réel après chaque mise à jour. Les trois tableaux d’instrumentation agrègent ces valeurs par fenêtre temporelle, provenance et photo. Pour le témoin R22, les journaux originaux contiennent les pertes, la norme globale et le drapeau d’écrêtage. Les déplacements nets entre départ et états 96/768 sont mesurés depuis ses poids archivés. Les normes par groupe et déplacements individuels par pas du témoin ne sont pas archivés. [Journaux R22](../r22/archives/dice) · [Statistiques R22](../r22/pertes-et-expositions.json) · [Écarts calculés sur les poids R22](comparabilite.json)
