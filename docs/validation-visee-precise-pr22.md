# Validation Pixel — visée précise (PR 22)

## Limite de version pour la branche d’intégration

Ce protocole historique concerne l’APK #22 et Room 1. Pour une branche contenant
le catalogue Room 2, suivre d’abord l’[ordre d’installation de la combinaison](validation-integration-visee-materiel.md#ordre-dinstallation-impératif).
Ne pas réinstaller l’APK #22 sur une base passée en Room 2. Les résultats humains
antérieurs ne sont pas une validation de la combinaison avec matériel/SAF V2.

## État et précautions

**Non exécuté pour cette PR : Pixel indisponible.** Aucun essai humain ni gain de
précision réelle validé. Aucun émulateur préconfiguré trouvé ; les deux fonctions
`@Preview` dans `PrecisionAimPreview.kt` sont compilées, non rendues ici. Ouvrir
ces previews dans Android Studio pour inspecter petite dalle et paysage.

La PR reste en brouillon. Ne pas fusionner avant évaluation humaine. Les commandes
ci-dessous sont un protocole à reprendre quand le téléphone est disponible,
pas des commandes déjà exécutées. Ne pas reconnecter continuellement le Pixel.

Utiliser les quatre séances synthétiques existantes, les préserver intégralement.
Créer **au maximum une** séance supplémentaire `TEST AUTO PR22` si nécessaire
pour les ajouts ; réutiliser ensuite ses volées. Ne pas toucher aux séances réelles,
ne pas désinstaller ni exécuter `pm clear`. Ne publier aucune base, capture ou export.

## Préparer l’APK et identifier le téléphone

```bash
./scripts/dev-check.sh --rerun-tasks --console=plain
adb devices -l
adb mdns services
# Reporter le numéro actuellement observé, pas un ancien port supposé.
LMARCHERY_ADB_SERIAL='numero-observe'
adb -s "$LMARCHERY_ADB_SERIAL" shell dumpsys window policy
```

Vérifier écran allumé et `KeyguardServiceDelegate` avec `showing=false`. Si verrouillé,
faire déverrouiller par le propriétaire puis arrêter les interactions en attendant.
Ne pas contourner le verrouillage. Si aucun appareil n’est disponible, conserver
la PR en brouillon et reporter la campagne, sans modifier le système.

## Captures SQLite privées et comparaison sans écriture

Définir ce helper dans un terminal ; il ferme d’abord l’application pour obtenir
une copie cohérente avec ses éventuels WAL/SHM. Il ne modifie aucune table.
Les copies et JSON sont des preuves locales privées, hors Git.

```bash
umask 077
LMARCHERY_PR22_EVIDENCE=$(mktemp -d /tmp/lmarchery-pr22-pixel.XXXXXX)
capture_lmarchery_sql() {
  local destination="$1"
  mkdir -p "$destination"
  adb -s "$LMARCHERY_ADB_SERIAL" shell am force-stop com.lmarchery || return
  for name in sessions.db sessions.db-wal sessions.db-shm; do
    if adb -s "$LMARCHERY_ADB_SERIAL" shell run-as com.lmarchery test -f "databases/$name"; then
      adb -s "$LMARCHERY_ADB_SERIAL" exec-out run-as com.lmarchery cat "databases/$name" > "$destination/$name" || return
    fi
  done
  python3 - "$destination" <<'PY'
import json, pathlib, sqlite3, sys
folder = pathlib.Path(sys.argv[1]).resolve()
assert (folder / 'sessions.db').exists(), 'Base absente ou run-as inaccessible'
with sqlite3.connect((folder / 'sessions.db').as_uri() + '?mode=ro', uri=True) as db:
    db.row_factory = sqlite3.Row
    assert db.execute('PRAGMA quick_check').fetchone()[0] == 'ok'
    assert not db.execute('PRAGMA foreign_key_check').fetchall()
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    rows = {t: sorted([dict(r) for r in db.execute('SELECT * FROM "' + t.replace('"', '""') + '"')],
                      key=lambda r: json.dumps(r, sort_keys=True)) for t in tables}
(folder / 'all-tables.json').write_text(json.dumps(rows, sort_keys=True, indent=2))
print('Tables capturées :', len(rows), '— contenu conservé en privé')
PY
}
capture_lmarchery_sql "$LMARCHERY_PR22_EVIDENCE/before-install"
adb -s "$LMARCHERY_ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$LMARCHERY_ADB_SERIAL" shell am start -n com.lmarchery/.app.MainActivity
```

Arrêter si installation incompatible, base inattendue ou diagnostic d’intégrité.
Ne jamais effacer/recréer la base pour faire passer la campagne.

## Consultation, annulation et rotation sans changement des séances existantes

1. Retrouver les quatre séances et leurs scores/volées. La séance terminée reste
   en lecture seule et n’offre pas de visée. Les anciennes volées validées aussi.
2. Dans une séance synthétique active pleine, sélectionner une flèche puis
   Repositionner et activer la visée précise. Appuyer, déplacer vers 10/9, relâcher :
   réticule et loupe cohérents, aperçu non enregistré distinct du score enregistré.
   **Annuler sans confirmer** pour préserver les données des quatre séances.
3. Refaire un geste et ajouter un deuxième doigt, d’abord sur le blason puis sur
   les commandes autour. Aucun ajout/déplacement confirmé. Les boutons doivent
   rester inactifs jusqu’au relâchement de tous les doigts.
4. Sortir du carré, puis rentrer avant relâchement : geste annulé, aucune position
   rabattue sur le bord. Les coins dans le carré restent des M légitimes.
5. Refaire un candidat, commencer un autre geste puis l’interrompre : le dernier
   candidat relâché doit rester, la position partielle ne le remplace pas.
6. Tester le scroll hors blason puis dedans : en visée précise, le geste appartient
   au blason ; en mode rapide, le défilement et le toucher court restent habituels.
7. Rotation pendant un geste : aucune flèche créée. Rotation **après relâchement** :
   candidat, motif et sélection conservés. Annuler explicitement ensuite.
8. Retour pendant la proposition : refuser l’abandon conserve le candidat ; accepter
   abandonne seulement la proposition. Les diagnostics/PendingSave ne sont pas masqués.
9. Force-stop et relance : seule l’archive confirmée revient, mode rapide, aucun
   candidat restauré artificiellement.

Si la rotation est forcée par ADB, relever puis restaurer **exactement** les deux
valeurs initiales, avec un `trap` même si un test échoue :

```bash
LMARCHERY_ROTATION_AUTO=$(adb -s "$LMARCHERY_ADB_SERIAL" shell settings get system accelerometer_rotation | tr -d '\r')
LMARCHERY_ROTATION_USER=$(adb -s "$LMARCHERY_ADB_SERIAL" shell settings get system user_rotation | tr -d '\r')
restore_lmarchery_rotation() {
  if [ "$LMARCHERY_ROTATION_AUTO" = null ]; then
    adb -s "$LMARCHERY_ADB_SERIAL" shell settings delete system accelerometer_rotation
  else
    adb -s "$LMARCHERY_ADB_SERIAL" shell settings put system accelerometer_rotation "$LMARCHERY_ROTATION_AUTO"
  fi
  if [ "$LMARCHERY_ROTATION_USER" = null ]; then
    adb -s "$LMARCHERY_ADB_SERIAL" shell settings delete system user_rotation
  else
    adb -s "$LMARCHERY_ADB_SERIAL" shell settings put system user_rotation "$LMARCHERY_ROTATION_USER"
  fi
}
trap restore_lmarchery_rotation EXIT INT TERM
adb -s "$LMARCHERY_ADB_SERIAL" shell settings put system accelerometer_rotation 0
adb -s "$LMARCHERY_ADB_SERIAL" shell settings put system user_rotation 1
# Observer le paysage, les commandes et le contexte, puis revenir en portrait.
adb -s "$LMARCHERY_ADB_SERIAL" shell settings put system user_rotation 0
restore_lmarchery_rotation
trap - EXIT INT TERM
```

Comparer les données après cette phase, avant toute nouvelle séance :

```bash
capture_lmarchery_sql "$LMARCHERY_PR22_EVIDENCE/after-cancel"
cmp "$LMARCHERY_PR22_EVIDENCE/before-install/all-tables.json" \
    "$LMARCHERY_PR22_EVIDENCE/after-cancel/all-tables.json"
# Résultat attendu : code 0, toutes les tables exactement inchangées.
adb -s "$LMARCHERY_ADB_SERIAL" shell am start -n com.lmarchery/.app.MainActivity
```

## Ajout et sauvegarde, sur TEST AUTO PR22 uniquement

1. Si cette séance n’existe pas déjà, la créer une seule fois, classique 40 cm.
   Capturer une nouvelle référence SQLite après sa création confirmée.
2. Mode rapide : un toucher court ajoute une seule flèche, après acquittement.
3. Mode précis : appuyer/déplacer/relâcher, vérifier l’aperçu sans changement de
   total durable. Annuler : aucune ligne supplémentaire. Capturer/comparer SQLite.
4. Refaire, puis force-stop avant confirmation : après relance, candidat absent
   et archives identiques à la référence. Cette capture contrôle aussi qu’un
   candidat en mémoire n’avait pas créé de ligne SQL.
5. Refaire et confirmer deux fois rapidement : une seule flèche/UUID/ligne nouvelle.
   Les totaux n’évoluent qu’après acquittement. Trois flèches interdisent un quatrième
   ajout et aucune validation automatique ne se produit.
6. Sur une flèche de cette **seule** séance, poser un jugement manuel, puis déplacer
   en précis : motif requis ; après confirmation, même UUID/création/origine et
   ancienne décision conservée dans une trace. Annuler ne change rien.
7. Vérifier le nouveau jugement possible après repositionnement, puis valider
   normalement. Volée validée et séance terminée restent non modifiables.
8. Exporter un fichier de contrôle via Sauvegardes, attendre relecture confirmée.
   Comparer les quatre séances originales et leurs profils, UUID, dates, points,
   décisions/traces à la sauvegarde de référence ; seule TEST AUTO PR22 peut différer.
   Vérifier cette nouvelle séance et ses nombres de descendants, sans UUID dupliqué.

Ne pas simuler une panne SQL en saturant/corrompant un téléphone contenant des
séances. Les erreurs/conflits, PendingSave et retry strict sont couverts par JVM ;
la fidélité SQLite/fermeture/réouverture reste contrôlée par les tests natifs existants.
Une capture n’est pas une autorisation de modifier les quatre séances historiques.

## Essai ergonomique au doigt (humain)

Sur des séances d’essai volontairement préparées par le testeur, classique puis
poulies 40 cm, placer trois flèches près du 10 en rapide, puis trois en précis.
Corriger une flèche vers 10/9, confirmer/annuler, passer en paysage. Relever temps
de saisie, nombre de corrections, lisibilité de la loupe ×4 et masquage éventuel
d’informations. Tester en salle puis dehors, avec gants si disponibles.

Noter appareil, orientation, taille de texte, main utilisée et observations libres.
Distinguer confort perçu, précision de pointage et vrai contact physique d’un tube :
les coordonnées ADB ou une loupe ne prouvent aucune précision réglementaire.
Ne pas calculer un gain physique de précision sans protocole humain adapté.

Résultats : **à renseigner**, aucune campagne physique exécutée pour la PR 22.
