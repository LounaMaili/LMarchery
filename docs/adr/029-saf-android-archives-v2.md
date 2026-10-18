# ADR 029 — Sauvegardes SAF Android V2 et lecture V1

Statut : adopté pour cette implémentation, validation physique SAF à effectuer.

## Contexte

Les [ADR 026](026-contrat-sauvegarde-json-v2.md) et
[027](027-archives-room-v2-atomiques.md) fournissent les deux contrats JSON et
les transactions V2. L’interface Android utilisait encore exclusivement V1.
L’[ADR 028 proposé dans la PR #26](https://github.com/LounaMaili/LMarchery/blob/ae96dbda3973445ff850a77ef4e4cb3de9066b04/docs/adr/028-seances-flexibles-edition-desktop.md)
prépare d’autres évolutions : aucune configuration par volée ni modification de
version Room/JSON liée à cette proposition n’est intégrée ici.

## Décision

- L’export principal est explicitement V2 : un unique `readArchiveV2()` fournit
  toutes les racines, profils non référencés compris, et tous les descendants.
  L’encodage V2 et son aller-retour strict précèdent l’ouverture du sélecteur.
- L’export V1 reste un choix distinct, marqué « sans catalogue matériel ». Son
  codec et ses octets historiques restent inchangés ; il refuse toujours une base
  sans séance. Une V2 de matériel seul, profils seuls ou entièrement vide est
  exportable, sans créer ni supprimer de donnée.
- L’import décode via `decodeVersioned()`. V1 appelle exclusivement les API
  historiques ; V2 appelle exclusivement `previewImportV2()`/`importArchiveV2()`.
  Aucune projection ou rétrogradation V2 vers V1 n’est disponible.
- L’aperçu V2 présente quatre catégories, chacune nouvelle/identique/conflictuelle.
  Une collision bloque l’ensemble. Tout import exige une confirmation explicite.
  Une archive vide ou identique peut être confirmée comme vérification sans
  écriture ; aucune liste vide ne constitue une demande d’effacement.
- Le repository transforme l’aperçu Room en capacité opaque `PreparedV2Import` :
  son plan est lisible, son `commit()` capture l’aperçu exact et son état local.
  L’interface ne peut pas reconstruire un aperçu SQL depuis les comptes affichés.
  Room recalcule les collisions et vérifie la baseline sous verrou écrivain.
- Aucun fichier n’est relu à la confirmation. Le candidat validé reste en mémoire
  dans le ViewModel, jusqu’à confirmation, annulation ou échec. Un aperçu périmé
  exige une nouvelle sélection ; aucun retry avec une baseline récente.

## Cycle de vie et erreurs

Le verrou existant exclut import, export, navigation et écritures de séance.
La transition synchrone hors de l’aperçu empêche une deuxième confirmation.
Les préférences SAF restent des launchers racine, hors des éléments recyclés.
La rotation retrouve le coordinateur et le candidat du ViewModel ; elle ne
relance pas le sélecteur. Une mort du processus perd les octets et le candidat :
le marqueur restauré affiche une interruption, jamais une reprise implicite.

Le lecteur borné conserve le plafond global de 16 MiB et les limites strictes du
codec. Le writer conserve fermeture puis relecture exacte de tous les octets.
Échec, annulation de coroutine ou fermeture impossible ne donnent aucun succès
anticipé. Un fichier partiel n’est ni supprimé ni réutilisé automatiquement.

Un résultat SQL technique ou une annulation pendant la confirmation peut avoir
perdu l’acquittement du commit : état « non confirmé », sans relance automatique.
Relire puis sélectionner à nouveau permet de constater les identiques. Un échec
d’actualisation après un succès connu ne transforme pas ce commit en échec.

## Validation et limites

Les tests JVM des coordinateurs couvrent les deux versions, les archives sans
séance, les quatre catégories, annulations, réattachement, perte de processus,
doubles commandes, aperçu périmé, erreurs techniques et incertitude SQL. Les tests
existants des flux couvrent plafond réel, fermeture, écriture partielle et
relecture différente. Les tests SQLite natifs de l’ADR 027 restent exécutés :
rollback après INSERT réel, concurrence, perte d’acquittement après COMMIT réel,
idempotence sous triggers interdisant les écritures et aller-retour entre deux
bases disque via le codec V2, avec toutes les entités et traces.

Ces preuves ne constituent pas un essai du fournisseur SAF Android, une rotation
réelle ou une validation physique. Le Pixel conservant APK #22 / Room V1 n’est
pas utilisé. Un essai sur installation indépendante devra vérifier choix V1/V2,
annulation, rotation avec sélecteur ouvert, fermeture/relecture, erreur fournisseur
et restauration V2 complète dans une base jetable. Aucun écran matériel ni lien
matériel/séance n’est ajouté dans cette étape.
