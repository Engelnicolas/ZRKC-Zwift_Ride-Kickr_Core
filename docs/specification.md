# ZRKC — spécification technique

App Android autonome qui relie les manettes du Zwift Ride au KICKR CORE : 24 vitesses
virtuelles, pente libre, parcours GPX ou ERG, musique et volume depuis les manettes,
historique des activités. Ce document décrit le code tel qu'il est ; l'usage est dans le
[README](../README.md), le protocole des manettes dans [ride.md](ride.md).

## Périmètre

| Appareil | Version de référence | Rôle |
|---|---|---|
| Zwift Ride | firmware 1.2.0 | Deux manettes, chacune un périphérique BLE |
| KICKR CORE « 85FF », Zwift Cog | firmware 1.5.36 | Home trainer piloté en protocole Zwift (repli FTMS) |
| Capteur cardiaque (COROS HRM) | profil standard 0x180D | Fréquence cardiaque, facultatif ; non essayé sur le matériel |
| Téléphone Android | Android 12 (API 31) minimum | Hôte de l'app ; testé sur Pixel 10 Pro, Android 17 (API 37) |

Hors périmètre : iOS, Play Store, apps tierces, tout accès réseau, mise à jour des firmwares.

## Architecture

Trois modules Gradle. La logique est en Kotlin pur et se teste sans téléphone ; le Bluetooth
et Android sont isolés à côté.

| Module | Contenu | Dépend de |
|---|---|---|
| `:protocol` (JVM pur) | Lecteur et écrivain protobuf minimaux ; décodeurs du Ride, du KICKR, FTMS et de la fréquence cardiaque ; gestes, correspondance boutons → actions, moteur de vitesses, profil de pente ; `RideSession` (logique de la sortie) ; réglages et leurs codecs ; encodeur FIT | kotlinx-coroutines |
| `:ble` (Android) | Scan ; liaisons (`DeviceLink`, `LinkSupervisor`, `ReconnectPolicy`) ; managers Nordic du Ride, du KICKR, FTMS et du capteur cardiaque ; `TrainerWriter` | `:protocol`, Nordic BLE |
| `:app` (Android) | `RideService` (service de premier plan) ; `AppContainer` (injection manuelle) ; `SessionController` ; actions téléphone ; stockage ; GPX ; journaux ; écrans Compose | `:protocol`, `:ble` |

Flux de données :

1. Le pod gauche du Ride publie ses 8 commandes et relaie les 8 de la droite : une liaison
   suffit, le pod droit n'est qu'un relais de secours. Les trames deviennent des gestes
   (appui, appui long, double appui).
2. `SessionController` applique la correspondance boutons → actions : vitesses et pente vers
   la session, musique, volume et ouverture de Spotify vers `PhoneActions`. Au départ de la
   sortie, la lecture de Spotify reprend en arrière-plan (session Spotify, sinon touche
   « lecture » adressée à Spotify seul).
3. `TrainerWriter` est le seul à écrire sur le KICKR : il n'envoie que la dernière consigne,
   au plus une fois par intervalle, puis relit le rapport (requête 520).
4. La télémétrie du KICKR (puissance, cadence, vitesse, volant) alimente la session et
   l'interface, avec la fréquence du capteur cardiaque (sinon celle que relaie le KICKR).
   Pendant la sortie, le journal en garde une ligne par seconde, avec la consigne écrite.

Fils : rappels BLE sur un `HandlerThread` dédié, jamais sur le fil principal ; logique de la
sortie sur un fil unique (`Dispatchers.Default.limitedParallelism(1)`) où commandes, gestes
et mesures ne se croisent jamais ; l'interface ne fait que collecter des `StateFlow`.

Choix techniques : Kotlin 2.4, Compose et Material 3, Navigation Compose ; Gradle 9.8
(wrapper), JDK 17 ; Nordic ble-ktx et Scanner Compat ; aucune bibliothèque protobuf, réseau
ni de chiffrement ; pas de framework d'injection. Versions figées dans
`gradle/libs.versions.toml`, vérifiées par empreinte (`gradle/verification-metadata.xml`).

