# Documentation et traces

    specification.md    spécification technique : architecture, protocoles, comportements,
                        interface, stockage, sécurité, tests, points ouverts
    ride.md             protocole des manettes du Ride (service 0xFC82), en texte
    protocole_fc82.html fiche protocole illustrée, publiée sur
                        https://claude.ai/code/artifact/7428625e-320a-4337-b1d2-ae035639e581
    images/             bannière, schéma de fonctionnement (SVG) et captures d'écran
                        du README (Pixel 10 Pro, barre d'état en mode démo)
    captures/           sessions de capture guidée du Ride (17 octobre 2026) : trames
                        brutes et noms donnés par la personne qui tenait les manettes ;
                        source des vecteurs protocol/src/test/resources/vectors/ride_commands.json
    journaux/           traces brutes : Ride (bits.log, hold.log, held2a.log, 16 octobre
                        2026) et toutes celles du KICKR (kickr_*.log, 17 octobre 2026),
                        source de protocol/src/test/resources/vectors/kickr.json. Les traces
                        plus anciennes du Ride restent dans l'historique git (commit 244204d).

Les scripts du prototype Python (`../../prototype/`, hors dépôt) écrivent dans `docs/`,
`captures/` et `journaux/` à la racine du projet : ce sont des liens vers ce dossier.

Les adresses Bluetooth des traces sont celles du matériel de test (manettes E2:93:…,
F2:DD:…, KICKR CB:4A:…) ; elles servent à rejouer les relevés.
