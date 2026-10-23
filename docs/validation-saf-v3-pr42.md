# Validation SAF V3 et consultation configurable — PR #42

## État de cette campagne

**Sans appareil, sans ADB et sans installation**. Les tests JVM de coordinateurs
utilisent des doubles de flux ; les tests de stockage utilisent des fichiers
SQLite natifs et documents JSON strictement synthétiques sur le PC. Ils ne
valident pas le fournisseur de documents Android ni les interactions humaines.
Voir [ADR 042](adr/042-saf-v3-consultation-configurable.md) et les résultats exacts
au SHA de la PR. Ne pas reprendre les anciens essais Pixel de #40/#41 comme
preuves de cette nouvelle interface.

| Cas physique | Résultat actuel |
|---|---|
| Création de document SAF V3, fermeture et relecture par fournisseur Android | NON TESTÉ |
| Sélection/import V1, V2 et V3 sur Android | NON TESTÉ |
| Annulation des sélecteurs, refus d’accès et panne de fournisseur | NON TESTÉ |
| Rotation avec sélecteur ouvert, aperçu et confirmation en cours | NON TESTÉ |
| Mort puis relance du processus et retour tardif du sélecteur | NON TESTÉ |
| Lisibilité des profils/configurations mixtes, accessibilité, gros caractères | NON TESTÉ |
| Consultation seule, navigation et exclusion de la visée/correction/matériel | NON TESTÉ sur appareil ; tests JVM exécutés |
| Précision tactile réelle | NON TESTÉ ; hors périmètre |

## Préparation d’une future campagne autorisée

1. Préparer séparément un APK de test à applicationId distinct et vérifier son
   UID/répertoire privé. L’APK debug normal `com.lmarchery` n’est **pas isolé** et
   ne doit pas remplacer l’application de l’utilisateur.
2. Ne jamais consulter, migrer, exporter ou modifier la base réelle. Fournir
   uniquement les archives synthétiques des tests, dont une mixte, une historique
   V1, une V2 avec catalogue et une V3 vide ou matériel seul.
3. Partir d’un stockage de test neuf ou d’une copie synthétique explicitement
   contrôlée. L’APK V3 sait migrer 1→2→3 ; une fois Room 3 ouvert, ne jamais revenir
   à un APK ne supportant que Room 1/2 sur ce même stockage. Conserver les essais
   d’anciens APK dans d’autres identifiants d’application.
4. Relever SHA du code, hash de l’APK isolé, version Android, schéma et fournisseur.
   Ne pas tester d’erreur destructive sur des documents personnels.

## Étapes manuelles futures

1. Exporter V3 depuis une base synthétique vide puis peuplée. Vérifier le nom
   `lmarchery-v3-...lmarchery.json`, choisir un document neuf, attendre le message
   « confirmé après relecture ». Décoder les octets strictement et comparer à
   l’archive issue d’une collecte SQLite cohérente, pas à des captures d’écran.
2. Annuler le sélecteur puis réessayer. Provoquer sur un fournisseur de test une
   fermeture/relecture refusée : aucun succès, document potentiellement incomplet,
   aucune suppression ni réutilisation automatique.
3. Restaurer la V3 mixte dans le stockage isolé : vérifier six catégories dans
   l’aperçu et aucune ligne avant confirmation. Appuyer deux fois sur confirmer :
   un seul acquittement et aucun doublon. Fermer/reprendre puis vérifier UUID,
   captures, profils/éditions, décisions/traces et catalogue.
4. Reprendre le même fichier : nouvel aperçu identique puis confirmation sans
   écriture. Tester archive vide explicite, matériel seul, profil seul et référence
   matériel non résolue ; aucune suppression ni réparation implicite.
5. Tester un conflit et un aperçu périmé via un autre écrivain **synthétique** :
   aucune restauration partielle. Une incertitude de commit garde le candidat et
   bloque navigation/édition/matériel/export. « Relire » prépare seulement un
   nouvel aperçu ; une nouvelle confirmation est indispensable. « Fermer » avertit
   qu’une ancienne transaction peut être déjà enregistrée.
6. Ouvrir les séances configurables, y compris une homogène restaurée et une
   séance terminée avec volées provisoires. Vérifier cible/édition et distance
   exactes par volée, origines et vides, scores validés/provisoires séparés. Aucune
   commande d’écriture de séance ou de matériel ; aucun geste ne modifie le blason.
   Revenir à une séance historique : son éditeur reste celui d’avant.
7. Sur une base exclusivement historique, tester les choix V1 et V2 et leur
   restauration. Avec au moins une ligne CONFIGURED, V1/V2 doivent refuser la
   sauvegarde/import global sans omission ni export silencieux dans une autre version.
8. Tourner l’écran durant préparation, sélecteur, aperçu et confirmation ; tester
   aussi l’arrêt du processus isolé. Aucun deuxième sélecteur/commit, candidat
   conservé seulement avec ViewModel vivant, interruption visible sinon. Vérifier
   petits écrans, gros caractères, défilement, retour et diagnostics persistants.

Consigner PASS/FAIL/NON TESTÉ pour chaque cas avec preuves synthétiques limitées.
Un essai non exécuté reste NON TESTÉ. Ne pas modifier les réglages de sécurité,
installer un ancien APK sur Room 3 ni toucher aux autres applications pour obtenir
un résultat vert. Aucune de ces actions physiques n’a été effectuée dans la PR.
