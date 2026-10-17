# Kick Zwift — app Android

App Android autonome qui relie les manettes du Zwift Ride au KICKR CORE : 24 vitesses
virtuelles, pente libre, parcours GPX ou ERG, musique et volume depuis les manettes.
Référence : `../docs/Spécification technique — app Android Zwift Ride ↔ KICKR CORE.docx`.

**Projet indépendant, sans lien avec Zwift Inc. ni Wahoo Fitness.** Les protocoles ont été
relevés sur le matériel à des fins d'interopérabilité. Utilisation à vos risques.

## Matériel testé

| Appareil | Version |
|---|---|
| Zwift Ride (deux manettes) | firmware 1.2.0, matériel B.0 |
| KICKR CORE « 85FF » avec Zwift Cog | firmware 1.5.36 |
| Google Pixel 10 Pro | Android 17 (API 37) ; minimum de l'app : Android 12 (API 31) |

Ne pas ouvrir l'app Zwift avec le Ride allumé : à partir du firmware 1.3, Zwift verrouille
les manettes. L'app lit la version de chaque manette et alerte.

## Utilisation

1. Connexions : autoriser Bluetooth et notifications, exclure l'app de l'optimisation de
   batterie, « Rechercher », puis « Mémoriser » le pod gauche du Ride (il relaie les deux
   manettes) et le KICKR. Seuls les appareils mémorisés sont joints ensuite.
2. Connecter la montre COROS directement au KICKR, en mode Vélo intérieur : c'est elle qui
   enregistre la sortie.
3. « Préparer la sortie », choisir le mode, « Démarrer ». Pendant la sortie, tout se pilote
   aux manettes ; Terminer demande un appui long de 2 s.

Correspondance par défaut : Vitesse haut et Vitesse bas des deux manettes pour les vitesses,
Flèche haut et Flèche bas pour la pente (ou la cible ERG), Flèches gauche et droite pour les
pistes, A lecture/pause, Y et B volume, Z pause de la sortie. Tout se change dans Réglages.

## Modules

    protocol/   Kotlin pur, sans Android : décodeurs du Ride, du KICKR et FTMS, gestes,
                vitesses, profil de pente, logique de la sortie (RideSession), réglages et
                leurs codecs, encodeur FIT. Testé sur la JVM avec les trames réelles.
    ble/        Android : liaisons (DeviceLink, LinkSupervisor, reconnexion), managers Nordic
                du Ride, du KICKR et du repli FTMS, scan, TrainerWriter (seul écrivain du
                trainer). Machines de liaison et TrainerWriter testés sur la JVM.
    app/        Android : RideService (service de premier plan), SessionController, actions
                téléphone, réglages (DataStore), parcours GPX, journaux, les cinq écrans.

## Construire

Prérequis : JDK 17 ou plus récent, SDK Android avec `platforms;android-37.0` et
`build-tools;36.0.0`, et un `local.properties` contenant `sdk.dir=...` (non versionné).

    ./gradlew build              # tests, Android Lint, ktlint, detekt, APK debug et release
    ./gradlew :protocol:test     # logique pure, sans SDK nécessaire à l'exécution
    ./gradlew ktlintFormat       # remet le code en forme
    ./gradlew :app:installDebug  # sur le téléphone branché (débogage USB)

Sur la machine de développement :

    export JAVA_HOME=$HOME/.local/share/jdk-17.0.20.1+1

### APK release signé

La clé reste hors du dépôt. Une fois pour toutes :

    keytool -genkeypair -keystore ~/.config/kick-zwift/release.jks -alias kickzwift \
            -keyalg RSA -keysize 4096 -validity 10000

Puis, à chaque version :

    export KICKZWIFT_STORE_FILE=~/.config/kick-zwift/release.jks
    export KICKZWIFT_STORE_PASSWORD=... KICKZWIFT_KEY_ALIAS=kickzwift KICKZWIFT_KEY_PASSWORD=...
    ./gradlew :app:assembleRelease
    ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk

Sans ces variables, l'APK release (`app-release-unsigned.apk`) est produit non signé.

## Vecteurs de test

Les tests de `:protocol` rejouent les trames capturées sur le Ride et le KICKR. Le résultat
attendu vient des noms donnés pendant la capture guidée, pas d'un décodeur. Après une
nouvelle capture (`../prototype/capture_guidee.py`) :

    ../.venv/bin/python ../prototype/export_vectors.py

## Fuzzing

Cibles Jazzer dans `protocol/src/fuzz` : décodeurs des trames reçues et des fichiers lus.
La CI nocturne (`.github/workflows/fuzz.yml`) les lance 10 min en tout. En local :

    ./gradlew :protocol:writeFuzzClasspath
    jazzer --cp="$(cat protocol/build/fuzz-classpath.txt)" \
           --target_class=dev.kickzwift.protocol.fuzz.RideDecoderFuzz -max_total_time=60

## Dépendances

Versions figées dans `gradle/libs.versions.toml`. Chaque artefact est vérifié par
son empreinte SHA-256 (`gradle/verification-metadata.xml`). Après une montée de
version :

    ./gradlew --write-verification-metadata sha256 --no-configuration-cache build

puis relire le diff du fichier avant de le valider.
