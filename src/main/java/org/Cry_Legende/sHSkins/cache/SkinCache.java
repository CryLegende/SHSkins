package org.Cry_Legende.sHSkins.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.model.SkinData;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Deux caches en memoire, aucun par joueur : un skin ou une cape change sur le site s'applique donc des la
 * connexion suivante, sans attendre l'expiration.
 * <ul>
 *     <li>comptes Minecraft : pseudo -> textures du compte Mojang du meme pseudo (vide = aucun compte) ;</li>
 *     <li>generations MineSkin : image (ou URL) du skin + cape -> textures signees. Le meme skin envoye par
 *     plusieurs joueurs, ou par le meme joueur a chaque connexion, ne coute qu'une generation.</li>
 * </ul>
 */
public class SkinCache {

    private static final int GENERATED_HOURS = 24;

    private volatile Cache<String, Optional<SkinData>> accounts;
    private volatile Cache<String, SkinData> generated;

    public SkinCache(PluginConfig config) {
        configure(config);
    }

    /**
     * Reconstruit les deux caches avec les reglages courants (cache.enabled, expire-after-write-minutes,
     * maximum-size) : les entrees en memoire sont perdues, les textures se redemandent a la connexion suivante.
     */
    public final void configure(PluginConfig config) {
        if (config.isCacheEnabled()) {
            this.accounts = Caffeine.newBuilder()
                .expireAfterWrite(config.getCacheExpireMinutes(), TimeUnit.MINUTES)
                .maximumSize(config.getCacheMaxSize())
                .build();
            this.generated = Caffeine.newBuilder()
                .expireAfterWrite(GENERATED_HOURS, TimeUnit.HOURS)
                .maximumSize(config.getCacheMaxSize())
                .build();
        } else {
            this.accounts = null;
            this.generated = null;
        }
    }

    /**
     * @return vide si le compte n'est pas en cache ; sinon les textures du compte (elles-memes vides sans compte)
     */
    public Optional<Optional<SkinData>> getAccount(String playerName) {
        Cache<String, Optional<SkinData>> cache = accounts;
        return cache == null ? Optional.empty() : Optional.ofNullable(cache.getIfPresent(key(playerName)));
    }

    public void putAccount(String playerName, Optional<SkinData> textures) {
        Cache<String, Optional<SkinData>> cache = accounts;
        if (cache != null) {
            cache.put(key(playerName), textures);
        }
    }

    public Optional<SkinData> getGenerated(String input) {
        Cache<String, SkinData> cache = generated;
        return cache == null ? Optional.empty() : Optional.ofNullable(cache.getIfPresent(input));
    }

    public void putGenerated(String input, SkinData skinData) {
        Cache<String, SkinData> cache = generated;
        if (cache != null) {
            cache.put(input, skinData);
        }
    }

    private static String key(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }
}
