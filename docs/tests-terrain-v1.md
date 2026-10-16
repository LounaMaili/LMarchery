# Tests terrain V1 — LMarchery

> Grilles de test pour valider l'application en conditions réelles, dès les premières phases

---

## Protocole Android persistant (PR 16)

**Statut : protocole à exécuter sur un vrai téléphone ; aucun résultat physique
n'est déduit des tests JVM, du build ou du lint.** Les grilles V1 plus bas comprennent
aussi des fonctions futures ; ne pas les marquer réussies avec ce premier parcours.

Préparer un téléphone Android 8.0/API 26 ou supérieur. Construire l'APK avec
`./scripts/dev-check.sh --rerun-tasks --console=plain`, puis installer
`app/build/outputs/apk/debug/app-debug.apk` (installation debug). Avec un téléphone
USB autorisé et les Platform Tools :

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ne pas désinstaller ni utiliser `pm clear` pour tester la reprise : cela effacerait
les données. Utiliser des séances d'essai synthétiques ; ne pas committer les
captures, bases ou données personnelles. Aucun compte ni réseau n'est nécessaire.

1. Lancer LMarchery. Vérifier la liste existante ou « Aucune séance enregistrée ».
2. Créer « Essai reprise », classique 40 cm/18 m. Vérifier trois flèches,
   entraînement, bon profil/distance, puis attendre la confirmation de création.
3. Placer **deux** impacts distincts, loin des traits pour cette vérification de
   base. Après chaque appui, attendre la confirmation d'enregistrement et le retour
   des commandes. Noter scores, positions et total provisoire. Le total validé doit
   rester zéro ; validation/clôture sont bloquées pour cette volée partielle.
4. Faire tourner l'écran, verrouiller/déverrouiller et passer par l'accueil Android.
   Vérifier les deux impacts, leurs numéros et les totaux, sans doublon. Le bouton
   Retour de LMarchery revient à la liste et ne supprime pas la séance.
5. Arrêter **complètement** le processus. Par exemple, une fois les écritures confirmées :

   ```bash
   adb shell am force-stop com.lmarchery
   ```

6. Relancer par l'icône. La liste doit proposer « Essai reprise », **En cours**.
   L'ouvrir : retrouver exactement les deux impacts, scores, titre, profil et date
   civile avec fuseau/offset. Aucun impact ne doit être ajouté par la reprise.
7. Placer le troisième impact (un coin extérieur au cercle permet de vérifier M/0).
   Vérifier le total provisoire et l'absence de X. Valider la volée : son total
   devient validé, le provisoire revient à zéro et aucun impact ne peut y être ajouté.
8. Créer une deuxième volée vide. Terminer la séance : cette volée reste vide,
   aucune donnée n'est effacée et le total validé ne change pas. Variante à tester
   séparément : saisir un impact dans la deuxième volée ; la clôture doit être
   bloquée jusqu'à trois impacts et validation de cette volée.
9. Revenir à la liste, fermer totalement, relancer puis rouvrir la séance terminée.
   Vérifier **Terminée / lecture seule**, les deux volées, les positions et scores.
   Les boutons de saisie, validation, nouvelle volée et clôture ne permettent plus
   aucune modification. La sélection d'une ancienne volée permet sa consultation.
10. Répéter avec poulies 40 cm, puis les deux profils 60 cm : bon diamètre/distance,
    10 poulies dessiné à la moitié du rayon du 10 classique, aucun X. Les comparaisons
    numériques exactes (r=0.075, limites) sont couvertes par les tests JVM ; une
    approximation tactile ne démontre pas un contact physique du fût.
11. Vérifier le défilement sur petit écran, portrait/paysage, taille de texte accrue,
    contraste, coins M et réactivité. Faire deux appuis rapides pendant une écriture :
    pas de double commande ni faux message de sauvegarde. Les diagnostics d'erreur
    et de conflit sont couverts par tests simulés, sans saturer volontairement le
    stockage d'un téléphone contenant des données réelles.

Après un arrêt **pendant** une écriture, ne rien présumer : relancer et consulter
ce que SQL a effectivement committé. Une proposition non confirmée n'est pas un
journal durable. Les données sont locales ; désinstallation et effacement des
données peuvent les perdre. L’export ajouté en PR 19 est testé dans son protocole
dédié ci-dessous ; aucun import utilisateur n’est encore disponible.

