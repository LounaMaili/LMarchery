# ADR 049 — Presets de séance locaux

Dans « Nouvelle séance configurable », le bouton « Enregistrer comme preset »
enregistre les réglages du formulaire sous un nom, indépendamment de la création
de la séance. « Choisir un preset » recopie le blason, la distance, le mode de
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
Les presets par défaut, la modification et la suppression restent des
travaux distincts.
