# Zwift Ride — protocole des manettes (service 0xFC82)

Relevé sur le matériel les 16 et 17 octobre 2026 (manettes en firmware 1.2.0). Version
détaillée et illustrée : `docs/protocole_fc82.html`, publiée sur
https://claude.ai/code/artifact/7428625e-320a-4337-b1d2-ae035639e581. Recherche
d'interopérabilité non officielle, sans lien avec Zwift Inc.

## Deux périphériques, un seul qui voit tout

| Pod | Adresse de l'exemplaire de test | N° série | Commandes publiées | Axes |
|---|---|---|---|---|
| Gauche, agrégateur | E2:93:3B:6A:0F:BC | 08-22933B6A0FBC | les 16 : ses 8 et les 8 de la droite | 4 |
| Droit | F2:DD:0E:DE:3D:BC | 07-B2DD0EDE3DBC | ses 8 seulement | 2 |

Une liaison au pod gauche suffit ; la droite est relayée avec quelques dizaines de
millisecondes de retard (200 ms au plus observés). Les deux pods s'annoncent sous le même
nom (« Zwift Ride », ou « Zwift SF2 ») avec le service 16 bits 0xFC82. Les adresses sont
aléatoires statiques.

## Caractéristiques

Suffixe commun `-19ca-4651-86e5-fa29dcdd09d1`, dans le service 0xFC82 :

| UUID | Propriétés | Rôle |
|---|---|---|
| `00000002-…` | notify | flux d'état : 0x23 boutons et axes, 0x15 battement, 0x19 batterie, 0x2a télémétrie |
| `00000003-…` | write-without-response | destination de la poignée de main |
| `00000004-…` | read, indicate | écho de la poignée de main |
| `00000006-…` | read, write, indicate | configuration ; piège : « RideOn » écrit ici ne démarre aucun flux |

Services annexes : 0x180F (batterie, 0x2A19), 0x180A (dont firmware 0x2A26).

## Poignée de main

1. S'abonner à `…0002` (notify) et `…0004` (indicate), avant toute écriture.
2. Écrire les 6 octets `52 69 64 65 4f 6e` (« RideOn », rien de plus) sur `…0003`, sans réponse.
3. `…0004` renvoie « RideOn » ; le flux 0x23 démarre aussitôt.

Pas de chiffrement, pas de keepalive : 9 min 36 s sans écriture, aucune coupure.

## Messages

- **0x23**, état complet à chaque transition : champ 1 = bitmap 32 bits en logique inversée
  (repos `0xFFFFFFFF`, bit à 0 = appuyé) ; champ 3 répété = un sous-message par axe
  `{1 : index, 2 : valeur sint32 zigzag}` de −100 à +100. Appuis et relâchements se
  déduisent en comparant deux trames consécutives.
- **0x15**, battement toutes les 0,99 s : 3 s sans trame = manette perdue.
- **0x19**, batterie toutes les 5 s : champ 2 en pourcentage (`19 10 53` = 83 %, la valeur
  lue au même moment sur 0x2A19).
- **0x2a**, une fois à la connexion, sans commande.

## Table des 16 commandes

Noms donnés par la personne qui tenait les manettes ; deux sessions complètes identiques.

| Manette | Commande | Signal |
|---|---|---|
| Gauche | Palette gauche | axe 0 → −100 |
| Gauche | Vitesse haut | bit 8 |
| Gauche | Vitesse bas | bit 9 |
| Gauche | Power up | bit 10 |
| Gauche | Flèche haut | bit 1 |
| Gauche | Flèche droite | bit 2 |
| Gauche | Flèche bas | bit 3 |
| Gauche | Flèche gauche | bit 0 |
| Droite | Palette droite | axe 1 → +100 |
| Droite | Vitesse haut | bit 12 |
| Droite | Vitesse bas | bit 13 |
| Droite | Power up | bit 14 |
| Droite | Y | bit 6 |
| Droite | A | bit 4 |
| Droite | B | bit 5 |
| Droite | Z | bit 7 |

Bits 11 et 15 vus le 16 octobre sans commande associée : origine inconnue.

## Pièges

- **Palettes sans bit** : elles ne bougent que leur axe. Seuil d'appui à |v| ≥ 50,
  relâchement sous 20 (les valeurs intermédiaires varient d'un appui à l'autre).
- **Zigzag** : lue en varint brut, la valeur sature à 199 et 200 ; décodée, c'est −100 et +100.
- **Fermer la liaison GATT** : une manette qui se croit encore connectée cesse d'annoncer et
  devient introuvable (diagnostic `bluetoothctl info`, remède `bluetoothctl disconnect`).
- **Verrou** : à partir du firmware 1.3, Zwift verrouille le Ride. Ne pas ouvrir l'app Zwift
  avec le Ride allumé.