| Vérification physique | Résultat à renseigner |
| --- | --- |
| Téléphone / version Android / date / testeur | Non exécuté par la validation JVM |
| Rotation et arrière-plan | À tester |
| Arrêt complet après deux impacts et reprise exacte | À tester |
| Troisième impact, validation, deuxième volée et clôture | À tester |
| Nouvelle ouverture en lecture seule | À tester |
| Quatre profils, lisibilité et doubles appuis | À tester |

---

## Protocole sélection et repositionnement (PR 17)

Ce protocole complète celui de la PR 16. Ne jamais modifier une séance réelle
ou la séance terminée `TEST AUTO PR16`. Pour la campagne ADB autorisée, créer
**au maximum une** séance `TEST AUTO PR17`. Réutiliser cette même séance pour
annuler puis confirmer ; ne pas désinstaller, effacer les données ou migrer la base.
Identifier le port actuel avec `adb devices -l` et utiliser `adb -s <série exacte>`
pour toutes les commandes destinées au téléphone. Arrêter les essais dépendants
du téléphone s'il est verrouillé, hors ligne ou non autorisé, sans contourner l'accès.

1. Installer l'APK debug avec `install -r`, après validation complète. Arrêter si
   les signatures sont incompatibles ; ne pas désinstaller pour débloquer.
2. Créer la séance synthétique classique 40 cm/18 m, puis placer trois flèches
   distinctes. Attendre chaque confirmation SQL ; relever positions et scores.
3. Choisir la **deuxième** flèche dans la liste et « Repositionner ». Vérifier
   son anneau de sélection et le mode explicite. Proposer un autre point (par
   exemple passer du 10 vers le 7), sans ajout de quatrième flèche. Vérifier
   ancienne position/score et candidat/aperçu clairement séparés, totaux inchangés.
4. Faire tourner l'écran si sûr, puis restaurer l'orientation initiale. Vérifier
   que la sélection et le candidat restent ceux de la deuxième flèche.
5. Annuler : aucune écriture ni trace, les trois flèches et les totaux restent
   identiques. Recommencer et confirmer. Attendre l'acquittement ; vérifier le
   nouveau score, les deux autres impacts intacts, même UUID et création, une
   seule trace ajoutée conservant l'ancienne décision.
6. Après acquittement, faire un `am force-stop com.lmarchery`, relancer, retrouver
   la séance et contrôler les trois impacts, leurs UUID/points/scores et la trace
   persistée si le diagnostic debug le permet. Aucun fichier privé n'est publié.
7. Valider la volée. La sélection/repositionnement doit être indisponible et le
   blason consultable seulement. Aucun essai ne rouvre une volée validée.

Vérifier séparément le retour avec abandon/refus d'abandon avant confirmation et
la sélection depuis la liste de flèches superposées. Sur une fixture contrôlée
avec ancienne décision manuelle, le motif est obligatoire ; aucun écran de
jugement n'est ajouté pour fabriquer cette situation. Les tests du contrôleur
couvrent les conflits, erreurs, retry exact et double confirmation sous sauvegarde
suspendue, sans saturation du stockage d'un téléphone personnel.

| Vérification PR 17 | Résultat à renseigner séparément |
| --- | --- |
| Tests contrôleur et SQLite natif | Voir les rapports de la validation locale/CI |
| ADB : trois impacts, sélection #2, candidat et annulation | Réussi le 16 octobre 2026 sur Pixel 8 / Android 17 ; comparaison SQL sans changement après annulation |
| ADB : confirmation, reprise exacte, deux autres flèches préservées | Réussi : 10 → 7 sur #2, trois impacts conservés, une trace, UUID/création conservés ; reprise après force-stop |
| ADB : rotation du candidat et refus après validation | Réussi ; orientation d'origine restaurée et candidat conservé ; sélection/repositionnement retirés après validation |
| Au doigt : précision, superposition, lisibilité, gants et soleil | Non couvert par ADB ; test humain requis |

