package org.Cry_Legende.sHSkins.api;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.paper.SHSkinsPaper;
import org.Cry_Legende.sHSkins.paper.SkinRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Optional;
import java.util.UUID;

/**
 * Skins des joueurs SkyHand, connectes ou non, pour les autres plugins des serveurs Paper.
 * <p>
 * Le proxy enregistre le skin applique a chaque connexion ; SHSkins le garde en memoire sur chaque serveur.
 * Toutes les methodes repondent tout de suite, depuis n'importe quel thread, sans requete reseau.
 * <p>
 * Un plugin qui s'en sert declare {@code softdepend: [SHSkins]} (ou {@code depend}) et, en softdepend, verifie
 * {@code Bukkit.getPluginManager().isPluginEnabled("SHSkins")} avant d'appeler cette classe.
 * <pre>{@code
 * ItemStack head = SHSkinsAPI.createHead(offlinePlayer);
 * SHSkinsAPI.applyHead(skullMeta, uuid, name);
 * Optional<SkinData> skin = SHSkinsAPI.getSkin("Pseudo");
 * }</pre>
 */
public final class SHSkinsAPI {

    private static final String TEXTURES = "textures";

    private SHSkinsAPI() {
    }

    /** SHSkins est actif sur ce serveur et a lu les skins de la base. */
    public static boolean isReady() {
        SkinRegistry registry = registry();
        return registry != null && registry.isLoaded();
    }

    /** Skin d'un joueur par son UUID. */
    public static Optional<SkinData> getSkin(UUID uuid) {
        SkinRegistry registry = registry();
        return registry == null ? Optional.empty() : registry.find(uuid).map(SkinRegistry.Entry::skin);
    }

    /** Skin d'un joueur par son pseudo (majuscules indifferentes). */
    public static Optional<SkinData> getSkin(String name) {
        SkinRegistry registry = registry();
        return registry == null ? Optional.empty() : registry.find(name).map(SkinRegistry.Entry::skin);
    }

    /** Skin d'un joueur, par son UUID puis par son pseudo. */
    public static Optional<SkinData> getSkin(OfflinePlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        return getSkin(player.getUniqueId()).or(() -> getSkin(player.getName()));
    }

    /**
     * Profil du joueur avec ses textures quand SHSkins les connait (tete, PNJ, bloc de tete). Sans textures connues,
     * le profil n'a que l'UUID et le pseudo, et le client affiche le skin par defaut.
     *
     * @param uuid UUID du joueur, ou null pour celui du pseudo sur le reseau
     * @param name pseudo du joueur, ou null si seul l'UUID est connu
     */
    public static PlayerProfile createProfile(UUID uuid, String name) {
        UUID id = uuid != null ? uuid : (name != null ? SkinRegistry.offlineUuid(name) : null);
        Optional<SkinData> skin = getSkin(id).or(() -> getSkin(name));
        PlayerProfile profile = Bukkit.createProfile(id, name);
        skin.ifPresent(s -> profile.setProperty(new ProfileProperty(TEXTURES, s.value(), s.signature())));
        return profile;
    }

    /** Tete du joueur avec son skin. */
    public static ItemStack createHead(UUID uuid, String name) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> applyHead(meta, uuid, name));
        return head;
    }

    /** Tete du joueur avec son skin. */
    public static ItemStack createHead(OfflinePlayer player) {
        return createHead(player.getUniqueId(), player.getName());
    }

    /** Tete du joueur avec son skin, par son pseudo. */
    public static ItemStack createHead(String name) {
        return createHead(null, name);
    }

    /** Pose le skin du joueur sur une tete existante (nom, lore et le reste de l'objet ne changent pas). */
    public static void applyHead(SkullMeta meta, UUID uuid, String name) {
        meta.setPlayerProfile(createProfile(uuid, name));
    }

    private static SkinRegistry registry() {
        SHSkinsPaper plugin = SHSkinsPaper.instance();
        return plugin == null ? null : plugin.registry();
    }
}
