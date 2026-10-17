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
    journaux/           les trois traces brutes les plus récentes de chaque appareil :
                        Ride (bits.log, hold.log, held2a.log, 16 octobre 2026) et KICKR
                        (kickr_pente_*, kickr_neutre_*, 17 octobre 2026). Les traces plus
                        anciennes, dont celles qui ont servi à kickr.json (kickr_probe_*,
                        kickr_gear_*), restent dans l'historique git (commit 244204d).

Les scripts du prototype Python (`../../prototype/`, hors dépôt) écrivent dans `docs/`,
`captures/` et `journaux/` à la racine du projet : ce sont des liens vers ce dossier.

Les adresses Bluetooth des traces sont celles du matériel de test (manettes E2:93:…,
F2:DD:…, KICKR CB:4A:…) ; elles servent à rejouer les relevés.