Captures, logs et copies de diagnostic restent hors Git. Une proposition non
confirmée disparaît après mort du processus ; ne pas la présenter comme durable.
La campagne ADB a aussi vérifié le refus d'abandon par Retour et la conservation
intégrale de `TEST AUTO PR16`, sans l'ouvrir pour la modifier. Aucune erreur
applicative ni crash/ANR observé dans les diagnostics du paquet. Une assertion
d'automatisation a nécessité de faire défiler jusqu'au message de succès hors
écran ; elle n'a entraîné aucune deuxième soumission de la correction.
Voir [ADR 019](adr/019-selection-repositionnement-android.md).

---

## Protocole décisions de score (PR 18)

Conserver intactes `TEST AUTO PR16` et `TEST AUTO PR17`. Si ADB Wi-Fi est encore
disponible, inventorier `adb devices -l` et employer la série exacte actuelle.
Ne pas contourner un verrouillage, désinstaller ou effacer les données. Créer
**au maximum une** séance synthétique `TEST AUTO PR18`, classique 40 cm/18 m.
Les copies privées de diagnostic, captures et logs restent hors Git.

1. Installer l'APK validé avec `install -r`. Créer la séance et saisir trois
   impacts, dont un proche de 10/9. Attendre chaque acquittement. La proximité
   seule doit conserver le score géométrique.
2. Sélectionner cette flèche par numéro, « Corriger le score », choisir la limite
   puis un côté. Vérifier les trois scores affichés et l'absence de déplacement.
   Annuler ; comparer la base avant/après : aucune modification.
3. Recommencer et confirmer. Vérifier `BOUNDARY_JUDGMENT`, référence de limite,
   valeur et date UTC sur disque, sans changement d'UUID, point ou création,
   sans trace de déplacement. Le total change seulement après acquittement.
4. Force-stop après confirmation puis relancer. Rouvrir cette seule séance et
   vérifier exactement la décision précédente. Une rotation pendant une
   proposition doit conserver les choix dans le ViewModel.
5. Sélectionner une flèche, choisir « Correction exceptionnelle », une valeur
   légale et un motif synthétique. Les profils indoor n'offrent pas X. Un motif
   blanc bloque la confirmation. Confirmer, arrêter puis relancer ; vérifier
   type, date et motif persistés à coordonnées identiques.
6. Sur cette flèche manuelle, proposer un repositionnement. Un motif est exigé
   avant confirmation. Si confirmé, vérifier nouvelle géométrie et ancienne
   décision conservée dans la trace, avec motif de déplacement distinct.
7. Vérifier secondairement le choix d'une limite hors suggestions, M au bord,
   un jugement conservant le même score, le retour avec refus d'abandon et
   la lisibilité en portrait/paysage. Ne pas modifier les séances protégées.

| Vérification PR 18 | Résultat à renseigner séparément |
| --- | --- |
| Contrôleur, SQLite disque, rollback et concurrence | Voir rapports de validation locale/CI |
| ADB : jugement, annulation sans écriture, confirmation et reprise | Réussi le 16 octobre 2026 sur Pixel 8 / Android 17 : géométrie 9 sans promotion, annulation SQL identique, jugement explicite vers 10 puis reprise avec limite/date/provenance |
| ADB : correction exceptionnelle, motif persistant et repositionnement motivé | Réussi : correction vers 5, motif obligatoire, reprise exacte sans déplacement ; repositionnement ultérieur vers 8 avec motif distinct et décision exceptionnelle invalidée conservée dans une trace |
| ADB : préservation complète de PR16 et PR17 | Réussi : comparaison des séances, volées, impacts, décisions et traces avec la référence initiale, sans modification |
| Au doigt : lisibilité, choix des côtés, petits écrans, gants et soleil | Non couvert par ADB |

Les décisions successives à position constante ne forment pas un journal
complet ; la nouvelle décision remplace la courante. Les tests d'erreur/conflit
utilisent des magasins synthétiques : ne pas saturer le stockage personnel.
La campagne a créé une seule séance PR18 et conservé trois impacts. Les copies
SQL confirment les mêmes bits de coordonnées, UUID et création après les deux
décisions de score, et aucune trace avant le déplacement explicite. La rotation
du nouveau candidat n'a pas été rejouée physiquement dans cette campagne ; la
conservation du contrôleur est couverte par les tests JVM. Les helpers ADB
restent hors dépôt et ne constituent pas une suite instrumentée Android.
Voir [ADR 020](adr/020-decisions-score-android.md).

---

## Protocole export JSON (PR 19)

