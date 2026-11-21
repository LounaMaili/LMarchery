# ADR 052 — Lieux favoris et lignes de tir

Statut proposé. Ce document prépare un contrat commun Android et Desktop. Le catalogue et la persistance feront l’objet d’un autre lot.

## Identités et sélection

- Un **lieu favori** porte un UUID v4 stable, un nom et une description facultatifs, et éventuellement une latitude et une longitude WGS84 en degrés. Les coordonnées forment une paire finie, latitude dans `[-90, 90]`, longitude normalisée dans `[-180, 180[`. L’incertitude horizontale en mètres et la provenance de la position sont facultatives. L’absence de localisation est un état normal.
- Une **ligne de tir** porte son propre UUID v4, l’UUID de son lieu, un nom et une description facultatifs et éventuellement une orientation. Un lieu peut avoir plusieurs lignes, y compris de sens opposés. L’orientation va de l’archer vers les cibles et suit l’[ADR 053](053-azimut-et-boussole.md). Le nom, la proximité géographique et l’azimut ne définissent aucune identité.
- À la création d’une séance, Android peut proposer un favori puis une ligne. Un lieu sans ligne et une séance sans lieu restent valides. Une correction manuelle peut différer de la valeur du catalogue. Le découpage ou la réorientation d’une ligne se fait par révision. Si l’accès à une ancienne ligne est retiré, son UUID reste réservé.

## Valeur historique

La séance conserve un instantané explicite des valeurs retenues. Il peut comprendre les UUID de provenance, les libellés, la position et l’orientation confirmées, avec l’instant d’adoption. Un instantané résolu reste consultable si la ligne ou le lieu disparaît du catalogue. Les modifications du favori ne se propagent pas aux séances enregistrées. Une rectification historique éventuelle est explicite et versionnée sur la séance. La météo peut référencer cet instantané, le favori utilisé pour la récupération ou un autre point de référence déclaré, selon l’[ADR 054](054-observations-meteo-et-vent.md).

Le champ `locationName` des séances existantes demeure un texte historique. Aucune migration ne déduit un lieu, des coordonnées ou un azimut depuis ce texte.

## Partage et stockage futurs

Le domaine Kotlin/JVM définira les identités et instantanés. Android saisira et confirmera les valeurs. Desktop lira ces mêmes types et distinguera provenance, coordonnées absentes et localisation inconnue. Un futur schéma Room pourra séparer les tables des favoris, les lignes et les instantanés de séance. Les références au catalogue resteront facultatives pour que les instantanés soient autonomes. Aucune suppression en cascade vers l’historique.

Un futur contrat d’archive versionné devra préserver les instantanés et définir si le catalogue local est exportable séparément. La prévisualisation SAF devra rendre explicite la présence de coordonnées et proposer une politique de partage distincte si nécessaire. Une omission délibérée de la position devra être déclarée comme telle. Les contrats stricts V1 à V4 gardent leur sens et un transfert vers ces versions devra refuser toute perte silencieuse. Voir [archives V4](../export-format-v4.md).
