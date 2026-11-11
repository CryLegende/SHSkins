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
public record PaperSettings(SkinDatabase.Settings database, int refreshSeconds, String unknown) {

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
            yaml.getString("placeholders.inconnu", ""));
    }
}
