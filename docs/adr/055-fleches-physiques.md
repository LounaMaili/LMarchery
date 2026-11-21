# ADR 055 — Identité d’une flèche physique

Statut proposé. Le futur catalogue reste distinct des impacts.

- `ArrowImpact.index` est le numéro de l’impact au sein de sa volée. Le numéro historique et l’UUID de l’impact restent inchangés lors d’une correction, d’une annulation ou d’une association de matériel.
- Une **flèche physique** porte un UUID v4 propre, un rattachement à son jeu de flèches et, éventuellement, un repère visible tel qu’un numéro sur le tube. Ce repère peut changer et les doublons restent possibles entre jeux. Le remplacement d’un tube usé introduit une nouvelle identité, même si le même repère est repris.
- L’association de l’impact à un tube est facultative. Sa valeur historique conservera l’UUID et, si nécessaire, le jeu et le repère retenus à cet instant. L’absence distingue un matériel inconnu d’un numéro d’impact connu. Le retrait d’une flèche du catalogue n’efface pas les anciens liens.
- Un tube peut être lié dans différentes volées et séances. Un lien proposé ne peut viser qu’un seul impact actif du même tube dans une volée. L’annulation libère la place active et préserve la preuve avec son association. Une correction du matériel reste distincte d’un repositionnement ou d’une décision de score. Une modification d’association sur une volée figée exigerait une règle métier explicite dans un lot ultérieur.

Android proposerait un choix rapide et facultatif après saisie, en présentant en priorité les tubes du jeu courant et un choix « non renseigné ». Le remplissage par ordre, la réutilisation automatique du numéro d’impact et le rapprochement par libellé restent exclus. Un import peut conserver une référence historique à un tube absent du catalogue. Il ne crée pas de flèche physique sur la seule base d’un ancien numéro.

Le modèle Kotlin/JVM portera les contraintes d’identité et d’association. Desktop le consommera pour grouper les résultats par tube, en gardant les impacts sans attribution dans une catégorie distincte. Aucun calcul du score ne dépendra du tube. Les tests futurs couvriront la réutilisation entre volées, le refus des collisions actives, les annulations et traces, l’absence de renumérotation, et la restauration sans recréer de matériel par déduction.

Le stockage futur pourra utiliser un catalogue par jeu et une association révisable distincte de la ligne Room de l’impact. L’archive versionnée à définir devra préserver UUID, appartenance au jeu, association, annulations et décisions. Les sauvegardes strictes V1 à V4 restent inchangées. Une conversion vers elles devra refuser toute perte implicite. Voir [ADR 048](048-annulation-logique-durable-impacts.md).