## Protocoles

### Zwift Ride

Voir [ride.md](ride.md) : service 0xFC82, poignée de main « RideOn » sans chiffrement ni
keepalive, message 0x23 (bitmap inversé et axes des palettes), table des 16 commandes.
À partir du firmware 1.3, Zwift verrouille le Ride : l'app lit la version et alerte.

### KICKR CORE en protocole Zwift

- Service `00000001-19ca-4651-86e5-fa29dcdd09d1` : `…0002` notify, `…0003` écriture sans
  réponse, `…0004` indicate. Poignée de main « RideOn », réponse « RideOn » `02 02`, sans
  chiffrement.
- **0x03**, données de pédalage (environ 1 par seconde) : 1 puissance, 2 cadence, 3 vitesse
  virtuelle, 4 fréquence cardiaque relayée, 5 vitesse du volant.
- **0x04**, consigne : `SimulationParam` (Wind 0, InclineX100, CWa 5100, Crr 400) et
  `PhysicalParam` (GearRatioX10000, BikeWeightx100, RiderWeightx100). Un rapport s'applique
  aussitôt, une pente progressivement.
- **Requête 520** (`00 08 88 04`) : réponse 0x3c sur `…0004`, qui porte le GearRatioX10000
  en vigueur ; sert à vérifier chaque écriture.
- **ERG** : champ PowerTarget (3) du 0x04, d'après virtual-cog, non vérifié sur le KICKR.

```
GearRatioX10000 = round(rapport × 10000)   // vitesse 12 : 2,40 → 24000
InclineX100     = round(pente en % × 100)  // +2,5 % → 250
BikeWeightx100  = round(masse en kg × 100) // 9,5 kg → 950
```

### FTMS, repli

Service 0x1826, Indoor Bike Data 0x2AD2, point de contrôle 0x2AD9 (0x00 prise de contrôle,
0x07 départ, 0x11 simulation, 0x05 puissance cible). Les vitesses sont simulées par un
décalage de pente de (rapport / 2,40 − 1) × 5 %. Pas de relecture du rapport.

### Rapports

Préréglage Zwift, modifiable dans les réglages : 0,75 · 0,87 · 0,99 · 1,11 · 1,23 · 1,38 ·
1,53 · 1,68 · 1,86 · 2,04 · 2,22 · **2,40** (vitesse 12) · 2,61 · 2,82 · 3,03 · 3,24 · 3,49 ·
3,74 · 3,99 · 4,24 · 4,54 · 4,84 · 5,14 · 5,49.

## Machines à états

Sortie (`RidePhase`) :

| État | Ce qui se passe | Transitions |
|---|---|---|
| IDLE | Aucune sortie | → PREPARING sur « Préparer la sortie » ou « Démarrer une activité » |
| PREPARING | Choix du mode | → RIDING sur « Démarrer » ; → IDLE sur Retour |
| RIDING | Consigne envoyée au trainer, chrono lancé | → PAUSED sur Pause, KICKR perdu plus de 30 s ou fin du parcours ; → FINISHED sur Terminer |
| PAUSED | Trainer à plat, chrono figé | → RIDING sur Reprendre ; → FINISHED sur Terminer |
| FINISHED | Trainer à plat, bilan figé, activité enregistrée | → IDLE à la fermeture du bilan |

Liaison de chaque appareil (`LinkState`, tenue par `DeviceLink`) : CONNECTING → HANDSHAKE →
READY ; NOT_FOUND tant qu'il n'a jamais été joint, RECONNECTING après une perte, LOST quand
les reprises sont épuisées (jamais pendant une sortie). Chaque tentative ouvre une connexion
GATT neuve et la ferme quoi qu'il arrive : parade à l'erreur 133, et garantie qu'une manette
ne reste pas persuadée d'être connectée.

## Comportements temps réel

Valeurs par défaut, réglables pour la plupart.

