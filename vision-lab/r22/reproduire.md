# Reproduire R22

Depuis `vision-lab/`, les commandes de revue utilisent les sorties numériques des neuf vues train. Les couleurs de photographie et les poids ne sont nécessaires que pour reproduire les contrôles d’entrée et l’apprentissage. CPython 3.15.2, NumPy 2.5.4 et OpenCV 4.13.0 sont communs aux deux bras. Le calcul différentiable utilise Torch 2.14.1+cpu sur x86_64. Les versions complètes et les sommes des roues figurent dans les manifestes d’environnement. [Roues minimales](../requirements-ci.txt) · [Torch CPU](../requirements-r3-cpu.txt) · [TorchVision](../requirements-r8-cpu.txt) · [Environnement effectivement comparé](comparabilite.json)

## Revue depuis les fichiers GitHub

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
out_r22=$(mktemp -d)
python -m r22.diagnostic "$out_r22/diagnostic"
python -m r22.mesures "$out_r22"
python -m r22.presenter "$out_r22"
diff -r r22/diagnostic "$out_r22/diagnostic"
cmp r22/mesures/groupes.json "$out_r22/mesures/groupes.json"
cmp r22/mesures/contrastes.json "$out_r22/mesures/contrastes.json"
diff -r r22/identites "$out_r22/identites"
diff -r --exclude=README.md r22/figures "$out_r22/figures"
for nom in decision.json pertes-et-expositions.json tableaux.md identites.md transitions.md reculs.md
do
    cmp "r22/$nom" "$out_r22/$nom" || exit 1
done
python -m unittest -v test_r22 test_r22_archives
```

Le test de rejeu compare tous les champs des six rapports bruts. Pour les seules distances euclidiennes d’entrée, il autorise deux unités du dernier bit (ULP) entre noyaux CPU et exige un résultat identique au critère inclusif de 10 px. Tous les autres champs, les valeurs des figures et la décision restent comparés exactement. [Historique de la vérification](corrections.md)

Le diagnostic utilise les JSON de R21. Le rejeu R22 relit les sorties décodées du candidat, dont les PNG uint16 où chaque bit identifie le masque d’une requête. Il reprend les plafonds R20, les cartes de référence R18 et les entrées R21 déjà vérifiées pour les témoins. Le calcul des figures et des tableaux utilise les fichiers de mesures et les identités par état. [Sorties candidates archivées](archives/dice) · [Témoins historiques](../r21/archives/temoin) · [Données numériques des figures](figures/donnees.json)

## Reproduction de l’exécution initiale

Les commandes suivantes décrivent l’exécution bornée publiée. `worktrees` désigne le répertoire qui contient les worktrees historiques nommés dans les manifestes. Utiliser un emplacement de sortie neuf. Les poids initiaux, photos après volée, réseaux et optimiseurs sont identifiés en octets et en SHA-256. Les empreintes canoniques des états portent sur le nom, la forme, le type et les octets de chaque tenseur. L’emplacement absolu d’une copie locale peut varier entre machines. [Inventaire local](inventaire-local.json) · [Sorties du contrôle préalable](comparabilite.json) · [Droits et provenance des images](../r18/sources.json)

```bash
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
worktrees=../..
python -m r22.verifier "$worktrees" "$out_r22/comparabilite.json"
python -m unittest -v test_r22_cpu
for graine in 9301 9302 9303
do
    python -m r22.executer "$graine" "$worktrees" "$out_r22/experience/dice" || exit 1
done
python -m r22.conditionner "$out_r22/experience/dice" "$out_r22" --worktrees "$worktrees"
python -m r22.mesures "$out_r22" --archives "$out_r22/archives/dice"
python -m r22.presenter "$out_r22"
```

Chaque journal publie les 768 pertes, les requêtes affectées et les expositions réelles. Chaque dossier d’état contient les probabilités de présence, les coordonnées, le pic spatial, les segments, les masques binaires et les coûts hongrois. Dans `pertes.json`, le Dice doux concerne la requête affectée à la cible par le coût historique courant. Les distributions de qualité au bilan portent sur les masques binaires et une attribution d’évaluation distincte. [Pertes et expositions](pertes-et-expositions.json) · [Paramètres et plan](configuration.json)
