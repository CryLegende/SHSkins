<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHSkins

*Applique les skins SkyHand a la connexion*

<img src="https://img.shields.io/badge/Velocity-3.5.1-f2a900?style=for-the-badge" alt="Velocity 3.5.1"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Proxy-3aa655?style=for-the-badge" alt="SkyHand Proxy">

</div>

---

## Présentation

Applique les skins SkyHand a la connexion

Utilisé sur : **Proxy**.

## Skins et capes

À chaque connexion :

1. le skin envoyé sur le site (skin-api), généré par MineSkin avec la cape choisie sur le profil ;
2. sinon le skin du compte Minecraft du même pseudo, avec sa cape, régénéré seulement si le joueur a choisi une autre cape (ou aucune) ;
3. si le site ou Mojang ne répond pas : le dernier skin enregistré du joueur.

La cape se choisit sur le profil du site (carte « Ma cape », plugin skyhandsite) : celle du compte Minecraft (défaut), aucune, ou une cape officielle. MineSkin ne pose une cape que si la clé y a droit (offre Basic ou plus) : SHSkins relit toutes les heures les capes autorisées (`/v2/me/capes`), et sans offre payante les skins du site restent sans cape.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/skin` | `/skins` | Renvoie vers la page du site où changer de skin. | aucune |
| `/shskins reload` | — | Recharge `config.yml` et `messages.yml` ; si un fichier est illisible, la configuration précédente reste active. Vide le cache mémoire des skins et relit les capes MineSkin ; les skins enregistrés (`playerdata/`) ne sont pas touchés. | `shskins.admin.reload` |

## Permissions

| Permission | Description |
|---|---|
| `shskins.admin.reload` | `/shskins reload`. |

Sous Velocity, il n'y a pas de permission parente ni de valeur par défaut : seules la console et les joueurs à qui le gestionnaire de permissions du proxy accorde `shskins.admin.reload` ont droit à `/shskins`.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/shskins/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
