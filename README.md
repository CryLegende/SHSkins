<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHSkins

*Les skins SkyHand, de la connexion aux têtes des joueurs hors ligne*

<img src="https://img.shields.io/badge/Velocity-3.5.1-f2a900?style=for-the-badge" alt="Velocity 3.5.1"> <img src="https://img.shields.io/badge/Paper-26.3-3a6ee8?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Proxy%20%2B%20serveurs-3aa655?style=for-the-badge" alt="SkyHand Proxy + serveurs">

</div>

---

## Présentation

SHSkins gère les skins de tout le réseau. Un seul jar, qui se pose sur le proxy et sur les serveurs Paper :

- **proxy (Velocity)** : choisit le skin de chaque joueur à sa connexion, le lui applique et l'enregistre dans MariaDB ;
- **serveurs (Paper)** : gardent en mémoire le skin de tous les joueurs et le donnent aux autres plugins (API) et aux menus (placeholders), y compris pour les joueurs hors ligne.

Utilisé sur : **Proxy**, **Opale**.

## Skins et capes (proxy)

À chaque connexion :

1. le skin envoyé sur le site (skin-api), généré par MineSkin avec la cape choisie sur le profil ;
2. sinon le skin du compte Minecraft du même pseudo, avec sa cape, régénéré seulement si le joueur a choisi une autre cape (ou aucune) ;
3. si le site ou Mojang ne répond pas : le dernier skin enregistré du joueur.

Le skin appliqué (ou celui déjà porté par une connexion premium) est enregistré dans `shskins_skins` avec le pseudo. La dernière réponse de Mojang pour le pseudo est gardée dans `shskins_accounts` : un joueur déjà venu se connecte sans attendre Mojang, la réponse est relue en arrière-plan.

La cape se choisit sur le profil du site (carte « Ma cape », plugin skyhandsite) : celle du compte Minecraft (défaut), aucune, ou une cape officielle. MineSkin ne pose une cape que si la clé y a droit (offre Basic ou plus) : SHSkins relit toutes les heures les capes autorisées (`/v2/me/capes`), et sans offre payante les skins du site restent sans cape.

Les versions 2.x gardaient un fichier par joueur dans `plugins/shskins/playerdata/` : au premier démarrage de la 3.0, ces fichiers sont repris dans la base (sans écraser un skin plus récent) et le dossier est renommé `playerdata-importe`.

## Skins des joueurs hors ligne (serveurs)

Au démarrage, SHSkins lit toute la table `shskins_skins`, puis toutes les `rafraichissement-secondes` les lignes écrites depuis. Un joueur qui rejoint le serveur est pris tel quel depuis son profil. Les recherches se font en mémoire : elles répondent tout de suite, depuis n'importe quel thread.

Les têtes faites par SHSkins portent les textures du skin : le client les affiche directement, sans rien demander à Mojang (qui ne connaît pas les UUID offline du réseau).

### API (plugins SkyHand)

`org.Cry_Legende.sHSkins.api.SHSkinsAPI` :

| Méthode | Rôle |
|---|---|
| `createHead(OfflinePlayer)`, `createHead(UUID, String)`, `createHead(String)` | tête du joueur avec son skin |
| `applyHead(SkullMeta, UUID, String)` | pose le skin sur une tête existante (nom et lore gardés) |
| `createProfile(UUID, String)` | profil avec les textures (PNJ, bloc de tête) |
| `getSkin(UUID)`, `getSkin(String)`, `getSkin(OfflinePlayer)` | textures (`SkinData` : valeur, signature, `skinUrl()`) |
| `isReady()` | SHSkins actif et skins lus depuis la base |

Le plugin qui s'en sert déclare `softdepend: [SHSkins]` (ou `depend`) et, en `softdepend`, vérifie `Bukkit.getPluginManager().isPluginEnabled("SHSkins")` avant d'appeler l'API. Dépendance Maven : le jar installé dans le dépôt local (`mvn install`), en `provided`.

### Placeholders (menus et plugins tiers)

| Placeholder | Rendu |
|---|---|
| `%shskins_texture_<pseudo>%` | textures du skin en base64, le format de `basehead-` dans DeluxeMenus |
| `%shskins_url_<pseudo>%` | adresse de l'image du skin |
| `%shskins_texture%`, `%shskins_url%` | la même chose pour le joueur qui regarde |

Le pseudo peut venir d'un autre placeholder entre accolades. Exemple DeluxeMenus (classements ajLeaderboards) :

```yaml
material: 'basehead-%shskins_texture_{ajlb_lb_vault_eco_balance_fixed_1_alltime_name}%'
```

Skin inconnu : `placeholders.inconnu` (vide par défaut, la tête garde le skin par défaut).

## Commandes

| Où | Commande | Alias | Description | Permission |
|---|---|---|---|---|
| Proxy | `/skin` | `/skins` | Renvoie vers la page du site où changer de skin. | aucune |
| Proxy | `/shskins reload` | — | Recharge `config.yml` et `messages.yml` ; si un fichier est illisible, la configuration précédente reste active. Vide le cache mémoire et relit les capes MineSkin. `base-de-donnees` demande un redémarrage. | `shskins.admin.reload` |
| Serveur | `/shskins info <pseudo>` | — | Skin connu pour ce joueur : adresse de l'image et date où le proxy l'a appliqué. | `shskins.admin.info` |
| Serveur | `/shskins reload` | — | Recharge `config.yml` et `messages.yml` ; `base-de-donnees` demande un redémarrage du serveur. | `shskins.admin.reload` |

En jeu, `/shskins` est celle du proxy : celle des serveurs se lance depuis leur console.

## Permissions

| Permission | Description |
|---|---|
| `shskins.admin.reload` | `/shskins reload` (proxy et serveurs). |
| `shskins.admin.info` | `/shskins info` (serveurs). |
| `shskins.admin` | Parent des deux sur les serveurs (op par défaut). |

Sous Velocity, il n'y a pas de permission parente ni de valeur par défaut : seules la console et les joueurs à qui le gestionnaire de permissions du proxy accorde `shskins.admin.reload` ont droit à `/shskins`.

## Base de données

Tables dans la base de `base-de-donnees.url` : [`schema.sql`](schema.sql). L'utilisateur MariaDB `shskins` n'a que `SELECT, INSERT, UPDATE` sur `shskins_skins` et `shskins_accounts`. Les serveurs ne font que lire.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/shskins/            (proxy)
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
plugins/SHSkins/            (serveurs Paper)
├── <a href="src/main/resources/paper/config.yml">config.yml</a>
└── <a href="src/main/resources/paper/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. `mvn install` le met aussi dans le dépôt Maven local pour les plugins qui utilisent l'API.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