| Paramètre | Valeur | Règle |
|---|---|---|
| Anti-rebond | 30 ms | Bascule plus courte ignorée |
| Appui long | 600 ms | Émis à l'échéance |
| Double appui | 300 ms | Si configuré, l'appui simple attend la fin de la fenêtre |
| Seuil des palettes | appui à \|v\| ≥ 50, relâché sous 20 | Hystérésis |
| Silence d'une manette | 3 s sans battement 0x15 | Manette perdue |
| Écriture au trainer | 1 / 150 ms | Seule la dernière consigne part |
| Relecture du rapport | requête 520, au plus 1 / s | Un écart : une réécriture, puis journal |
| Reconnexion | 1, 2, 4, 8, 16 puis 30 s | Sans limite pendant une sortie |
| KICKR perdu | 30 s | La sortie passe en pause |
| Pente | −10 % à +16 %, pas de 0,5 % | Toute consigne est bornée |
| ERG | pas de 10 W, plafond 600 W | Vitesses désactivées |
| Vitesses | 10 changements / s au plus | — |
| Interface | 4 Hz | Puissance affichée = moyenne sur 3 s |

Mode GPX : distance cumulée par haversine à l'import ; distance parcourue intégrée depuis la
vitesse du 0x03 ; pente envoyée = pente moyenne des 50 m devant, recalculée chaque seconde.
Une pause l'emporte sur un changement de vitesse reçu dans la même fenêtre d'écriture.

## Interface

Une activité, Navigation Compose, un ViewModel par écran exposant un `StateFlow` immuable.
Aucun écran ne parle au Bluetooth : tout passe par le service, qui garde la sortie active
écran éteint. La phase de la sortie impose l'écran (Sortie pendant la sortie, Bilan à la fin).

| Écran | Contenu |
|---|---|
| Connexions (onglet) | Encart « My devices », une ligne par appareil : état, batterie, firmware, fréquence cardiaque, alerte verrou ; recherche, mémorisation, oubli ; « Préparer la sortie » |
| Activités (onglet) | « Démarrer une activité » ; distance et temps du mois et de l'année en cours ; historique, la plus récente en tête (liseré rouge, date et mode, temps, distance, puissance moyenne, commentaire) |
| Activité | Bilan enregistré ; commentaire modifiable ; suppression confirmée |
| Choix du mode | Pente libre, parcours GPX (import, aperçu, cinq derniers), ERG |
| Sortie | Notifications des autres apps en haut, poussant l'écran vers le bas (8 s, trois au plus) ; encart des capteurs ; vitesse engagée et barre des rapports, puissance, cadence, vitesse, pente ou cible, temps (h:mm:ss), distance, fréquence cardiaque, vitesse brute du volant, profil GPX ; lecteur, Spotify d'abord (titre, lecture ou pause, volume, relu toutes les 500 ms ; boutons précédent, lecture/pause, suivant, volume − et +) ; boutons ± de la cible en ERG seulement, la pente libre se règle aux manettes ; Pause, Terminer (appui de 3 s, décompte 3, 2, 1 sur le bouton) |
| Bilan | Durée, distance, puissances, cadence, dénivelé, changements ; commentaire ; export FIT |
| Réglages (onglet) | Correspondance boutons → actions avec « Tester », rapports, masses, affichage, options avancées (repli FTMS, export FIT, journaux, essai des vitesses « Gear test » : Debug start, Debug stop) |

Règles : écran allumé pendant la sortie, portrait ou paysage ; thème sombre par défaut ;
cibles de 48 dp, 64 dp pour Pause et Terminer ; état des appareils signalé par la forme et le
texte en plus de la couleur ; vibration au changement de vitesse, désactivable ; notification
permanente avec Pause et Arrêter. Interface en anglais seulement. Boutons et accents en rouge
(esprit COROS), fonds neutres, noirs en thème sombre ; pastille « prêt » en vert ; logo discret
en haut à droite de chaque écran. Durées en heures, minutes et secondes.

