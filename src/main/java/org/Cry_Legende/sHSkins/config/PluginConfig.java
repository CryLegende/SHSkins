package org.Cry_Legende.sHSkins.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PluginConfig {

    private final Path dataFolder;
    private final Logger logger;

    private String apiUrl;
    private boolean cacheEnabled;
    private int cacheExpireMinutes;
    private int cacheMaxSize;
    private String mineskinApiKey;
    private boolean debug;

    public PluginConfig(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        loadConfig();
    }

    @SuppressWarnings("unchecked")
    public void loadConfig() {
        Path file = dataFolder.resolve("config.yml");
        try {
            Files.createDirectories(dataFolder);
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Could not create config.yml", e);
        }

        Map<String, Object> root = Map.of();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (loaded instanceof Map<?, ?> map) {
                root = (Map<String, Object>) map;
            }
        } catch (Exception e) {
            logger.error("Could not load config.yml", e);
        }

        this.apiUrl = string(root, "api-url", "https://skyhand.fr/api/skin-api/skins/%player%");
        Map<String, Object> cache = section(root, "cache");
        this.cacheEnabled = bool(cache, "enabled", true);
        this.cacheExpireMinutes = integer(cache, "expire-after-write-minutes", 60);
        this.cacheMaxSize = integer(cache, "maximum-size", 1000);
        this.mineskinApiKey = string(section(root, "mineskin"), "api-key", "");
        this.debug = bool(root, "debug", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return (v instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return (v instanceof Boolean b) ? b : def;
    }

    private static int integer(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return (v instanceof Number n) ? n.intValue() : def;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public int getCacheExpireMinutes() {
        return cacheExpireMinutes;
    }

    public int getCacheMaxSize() {
        return cacheMaxSize;
    }

    public String getMineskinApiKey() {
        return mineskinApiKey;
    }

    public boolean isDebug() {
        return debug;
    }
}
