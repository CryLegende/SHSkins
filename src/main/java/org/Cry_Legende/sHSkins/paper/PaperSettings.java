package org.Cry_Legende.sHSkins.paper;

import org.Cry_Legende.sHSkins.config.ConfigException;
import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Valeurs de config.yml sur un serveur Paper.
 *
 * @param refreshSeconds secondes entre deux relectures des skins modifies en base
 * @param unknown        texte rendu par les placeholders quand le skin du joueur n'est pas connu
 */
public record PaperSettings(SkinDatabase.Settings database, int refreshSeconds, String unknown, Pieces pieces) {

    /**
     * Morceaux de skins.
     *
     * @param enabled       ce serveur peut en faire (sinon il se sert de ceux deja faits)
     * @param application   application Microsoft qui connecte le compte Mojang
     * @param perMinute     depots par minute du compte Mojang
     * @param key           cle MineSkin
     * @param reserve       depots MineSkin de l'heure jamais pris par les morceaux
     * @param reserveNormal la meme chose pour les demandes qui ne sont pas urgentes
     * @param reserveLater  la meme chose pour celles qui ne font que pouvoir servir
     */
    public record Pieces(boolean enabled, String application, int perMinute, String key, int reserve, int reserveNormal, int reserveLater) {
    }

    /** Lit config.yml ; un fichier illisible leve une {@link ConfigException} qui porte le fichier et l'erreur. */
    public static PaperSettings load(File file) throws ConfigException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            throw new ConfigException(file.getName(), e);
        }
        return new PaperSettings(
            new SkinDatabase.Settings(
                yaml.getString("base-de-donnees.url", "jdbc:mariadb://127.0.0.1:3306/Global"),
                yaml.getString("base-de-donnees.utilisateur", "shskins"),
                yaml.getString("base-de-donnees.mot-de-passe", "")),
            Math.max(5, yaml.getInt("rafraichissement-secondes", 30)),
            yaml.getString("placeholders.inconnu", ""),
            new Pieces(
                yaml.getBoolean("morceaux.actif", false),
                yaml.getString("morceaux.compte-mojang.application", "00000000402b5328").trim(),
                Math.max(1, Math.min(20, yaml.getInt("morceaux.compte-mojang.par-minute", 16))),
                yaml.getString("morceaux.mineskin.cle", "").trim(),
                Math.max(0, yaml.getInt("morceaux.mineskin.reserve", 20)),
                Math.max(0, yaml.getInt("morceaux.mineskin.reserve-non-urgent", 50)),
                Math.max(0, yaml.getInt("morceaux.mineskin.reserve-plus-tard", 70))));
    }
}
