Lis AGENTS.md puis relis l'état courant du dépôt sans modifier de fichiers.

Objectif :
vérifier que le travail en cours est cohérent avec la V1 de LMarchery.

Contrôle :
- git status
- présence de AGENTS.md
- cohérence entre documentation et code
- absence de CameraX, OpenCV, TFLite, ONNX, Compose et Room si la tâche porte seulement sur le moteur de score
- cohérence du moteur de score avec docs/regles-score.md
- cohérence des modèles avec docs/modele-donnees-v1.md
- cohérence de l'export avec docs/export-format-v1.md
- état des tests

Contraintes :
- ne modifie aucun fichier
- ne crée pas de commit
- ne fais pas de push

Donne :
- résumé de l'état actuel
- points OK
- problèmes éventuels
- corrections recommandées
- commandes à lancer
