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
journal durable. Les données sont locales, sans export utilisateur ; désinstallation
et effacement des données peuvent les perdre.

| Vérification physique | Résultat à renseigner |
| --- | --- |
| Téléphone / version Android / date / testeur | Non exécuté par la validation JVM |
| Rotation et arrière-plan | À tester |
| Arrêt complet après deux impacts et reprise exacte | À tester |
| Troisième impact, validation, deuxième volée et clôture | À tester |
| Nouvelle ouverture en lecture seule | À tester |
| Quatre profils, lisibilité et doubles appuis | À tester |

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