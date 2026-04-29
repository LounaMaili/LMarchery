# Tests terrain V1 — LMarchery

> Grilles de test pour valider l'application en conditions réelles, dès les premières phases

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