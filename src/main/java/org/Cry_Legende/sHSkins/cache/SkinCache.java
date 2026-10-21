package org.Cry_Legende.sHSkins.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.model.SkinData;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class SkinCache {

    private final Cache<String, SkinData> cache;
    private final boolean isEnabled;

    public SkinCache(PluginConfig config) {
        this.isEnabled = config.isCacheEnabled();
        if (this.isEnabled) {
            this.cache = Caffeine.newBuilder()
                .expireAfterWrite(config.getCacheExpireMinutes(), TimeUnit.MINUTES)
                .maximumSize(config.getCacheMaxSize())
                .build();
        } else {
            this.cache = null;
        }
    }

    public Optional<SkinData> get(String playerName) {
        if (!isEnabled) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(playerName.toLowerCase(Locale.ROOT)));
    }

    public void put(String playerName, SkinData skinData) {
        if (isEnabled) {
            cache.put(playerName.toLowerCase(Locale.ROOT), skinData);
        }
    }
}
