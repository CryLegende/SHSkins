package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Capes que la cle MineSkin a le droit de poser sur un skin (GET /v2/me/capes, champ « usable »).
 * <p>
 * MineSkin ne genere un skin avec cape que pour les offres payantes (Basic et au-dessus) : avec une cle gratuite
 * la liste est vide, et SHSkins ne demande alors jamais de cape. Elle est relue toutes les heures, donc un
 * abonnement pris (ou arrete) s'applique sans redemarrer le proxy.
 */
public class CapeCatalog {

    private static final String ME_CAPES = "https://api.mineskin.org/v2/me/capes";

    private final Logger logger;
    private final PluginConfig config;
    private final HttpClient httpClient;
    private final String userAgent;

    /** UUID MineSkin -> empreinte de la texture de la cape. */
    private volatile Map<String, String> usable = Map.of();

    public CapeCatalog(Logger logger, PluginConfig config, HttpClient httpClient, String userAgent) {
        this.logger = logger;
        this.config = config;
        this.httpClient = httpClient;
        this.userAgent = userAgent;
    }

    public boolean isEmpty() {
        return usable.isEmpty();
    }

    public boolean isUsable(String capeId) {
        return usable.containsKey(capeId);
    }

    public Optional<String> hashOf(String capeId) {
        return Optional.ofNullable(usable.get(capeId));
    }

    /**
     * Cape MineSkin qui porte cette texture (celle du compte Minecraft du joueur), si elle est utilisable.
     */
    public Optional<String> idForHash(String hash) {
        return usable.entrySet().stream()
            .filter(entry -> entry.getValue().equalsIgnoreCase(hash))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    /**
     * MineSkin a refuse une cape (abonnement termine) : plus aucune demande avec cape jusqu'a la prochaine lecture.
     */
    public void revoke() {
        if (!usable.isEmpty()) {
            usable = Map.of();
            logger.warn("MineSkin refuse les capes avec cette cle : skins generes sans cape jusqu'a la prochaine verification.");
        }
    }

    public void refresh() {
        String key = config.getMineskinApiKey();
        if (key.isBlank()) {
            usable = Map.of();
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ME_CAPES))
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer " + key)
            .timeout(Duration.ofSeconds(10))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() != 200) {
                    // Liste precedente gardee : une panne de MineSkin ne doit pas retirer les capes
                    logger.warn("Liste des capes MineSkin illisible (Code: {})", response.statusCode());
                    return;
                }
                Map<String, String> next = new HashMap<>();
                for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("capes")) {
                    JsonObject cape = element.getAsJsonObject();
                    if (cape.has("usable") && cape.get("usable").getAsBoolean() && cape.has("uuid") && cape.has("hash")) {
                        next.put(cape.get("uuid").getAsString(), cape.get("hash").getAsString());
                    }
                }
                if (!next.keySet().equals(usable.keySet())) {
                    logger.info("Capes MineSkin utilisables : {}", next.isEmpty() ? "aucune (offre gratuite)" : next.size());
                }
                usable = Map.copyOf(next);
            })
            .exceptionally(exception -> {
                logger.warn("Impossible de lire la liste des capes MineSkin : {}", exception.getMessage());
                return null;
            });
    }
}
