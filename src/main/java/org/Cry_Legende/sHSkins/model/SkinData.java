package org.Cry_Legende.sHSkins.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Represente les donnees d'un skin (valeur et signature).
 * <p>
 * La valeur est le JSON signe par Mojang, en base64, qui donne l'URL de la texture du skin et, si le profil
 * en porte une, celle de la cape. La signature couvre les deux : on ne peut pas ajouter une cape a un skin
 * sans regenerer les textures (MineSkin).
 */
public record SkinData(String value, String signature) {

    private static final String TEXTURE_PATH = "/texture/";

    /**
     * Empreinte de la cape portee (fin de son URL textures.minecraft.net), si ces textures en ont une.
     */
    public Optional<String> capeHash() {
        return textureUrl("CAPE").map(url -> url.substring(url.lastIndexOf(TEXTURE_PATH) + TEXTURE_PATH.length()));
    }

    /**
     * URL de la texture du skin, pour le regenerer avec une autre cape.
     */
    public Optional<String> skinUrl() {
        return textureUrl("SKIN");
    }

    private Optional<String> textureUrl(String type) {
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
            if (textures == null || !textures.has(type)) {
                return Optional.empty();
            }
            JsonElement url = textures.getAsJsonObject(type).get("url");
            if (url == null || !url.getAsString().contains(TEXTURE_PATH)) {
                return Optional.empty();
            }
            return Optional.of(url.getAsString());
        } catch (RuntimeException e) {
            // Valeur illisible (fichier de stockage abime, texture d'un autre format) : comme sans cape
            return Optional.empty();
        }
    }
}