## Persistance

| Donnée | Stockage |
|---|---|
| Réglages, correspondance, table des rapports | DataStore typé `settings.pb` (codec de `:protocol`) |
| Appareils de confiance | DataStore typé `known_devices.pb` |
| Dernière vitesse, cinq derniers GPX | DataStore typé `ride_state.pb` |
| Activités (date, mode, durée, distance, puissances, cadence, dénivelé, changements, commentaire) | SQLite `activities.db` |
| Fichiers GPX importés | Copie dans le stockage interne |
| Journaux | 5 fichiers de 1 Mo, partagés depuis les réglages |

Un fichier DataStore illisible repart des valeurs par défaut. Aucune donnée n'est sauvegardée
dans le cloud ni transférée (`allowBackup=false`, règles d'extraction vides).

## Sécurité

L'app n'a aucun accès réseau : sa surface d'attaque se limite au Bluetooth et aux GPX importés.

- **Permissions** : `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT`,
  `FOREGROUND_SERVICE` et `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `VIBRATE`, `ACCESS_NOTIFICATION_POLICY`. Absentes volontairement : `INTERNET`,
  localisation, stockage, accessibilité.
- **Accès aux notifications** (facultatif, accordé dans les réglages d'Android) : ouvre la
  liste des lecteurs actifs pour le titre et l'état du lecteur ; sans accès, le lecteur montre
  le volume et « de la musique joue ». Hors sortie, les notifications reçues sont ignorées ;
  pendant la sortie, celles qui auraient surgi en bandeau (importance haute, hors appels) sont
  recopiées dans l'écran Sortie, en mémoire seulement, jamais journalisées.
- **Mode « ZRKC ride »** (`ACCESS_NOTIFICATION_POLICY`, accordé avec l'accès aux notifications) :
  actif de RIDING à la fin de la sortie, il supprime les bandeaux flottants ; les notifications
  restent dans le volet mais sans son ni vibration ; appels, alarmes et musique passent. Désactivé
  au lancement de l'app s'il était resté actif.
- **Visibilité des paquets** : `<queries>` limité à `com.spotify.music`.
- **Appareils de confiance** : seules les adresses mémorisées au premier appairage sont
  jointes, directement et sans scan.
- **Trames et fichiers hostiles** : tout est vérifié et rejeté sans faire tomber la liaison ;
  les décodeurs sont fuzzés.
- **Trainer** : pente bornée, ERG plafonné ; à l'arrêt ou sur plantage, il reçoit une
  consigne à plat.
- **Journaux** : adresses Bluetooth tronquées.
- **Composants** : deux exportés, l'activité de lancement et `MediaAccessService`, auquel
  seul le système peut se lier (`BIND_NOTIFICATION_LISTENER_SERVICE`).
- **Build** : R8, clé de signature hors du dépôt, dépendances vérifiées par empreinte,
  actions GitHub épinglées par empreinte de commit.

## Tests et CI

- **Vecteurs de référence** : trames réelles du Ride et du KICKR, captées sur le matériel par
  le prototype Python (hors dépôt), rejouées par les tests JVM.
- **Tests JVM** : 279 (230 dans `:protocol`, 26 dans `:ble`, 23 dans `:app`) ; couverture
  de `:protocol` ≥ 90 % des lignes, bloquante (JaCoCo). Faux appareils derrière `Controller`
  et `Trainer` pour les liaisons, `TrainerWriter` et `SessionController`.
- **Qualité** : Android Lint (avertissements bloquants), ktlint, detekt.
- **CI** (`.github/workflows/ci.yml`, à chaque push et pull request) : `./gradlew build`,
  puis contrôle de l'APK release : ni `INTERNET` ni localisation, deux composants exportés
  dont `MediaAccessService` protégé par sa permission.
- **Fuzzing nocturne** (`fuzz.yml`) : Jazzer, 4 cibles, 150 s chacune.

## État et points ouverts

Tout le code est écrit, la CI et le fuzzing sont verts ; l'app est installée sur le
téléphone. Restent les essais sur le matériel, dans l'ordre :

1. Liaisons : 1 h écran éteint sans perte ; reconnexion seule après coupure de chaque appareil.
2. Manettes : chaque commande en mode « Tester » ; 30 min Ride et KICKR connectés ensemble.
3. Trainer : rafale de 10 changements, rapport final relu par la requête 520 ; « Arrêter »
   remet le trainer à plat ; repli FTMS.
4. Musique et volume depuis les manettes, écran éteint.
5. Sorties : vitesse lisible à 1,5 m ; 30 min en pente libre ; 1 h sur un GPX ; ERG à ±10 W ;
   activité enregistrée, commentée puis supprimée.
6. Endurance : 2 h écran éteint avec l'APK release signé.

Questions ouvertes :

- **Vitesses peu ressenties** (sortie du 18 oct., 15:49) : le KICKR relit bien chaque rapport,
  mais la vitesse simulée suit la puissance et non le rapport ; un changement ne donne qu'un
  pic de quelques secondes (vitesse 24 à 67 rpm : 44 W, au lieu d'environ 400 W pour un vrai
  vélo). L'essai des vitesses des réglages (`GearTestPlan`, comme `kickr_sequence.py vitesses`)
  compare quatre façons d'envoyer le rapport : seul, seul après une pente, avec les masses
  (l'app), pente, rapport et masses en une trame.
- Unité de la vitesse virtuelle du 0x03 (centième de km/h ou de m/s) : à reporter dans
  `TrainerCodec.SPEED_RAW_TO_KMH` ; sens du champ 6.
- Mode ERG du KICKR (champ PowerTarget) et bornes de pente réelles du CORE.
- Le CORE cale-t-il le rapport physique en début de sortie, comme le Zwift Hub ?
- Nombre de liaisons BLE simultanées tenues par le téléphone.
- Seuil des palettes en roulage ; origine des bits 11 et 15 du Ride.
- Décalage de pente du repli FTMS, à régler au pédalage.
- Doze écran éteint : si les reprises s'étirent, verrou de réveil partiel (`WAKE_LOCK`).
- Android 12 ne reçoit pas le type d'adresse : après redémarrage, une adresse aléatoire peut
  demander un scan avant de se joindre.
- Tests UI Compose : non écrits.
- Licence de RideToWoosh, si du code devait en être repris.

## Risques

| Risque | Parade |
|---|---|
| Ride mis à jour en firmware 1.3 ou plus : verrou Zwift | Ne pas ouvrir l'app Zwift avec le Ride allumé ; alerte à l'écran |
| Firmware du CORE mis à jour : protocole changé | Mise à jour seulement après validation par la communauté ; repli FTMS |
| Code GPL recopié (QZ, Berg0162) | S'inspirer de la documentation, ne pas copier |
| Android coupe l'app en arrière-plan | Service de premier plan, exclusion de l'optimisation de batterie |

Rétro-ingénierie à des fins d'interopérabilité : en principe autorisée en droit européen
(directive 2009/24/CE, art. 6) ; l'app ne touche ni aux serveurs ni au logiciel de Zwift.

Références : [Makinolo, Zwift trainer protocol](https://www.makinolo.com/blog/2024/10/20/zwift-trainer-protocol/) ·
[pymmog/virtual-cog](https://github.com/pymmog/virtual-cog) ·
[dadepro/zwift-virtual-shifting](https://github.com/dadepro/zwift-virtual-shifting) ·
[TheInGoF/RideToWoosh](https://github.com/TheInGoF/RideToWoosh) ·
[nordicsemi/Android-BLE-Library](https://github.com/nordicsemi/Android-BLE-Library) ·
[Zwift Insider, Virtual Shifting](https://zwiftinsider.com/virtual-shifting/) (table des rapports) ·
[Wahoo, notes de version du KICKR CORE](https://support.wahoofitness.com/hc/en-us/articles/360000217839).
