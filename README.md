# Kick Zwift — app Android

App Android autonome qui relie les manettes du Zwift Ride au KICKR CORE.
Référence : `../docs/Spécification technique — app Android Zwift Ride ↔ KICKR CORE.docx`.

## Modules

    protocol/   Kotlin pur, sans Android : types communs, décodeur du Ride,
                moteur de vitesses. Testé sur la JVM avec les trames réelles.
    ble/        Android : interfaces Controller et Trainer (implémentées aux lots 4 à 6).
    app/        Android : activité Compose, manifeste, thème.

## Construire

Prérequis : JDK 17 ou plus récent, SDK Android avec `platforms;android-37.0` et
`build-tools;36.0.0`, et un `local.properties` contenant `sdk.dir=...` (non versionné).

    ./gradlew build              # tests, Android Lint, ktlint, detekt, APK debug et release
    ./gradlew :protocol:test     # logique pure, sans SDK nécessaire à l'exécution
    ./gradlew ktlintFormat       # remet le code en forme

Sur la machine de développement :

    export JAVA_HOME=$HOME/.local/share/jdk-17.0.20.1+1

L'APK release (`app/build/outputs/apk/release/`) est réduit par R8 et non signé :
la clé de signature reste hors du dépôt.

## Vecteurs de test

Les tests de `:protocol` rejouent les trames capturées sur le Ride. Le résultat
attendu vient des noms donnés pendant la capture guidée, pas d'un décodeur.
Après une nouvelle capture (`../prototype/capture_guidee.py`) :

    ../.venv/bin/python ../prototype/export_vectors.py

## Dépendances

Versions figées dans `gradle/libs.versions.toml`. Chaque artefact est vérifié par
son empreinte SHA-256 (`gradle/verification-metadata.xml`). Après une montée de
version :

    ./gradlew --write-verification-metadata sha256 --no-configuration-cache build

puis relire le diff du fichier avant de le valider.
