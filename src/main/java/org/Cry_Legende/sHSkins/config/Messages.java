package org.Cry_Legende.sHSkins.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Textes de messages.yml, codes couleur &amp;. Une cle absente du fichier prend le texte du jar, une cle vide
 * desactive le message. Un rechargement remplace tous les textes d'un coup.
 */
public final class Messages {

    private static final String RESOURCE = "messages.yml";
    /** Cle du texte de /skin, reprise de l'ancien commande-skin.message de config.yml. */
    private static final String SKIN_COMMAND = "skin-command";

    // extractUrls rend le lien du site cliquable dans le chat
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.AMPERSAND_CHAR)
        .extractUrls()
        .build();

    private final Path file;
    private final Map<String, String> defaults;
    private volatile Map<String, String> texts;

    /**
     * Cree messages.yml s'il n'existe pas (le fichier du jar, avec le texte de /skin de l'ancien config.yml quand il
     * y en a un) puis le lit. Un fichier illisible est signale et les textes du jar servent.
     */
    public Messages(Path dataFolder, Logger logger, String legacySkinCommand) {
        this(dataFolder, logger, RESOURCE, legacySkinCommand);
    }

    /**
     * @param jarResource fichier du jar qui sert de modele et de textes par defaut (le proxy et les serveurs Paper
     *                    n'ont pas les memes messages)
     */
    public Messages(Path dataFolder, Logger logger, String jarResource, String legacySkinCommand) {
        this.file = dataFolder.resolve(RESOURCE);
        String jar = jarText(jarResource);
        this.defaults = flatten(parse(jar));
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(dataFolder);
                Files.writeString(file, withSkinCommand(jar, legacySkinCommand), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Could not create messages.yml", e);
            }
        }
        Map<String, String> loaded = defaults;
        try {
            loaded = merge(read());
        } catch (ConfigException e) {
            logger.error("messages.yml illisible, textes du jar utilises : {}", e.getMessage());
        }
        this.texts = loaded;
    }

    /**
     * Lit et valide messages.yml sans rien changer ; renvoie l'etape qui remplace les textes. En cas d'erreur YAML
     * l'exception porte le fichier et l'erreur, et les textes en cours restent en place.
     */
    public Runnable prepareReload() throws ConfigException {
        Map<String, String> loaded = merge(read());
        return () -> this.texts = loaded;
    }

    /** Texte de la cle, ou null s'il est absent ou vide. Chaque paire de {@code pairs} remplace un {nom}. */
    public String text(String key, String... pairs) {
        String text = texts.get(key);
        if (text == null) {
            return null;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    /** Composant colore de la cle (liens cliquables), ou null s'il est absent ou vide. */
    public Component component(String key, String... pairs) {
        String text = text(key, pairs);
        return text == null ? null : LEGACY.deserialize(text);
    }

    private Map<String, Object> read() throws ConfigException {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException(RESOURCE, e);
        } catch (RuntimeException e) {
            throw new ConfigException(RESOURCE, e);
        }
    }

    private Map<String, String> merge(Map<String, Object> fileValues) {
        Map<String, String> merged = new HashMap<>(defaults);
        merged.putAll(flatten(fileValues));
        merged.values().removeIf(String::isEmpty);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        Object loaded = new Yaml().load(new StringReader(yaml));
        return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, String> flatten(Map<String, Object> values) {
        Map<String, String> out = new HashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                out.put(key, String.valueOf(value));
            }
        });
        return out;
    }

    private static String jarText(String resource) {
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Le fichier du jar, dont la ligne skin-command prend l'ancien texte de /skin quand il existe. */
    private static String withSkinCommand(String jar, String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return jar;
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setWidth(Integer.MAX_VALUE);
        String quoted = new Yaml(options).dump(legacy).strip();
        StringBuilder out = new StringBuilder();
        for (String line : jar.split("\n", -1)) {
            out.append(line.startsWith(SKIN_COMMAND + ":") ? SKIN_COMMAND + ": " + quoted : line).append('\n');
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