Ne pas créer de nouvelle séance ni modifier `TEST AUTO PR16`, `TEST AUTO PR17`
ou `TEST AUTO PR18`. Inventorier `adb devices -l` et les services mDNS si le port
a changé. Utiliser le transport actuel vérifié. Si le téléphone est verrouillé,
hors ligne ou non autorisé, arrêter les tests dépendants sans contourner l'accès.
Ne pas désinstaller, exécuter `pm clear`, supprimer la base ou publier un export.

1. Construire et valider l'APK, puis `adb -s <série> install -r ...` si la signature
   est compatible. Comparer les données Room synthétiques avant/après installation.
2. Ouvrir l'accueil, retrouver les trois séances protégées et « Exporter mes séances ».
   Le message rappelle que le fichier est en clair et que l'import reste indisponible.
3. Tester une annulation du sélecteur : aucun contenu écrit ni succès annoncé.
4. Relancer l'export global. Choisir un dossier **local de test** dans le sélecteur
   Android, avec le nom `.lmarchery.json` proposé. Ne pas choisir un service externe.
5. Attendre le succès après relecture ; relever nombre de séances, taille et nom.
   Le fichier doit exister et être non vide. Récupérer uniquement ce fichier de
   test autorisé si l'accès le permet ; garder les preuves privées hors du dépôt.
6. Décoder/reconstruire indépendamment le fichier sur JVM et comparer profils,
   séances, coordonnées bit à bit, décisions, dates et traces aux données Room.
   Aucune donnée utilisateur n'est envoyée sur GitHub ou dans les logs CI.
7. Comparer toutes les tables Room avant/après : aucune modification. Faire un
   force-stop après succès, relancer et retrouver les trois séances intactes.
8. Tester la rotation pendant le choix de destination si sûr : un seul sélecteur,
   pas de double export. Les interruptions d'écriture, fournisseurs défaillants et
   conflits restent testés avec flux/magasins synthétiques, sans saturer le téléphone.

| Vérification PR 19 | Résultat physique à renseigner |
| --- | --- |
| Téléphone, Android, date | Pixel 8 / Android 17, 16 octobre 2026 ; port Wi-Fi courant identifié par mDNS, téléphone déverrouillé avant interaction ; APK installé avec `install -r` |
| Annulation SAF sans faux succès | Réussi : retour Android, message d’annulation, aucun succès ; nouvelle tentative possible |
| Création locale, taille, nombre et relecture indépendante | Réussi dans un dossier local de test choisi via SAF : 19 342 octets, 3 séances, 1 profil, 9 impacts, 2 traces. Relecture Android puis reconstruction JVM indépendante ; égalité complète avec l’archive issue de SQLite source et réencodage identique octet par octet |
| Tables Room et séances PR16/17/18 intactes | Réussi : toutes les tables comparées avant installation, après export et après relance ; contenu identique, intégrité SQL et clés étrangères vérifiées ; aucun effacement |
| Force-stop et reprise | Réussi : trois séances synthétiques retrouvées dans la liste après arrêt complet et relance |
| Rotation pendant le sélecteur | Non exécutée sur téléphone ; conservation de la requête et absence de double lancement testées sur le coordinateur JVM |
| Autres fournisseurs, manque d'espace, ergonomie au doigt | Non démontrés par les tests JVM |

Un fournisseur sans relecture ne permet pas de confirmer l'export. Après erreur,
fermeture en échec ou interruption, le document peut être vide/incomplet : ne pas
l'utiliser. Il n'est pas supprimé automatiquement. Aucune fonction d'import n'est
ajoutée pour ce test. Voir [ADR 021](adr/021-export-json-versionne-android.md).

---

## Principes

- Les tests terrain commencent **dès la phase 2** (blason interactif), pas uniquement en fin de projet
- Chaque test est fait sur un **vrai téléphone** Android, dans des **conditions réelles**
- L'objectif est de découvrir les problèmes ergonomiques et techniques avant qu'ils ne soient gravés dans l'architecture

---

## Test 1 — Blason interactif

**Quand** : dès que le blason interactif est fonctionnel (Epic 2)

