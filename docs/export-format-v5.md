# Sauvegarde JSON V5 des lieux de tir

La sauvegarde complète V5 réunit les séances, leurs données sportives, le matériel, les lieux favoris, les lignes actives ou désactivées et les instantanés de lieu. Les presets personnels, photos et repères photographiques restent propres à l’appareil.

## Contrat partagé

| Clé obligatoire | Contenu |
|---|---|
| `schemaVersion` | Entier 5 |
| `sporting` | Document [V4](export-format-v4.md) complet, avec son propre numéro de version, ses annulations et le contrat de score 1 |
| `sites` | Lieux avec UUID v4, nom, description et position facultative |
| `lines` | Lignes avec UUID v4, UUID du lieu, nom, description, orientation et état actif |
| `sessionPlaces` | UUID de séance, instant UTC d’adoption, copie du lieu, copie éventuelle de la ligne et orientation effective facultative |

Les propriétés facultatives figurent explicitement avec `null`. Une position contient latitude, longitude, origine éventuelle et précision horizontale éventuelle en mètres. Une orientation conserve degrés, référence du nord, origine manuelle, incertitude éventuelle en degrés et instant connu. Une ligne capturée contient sa valeur au catalogue lors de l’adoption, même lorsque l’archer choisit un azimut différent pour la séance. L’adoption L1 intervient à la création. L’absence d’instantané désigne une séance sans lieu structuré.

Les collections propres à V5 se lisent par identité et se réexportent en ordre canonique d’UUID. Les collections du document sportif gardent l’ordre du contrat V4. Le décodeur rejette notamment les propriétés manquantes, inconnues ou dupliquées, les valeurs non finies, les propriétaires absents, un même UUID utilisé pour plusieurs entités et les références de nord inconnues. Le document complet partage le budget du parseur strict, dont la limite de 16 Mio.

Desktop peut décoder avec `ArchiveDocumentDecoder.decode(bytes)` de `:core:export`, puis lire `ArchiveDocumentRead.V5.archive`. Le résultat `DomainArchiveV5.sporting` donne accès aux API de score existantes. `places.snapshots` fournit la valeur historique par `sessionUuid`, y compris après un changement de nom ou de direction du favori. Les types `ShootingSite`, `ShootingLine`, `SessionPlace`, `GeoPosition` et `LineOrientation` appartiennent à `:core:model`, dans `com.lmarchery.core.model.place`.

## Stockage, restauration et partage

Room 5 ajoute les tables `shooting_sites`, `shooting_lines` et `session_places` à la base des séances. La migration 4→5 crée ces tables vides. Le champ historique `locationName` reste indépendant. Les UUID du catalogue sont réservés dans le registre commun. La ligne est liée à son lieu par clé étrangère et la capture à sa séance. Les valeurs de la capture ont leurs propres colonnes. Les positions et angles utilisent les bits du Double et les instants conservent leur précision.

L’export lit le graphe sportif et les lieux dans la même transaction. Si un favori **ou un instantané historique** contient des coordonnées, Android demande explicitement l’accord avant le choix du fichier. Tout destinataire du document peut les lire. Annuler à cette étape libère l’opération. Les exports V1–V4 signalent qu’il faut choisir V5 dès qu’un enregistrement de lieu existe. Leurs codecs publiés restent inchangés.

L’import présente les quantités de lieux, lignes et instantanés, les ajouts prévus et la présence de coordonnées. Après confirmation, les ajouts sportifs et géographiques forment une transaction unique. Une identité connue divergente, une capture différente pour la même séance ou un aperçu périmé interdisent l’ajout. Un fichier identique est idempotent. Une interruption après une tentative d’écriture conserve le candidat pour une relecture explicite.

## Recette Android à effectuer

Sur une installation TEST isolée, créer un favori à deux lignes de sens opposés. Démarrer une séance avec la première et corriger uniquement son orientation. Rouvrir la séance après avoir renommé le lieu et désactivé la ligne. Vérifier les anciens libellés et la direction dans « Séance et volées ». Créer également une séance sans lieu. Avec le sélecteur de fichiers Android, annuler puis accepter un export contenant une position, restaurer le document dans une installation de recette indépendante et relire les deux séances.
