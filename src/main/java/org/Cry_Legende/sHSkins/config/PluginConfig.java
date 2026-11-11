package org.Cry_Legende.sHSkins.config;

import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Valeurs de config.yml. Les champs sont lus a chaque connexion par les services qui gardent cet objet : un
 * rechargement les remplace sur place.
 */
public class PluginConfig {

    private final Path dataFolder;
    private final Logger logger;

    private volatile String apiUrl;
    private volatile String capeApiUrl;
    private volatile boolean cacheEnabled;
    private volatile int cacheExpireMinutes;
    private volatile int cacheMaxSize;
    private volatile String mineskinApiKey;
    private volatile boolean debug;
    /** Lue au demarrage seulement : changer la base demande un redemarrage du proxy. */
    private volatile SkinDatabase.Settings database;
    /** Ancien texte de /skin (commande-skin.message), repris dans messages.yml a la premiere creation. */
    private volatile String legacySkinCommandMessage;

    public PluginConfig(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        loadConfig();
    }

    /** Lecture du demarrage : un config.yml illisible est signale puis les valeurs par defaut servent. */
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
        try {
            root = read(file);
        } catch (Exception e) {
            logger.error("Could not load config.yml", e);
        }
        apply(root);
    }

    /**
     * Lit et valide config.yml sans rien changer ; renvoie l'etape qui applique les valeurs lues. En cas d'erreur
     * YAML l'exception porte le fichier et l'erreur, et la configuration en cours reste en place.
     */
    public Runnable prepareReload() throws ConfigException {
        Map<String, Object> root;
        try {
            root = read(dataFolder.resolve("config.yml"));
        } catch (Exception e) {
            throw new ConfigException("config.yml", e);
        }
        return () -> apply(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }

    private void apply(Map<String, Object> root) {
        this.apiUrl = string(root, "api-url", "https://skyhand.fr/api/skin-api/skins/%player%");
        // Vide (ancienne config) : pas de choix de cape, celle du compte Minecraft s'applique
        this.capeApiUrl = string(root, "cape-api-url", "");
        Map<String, Object> cache = section(root, "cache");
        this.cacheEnabled = bool(cache, "enabled", true);
        this.cacheExpireMinutes = integer(cache, "expire-after-write-minutes", 60);
        this.cacheMaxSize = integer(cache, "maximum-size", 1000);
        this.mineskinApiKey = string(section(root, "mineskin"), "api-key", "");
        this.legacySkinCommandMessage = string(section(root, "commande-skin"), "message", null);
        this.debug = bool(root, "debug", false);
        Map<String, Object> db = section(root, "base-de-donnees");
        this.database = new SkinDatabase.Settings(
            string(db, "url", "jdbc:mariadb://127.0.0.1:3306/Global"),
            string(db, "utilisateur", "shskins"),
            string(db, "mot-de-passe", ""));
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

    public String getCapeApiUrl() {
        return capeApiUrl;
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

    public SkinDatabase.Settings getDatabase() {
        return database;
    }

    /** Ancien texte de /skin lu dans config.yml, ou null : sert seulement a creer messages.yml. */
    public String getLegacySkinCommandMessage() {
        return legacySkinCommandMessage;
    }
}
