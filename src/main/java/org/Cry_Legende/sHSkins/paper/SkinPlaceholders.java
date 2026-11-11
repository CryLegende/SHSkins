package org.Cry_Legende.sHSkins.paper;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHSkins.api.SHSkinsAPI;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.bukkit.OfflinePlayer;

import java.util.Optional;

/**
 * Placeholders de SHSkins, pour les plugins qui ne passent pas par {@link SHSkinsAPI} (menus DeluxeMenus...) :
 * <ul>
 *     <li>{@code %shskins_texture%} / {@code %shskins_texture_<pseudo>%} : textures du skin en base64, le format
 *     de {@code basehead-} dans DeluxeMenus ;</li>
 *     <li>{@code %shskins_url%} / {@code %shskins_url_<pseudo>%} : adresse de l'image du skin.</li>
 * </ul>
 * Le pseudo peut venir d'un autre placeholder entre accolades : {@code %shskins_texture_{ajlb_lb_..._name}%}.
 */
final class SkinPlaceholders extends PlaceholderExpansion {

    private final SHSkinsPaper plugin;

    SkinPlaceholders(SHSkinsPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "shskins";
    }

    @Override
    public String getAuthor() {
        return "Cry_Legende";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        int separator = params.indexOf('_');
        String type = separator < 0 ? params : params.substring(0, separator);
        String target = separator < 0 ? "" : params.substring(separator + 1);
        if (!type.equals("texture") && !type.equals("url")) {
            return null;
        }

        Optional<SkinData> skin;
        if (target.isEmpty()) {
            skin = SHSkinsAPI.getSkin(player);
        } else {
            String name = target.indexOf('{') >= 0 ? PlaceholderAPI.setBracketPlaceholders(player, target) : target;
            skin = SHSkinsAPI.getSkin(name.strip());
        }

        Optional<String> value = type.equals("texture") ? skin.map(SkinData::value) : skin.flatMap(SkinData::skinUrl);
        return value.orElse(plugin.settings().unknown());
    }
}
