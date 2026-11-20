# ADR 049 — Presets de séance locaux

Dans « Nouvelle séance configurable », le bouton « Enregistrer comme preset »
enregistre les réglages du formulaire sous un nom, indépendamment de la création
de la séance. « Mes presets » permet de recopier le blason, la distance, le mode de
flèches, le lieu de tir intérieur/extérieur, le mode de comptage, le nombre
facultatif de volées prévues et les références de matériel. Toute valeur proposée
reste modifiable. Le nom propose un titre si le champ est vide. Les notes et le
lieu saisi librement restent propres à la séance. La séance possède sa
configuration et ses références, sans identifiant de preset.

`SessionPresetDatabase` conserve les presets dans une base Room dédiée de
version 1. Chaque entrée possède un UUID v4, un instant de création et un nom
unique sensible à la casse. Le profil est relu par UUID dans le référentiel
embarqué et son édition doit correspondre exactement. Les références de
matériel restent opaques et une fiche absente peut rester associée. Cette
configuration personnelle ne certifie pas de format de compétition.

Room 4 et les formats JSON V1 à V4 gardent leur périmètre de séances, profils
et matériel. Les presets restent sur l’appareil et une restauration de séance
laisse le catalogue local de presets en place. La sauvegarde des presets peut être livrée en deux étapes : définir leur
document versionné et ses conflits d’identité et de nom, puis raccorder un
export/import SAF dédié avec aperçu et restauration atomique dans leur base.
## Gestion des presets personnels

« Mes presets » propose « Modifier » et « Supprimer » sur chaque preset.
L’édition reprend les réglages existants, y compris les références de matériel
opaques, et conserve l’UUID et l’instant de création. Le formulaire de séance
en cours reste indépendant. Une suppression confirmée est définitive et les
sauvegardes JSON/SAF ne permettent pas de restaurer le preset.

La base dédiée compare la ligne complète attendue dans une transaction écrivain
avant la mise à jour ou la suppression. Un changement concurrent déclenche un
conflit et une relecture du catalogue. Si l’écriture est interrompue ou que
son acquittement manque, le résultat est signalé comme non confirmé. L’ancienne
commande devient invalide et la liste est relue sans nouvelle écriture.
Le nom reste unique et sensible à la casse. Le schéma reste à la version 1.

## Suggestions intégrées

Le formulaire propose cinq réglages d’entraînement sous « Suggestions intégrées »,
indépendamment du chargement de « Mes presets ». Les deux suggestions salle à
18 m utilisent les profils WA 40 cm classique et poulies de l’édition
2026-01-27, avec trois flèches. Les suggestions d’extérieur utilisent le profil
historique 80 cm à 30 et 50 m, et le profil historique 122 cm à 70 m, avec
six flèches. Ces profils historiques n’ont pas d’édition réglementaire déclarée.

Une sélection remplace uniquement les réglages de tir dans le brouillon.
Le titre, le nombre de volées prévues, le lieu, les notes et les références de
matériel déjà choisis sont conservés. La suggestion est modifiable et ne déclenche
aucune écriture. L’archer peut l’enregistrer sous un nom via le parcours de
preset personnel. La base Room et les sauvegardes gardent leur périmètre.