| Critère | Résultat |
|---------|----------|
| **Testeur** | |
| **Téléphone** | |
| **Date** | |
| **Luminosité** | ☐ Salle ☐ Extérieur soleil ☐ Extérieur ombre |
| **Rapidité de saisie** | Temps pour placer 3 impacts : ___ s |
| **Précision du toucher** | ☐ Correct ☐ Trop sensible ☐ Pas assez |
| **Correction d'un impact** | ☐ Facile ☐ Difficile ☐ Bug |
| **Suppression d'un impact** | ☐ Facile ☐ Difficile ☐ Bug |
| **Usage avec gants / doigts froids** | ☐ Testé ☐ Pas testé |
| **Saisie entre deux volées** | ☐ Assez rapide ☐ Trop lent ☐ Gênant |
| **Lisibilité en plein soleil** | ☐ Lisible ☐ Difficile ☐ Illisible |
| **Remarques libres** | |

**Critère de réussite** : un archer peut saisir une volée rapidement sans avoir l'impression que l'application le gêne dans sa séance.

---

## Test 2 — Séance complète

**Quand** : dès que les séances sont fonctionnelles (Epic 3)

| Critère | Résultat |
|---------|----------|
| **Testeur** | |
| **Téléphone** | |
| **Date** | |
| **Lieu** | |
| **Type de séance** | ☐ Salle ☐ Extérieur |
| **Nombre de volées testées** | |
| **Temps moyen de saisie d'une volée** | ___ s |
| **Création de séance** | ☐ Facile ☐ Confus ☐ Bug |
| **Calcul du score total** | ☐ Correct ☐ Erreur |
| **Correction d'une erreur en cours** | ☐ Facile ☐ Difficile ☐ Impossible |
| **Reprise après verrouillage téléphone** | ☐ OK ☐ Données perdues ☐ Crash |
| **Reprise après changement d'appli** | ☐ OK ☐ Données perdues ☐ Crash |
| **Consultation de l'historique** | ☐ OK ☐ Bug |
| **Erreurs observées** | |
| **Remarques libres** | |

**Critère de réussite** : l'application survit à une vraie séance d'entraînement, du premier impact au stockage en historique.

---

## Test 3 — Historique et export

**Quand** : dès que l'historique et l'export sont fonctionnels (Epic 5)

| Critère | Résultat |
|---------|----------|
| **Testeur** | |
| **Téléphone** | |
| **Date** | |
| **Retrouver une séance passée** | ☐ Facile ☐ Difficile ☐ Bug |
| **Consulter le détail** | ☐ OK ☐ Incomplet ☐ Bug |
| **Export JSON** | ☐ OK ☐ Erreur |
| **Import sur un autre appareil** | ☐ OK ☐ Données manquantes ☐ Doublons ☐ Pas testé |
| **Remarques libres** | |

---

## Test 4 — Cas limites

**Quand** : en fin de MVP, avant release

| Critère | Résultat |
|---------|----------|
| **Testeur** | |
| **Séance interrompue (appel entrant)** | ☐ OK ☐ Données perdues ☐ Crash |
| **Nombre libre de flèches par volée** | ☐ OK ☐ Bug |
| **Score sur le cordon (mode compétition)** | ☐ Score correct ☐ Score incorrect ☐ Pas testé |
| **Toutes flèches en 10** | ☐ Score correct ☐ Bug |
| **Toutes flèches en M (miss)** | ☐ Score correct ☐ Bug |
| **Séance avec 0 volée** | ☐ Géré ☐ Crash |
| **Séance vide sauvegardée** | ☐ Géré ☐ Crash |
| **Saisie en luminosité difficile** | ☐ OK ☐ Difficile ☐ Illisible |
| **Remarques libres** | |

---

## Test 5 — Matériel et presets

**Quand** : dès que le matériel et les presets sont fonctionnels (Epic 4)

| Critère | Résultat |
|---------|----------|
| **Testeur** | |
| **Création d'une fiche arc** | ☐ Facile ☐ Confus ☐ Bug |
| **Création d'une fiche flèches** | ☐ Facile ☐ Confus ☐ Bug |
| **Création d'un preset** | ☐ Facile ☐ Confus ☐ Bug |
| **Lancement séance depuis preset** | ☐ 2 taps ☐ Plus ☐ Bug |
| **Modification d'un preset** | ☐ OK ☐ Bug |
| **Remarques libres** | |

---

*Dernière mise à jour : 29 avril 2026*
