# ADR 052 — Lieux favoris et lignes de tir

Statut adopté pour L1. Le catalogue et les instantanés sont disponibles dans le domaine Kotlin/JVM, Room 5 et la sauvegarde JSON V5.

## Identités et sélection

- Un **lieu favori** porte un UUID v4 stable, un nom et une description facultatifs, et éventuellement une latitude et une longitude WGS84 en degrés. Les coordonnées forment une paire finie, latitude dans `[-90, 90]`, longitude normalisée dans `[-180, 180[`. L’incertitude horizontale en mètres et la provenance de la position sont facultatives. L’absence de localisation est un état normal.
- Une **ligne de tir** porte son propre UUID v4, l’UUID de son lieu, un nom et une description facultatifs et éventuellement une orientation. Un lieu peut avoir plusieurs lignes, y compris de sens opposés. L’orientation va de l’archer vers les cibles et suit l’[ADR 053](053-azimut-et-boussole.md). Le nom, la proximité géographique et l’azimut ne définissent aucune identité.
- À la création d’une séance, Android peut proposer un favori puis une ligne. Un lieu sans ligne et une séance sans lieu restent valides. Une correction manuelle peut différer de la valeur du catalogue. Le découpage ou la réorientation d’une ligne se fait par révision. Si l’accès à une ancienne ligne est retiré, son UUID reste réservé.

## Valeur historique

La séance conserve un instantané explicite des valeurs retenues. Il peut comprendre les UUID de provenance, les libellés, la position et l’orientation confirmées, avec l’instant d’adoption. Un instantané résolu reste consultable si la ligne ou le lieu disparaît du catalogue. Les modifications du favori ne se propagent pas aux séances enregistrées. Une rectification historique éventuelle est explicite et versionnée sur la séance. La météo peut référencer cet instantané, le favori utilisé pour la récupération ou un autre point de référence déclaré, selon l’[ADR 054](054-observations-meteo-et-vent.md).

Le champ `locationName` des séances existantes demeure un texte historique. Aucune migration ne déduit un lieu, des coordonnées ou un azimut depuis ce texte.

## Adoption et révision

Le formulaire de création configurable propose « Lieu et ligne de tir (facultatif) ». Le catalogue reste accessible depuis ce formulaire même lorsque l’archer souhaite uniquement gérer ses favoris. La création et l’adoption de l’instantané partagent une transaction. Une ligne désactivée reste consultable dans le catalogue et les anciennes séances. Un favori modifié pendant la création exige une nouvelle sélection.

L1 réserve la rectification historique à une évolution versionnée. Elle nécessitera un ancien instantané attendu, une nouvelle valeur proposée, un motif, un horodatage et la conservation de chaque révision dans une nouvelle version de sauvegarde. L1 conserve uniquement l’adoption initiale.

La structure Room 5, les API Desktop, le consentement au partage de coordonnées et la recette Android sont décrits dans le [contrat d’archive V5](../export-format-v5.md).
