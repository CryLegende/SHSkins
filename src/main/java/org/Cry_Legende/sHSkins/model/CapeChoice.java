package org.Cry_Legende.sHSkins.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cape choisie par le joueur sur le site (profil, carte « Ma cape »).
 *
 * @param mode   AUTO : la cape du compte Minecraft du meme pseudo (defaut, aussi quand le site ne repond pas) ;
 *               NONE : aucune cape ; CAPE : la cape officielle {@code capeId}.
 * @param capeId UUID MineSkin de la cape (mode CAPE uniquement), voir https://api.mineskin.org/v2/capes
 */
public record CapeChoice(Mode mode, String capeId) {

    public enum Mode { AUTO, NONE, CAPE }

    public static final CapeChoice AUTO = new CapeChoice(Mode.AUTO, null);
    public static final CapeChoice NONE = new CapeChoice(Mode.NONE, null);

    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * Lit la valeur renvoyee par le site : "auto", "none" ou un UUID de cape. Toute autre valeur vaut AUTO.
     */
    public static CapeChoice parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("none")) {
            return NONE;
        }
        return UUID_FORMAT.matcher(value).matches() ? new CapeChoice(Mode.CAPE, value) : AUTO;
    }

    public Optional<String> cape() {
        return Optional.ofNullable(capeId);
    }

    @Override
    public String toString() {
        return mode == Mode.CAPE ? capeId : mode.name().toLowerCase(Locale.ROOT);
    }
}
