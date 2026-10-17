# Documentation et traces

    Spécification technique — app Android Zwift Ride ↔ KICKR CORE.docx
        Référence : protocoles, architecture, lots, avancement, points ouverts.
    Pont open source Zwift Ride ↔ KICKR CORE plan de développement.docx
        Phases, spécification de l'interface, risques.
    ride.md             protocole des manettes du Ride (service 0xFC82), en texte
    protocole_fc82.html fiche protocole illustrée, publiée sur
                        https://claude.ai/code/artifact/7428625e-320a-4337-b1d2-ae035639e581
    captures/           sessions de capture guidée du Ride (17 octobre 2026) : trames
                        brutes et noms donnés par la personne qui tenait les manettes ;
                        source des vecteurs protocol/src/test/resources/vectors/ride_commands.json
    journaux/           traces brutes des 16 et 17 octobre 2026 : Ride (ride_trace*,
                        dual_log*, hold, simul…) et KICKR (kickr_*.log, source de kickr.json)

Les scripts du prototype Python (`../../prototype/`, hors dépôt) écrivent dans `docs/`,
`captures/` et `journaux/` à la racine du projet : ce sont des liens vers ce dossier.

Les adresses Bluetooth des traces sont celles du matériel de test (manettes E2:93:…,
F2:DD:…, KICKR CB:4A:…) ; elles servent à rejouer les relevés.
