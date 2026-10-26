package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.cache.SkinCache;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.storage.SkinStorage;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SkinService {

    private static final String USER_AGENT = "SHSkins/" + SHSkins.VERSION;

    private final Logger logger;
    private final SkinCache skinCache;
    private final SkinStorage skinStorage;
    private final PluginConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public SkinService(Logger logger, SkinCache skinCache, SkinStorage skinStorage, PluginConfig config) {
        this.logger = logger;
        this.skinCache = skinCache;
        this.skinStorage = skinStorage;
        this.config = config;
    }

    public CompletableFuture<Optional<SkinData>> getSkinData(String playerName, UUID playerUUID) {
        Optional<SkinData> cachedSkin = skinCache.get(playerName);
        if (cachedSkin.isPresent()) {
            debug("Skin pour " + playerName + " trouve dans le cache en memoire.");
            return CompletableFuture.completedFuture(cachedSkin);
        }

        return fetchFromExternalApis(playerName, playerUUID)
            .thenCompose(apiSkinOptional -> {
                if (apiSkinOptional.isPresent()) {
                    SkinData authoritativeSkin = apiSkinOptional.get();
                    debug("Skin trouve via les APIs pour " + playerName + ". Mise a jour du cache et du stockage.");
                    skinCache.put(playerName, authoritativeSkin);
                    skinStorage.saveSkin(playerUUID, authoritativeSkin);
                    return CompletableFuture.completedFuture(Optional.of(authoritativeSkin));
                }
                debug("Aucun skin via les APIs pour " + playerName + ". Fallback sur le stockage disque.");
                Optional<SkinData> storedSkin = skinStorage.getSkin(playerUUID);
                storedSkin.ifPresent(s -> skinCache.put(playerName, s));
                return CompletableFuture.completedFuture(storedSkin);
            });
    }

    private CompletableFuture<Optional<SkinData>> fetchFromExternalApis(String playerName, UUID playerUUID) {
        return fetchImageDataFromCustomApi(playerName, playerUUID)
            .thenCompose(imageDataOptional -> imageDataOptional
                .map(this::generateSkinFromImageData)
                .orElseGet(() -> fetchFromMojang(playerUUID)));
    }

    private CompletableFuture<Optional<byte[]>> fetchImageDataFromCustomApi(String playerName, UUID playerUUID) {
        String url = config.getApiUrl()
            .replace("%player%", playerName)
            .replace("%uuid%", playerUUID.toString());
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(5))
                .build();
        } catch (IllegalArgumentException e) {
            // URL de config invalide : sans ce garde, l'exception remonterait dans l'evenement de connexion de chaque joueur
            logger.warn("api-url invalide pour " + playerName + " : " + url);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                Optional<String> contentType = response.headers().firstValue("Content-Type");
                if (contentType.isPresent() && contentType.get().contains("application/json")) {
                    debug("L'API perso a retourne une reponse JSON (erreur 404) pour " + playerName);
                    return Optional.<byte[]>empty();
                }
                if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                    debug("Donnees d'image recuperees depuis l'API personnalisee pour " + playerName);
                    return Optional.of(response.body());
                }
                debug("API personnalisee n'a rien retourne pour " + playerName + " (Code: " + response.statusCode() + ")");
                return Optional.<byte[]>empty();
            })
            .exceptionally(exception -> {
                logger.warn("Erreur de connexion a l'API personnalisee pour " + playerName, exception);
                return Optional.empty();
            });
    }

    private CompletableFuture<Optional<SkinData>> generateSkinFromImageData(byte[] imageData) {
        String mineskinUrl = "https://api.mineskin.org/v2/generate";
        try {
            String boundary = "----SHSkinsBoundary" + UUID.randomUUID().toString().replace("-", "");
            Map<Object, Object> data = new HashMap<>();
            data.put("visibility", "public");
            data.put("file", imageData);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mineskinUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .POST(buildMultipartBody(data, boundary));

            if (!config.getMineskinApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + config.getMineskinApiKey());
            }

            return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            JsonObject textureData = json.getAsJsonObject("skin")
                                .getAsJsonObject("texture")
                                .getAsJsonObject("data");
                            return Optional.of(new SkinData(
                                textureData.get("value").getAsString(),
                                textureData.get("signature").getAsString()));
                        } catch (Exception e) {
                            logger.warn("Erreur de parsing de la reponse de MineSkin v2", e);
                        }
                    }
                    logger.warn("Erreur MineSkin (Code: {}): {}", response.statusCode(), response.body());
                    return Optional.<SkinData>empty();
                })
                .exceptionally(exception -> {
                    logger.error("Impossible de contacter l'API MineSkin v2", exception);
                    return Optional.empty();
                });
        } catch (IOException e) {
            logger.error("Erreur lors de la construction de la requete multipart", e);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private HttpRequest.BodyPublisher buildMultipartBody(Map<Object, Object> data, String boundary) throws IOException {
        var byteArrays = new ArrayList<byte[]>();
        byte[] separator = ("--" + boundary + "\r\nContent-Disposition: form-data; name=")
            .getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            byteArrays.add(separator);
            if (entry.getValue() instanceof byte[] bytes) {
                byteArrays.add(("\"" + entry.getKey() + "\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
                byteArrays.add(bytes);
                byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                byteArrays.add(("\"" + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
        }
        byteArrays.add(("--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(byteArrays);
    }

    private CompletableFuture<Optional<SkinData>> fetchFromMojang(UUID playerUUID) {
        // Le reseau tourne en UUID offline (version 3) : ce fallback ne se declenche
        // que pour un profil premium reel, exactement comme la version BungeeCord.
        if (playerUUID.version() != 4) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/"
            + playerUUID.toString().replace("-", "") + "?unsigned=false";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(5))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 200) {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        JsonObject properties = json.getAsJsonArray("properties").get(0).getAsJsonObject();
                        return Optional.of(new SkinData(
                            properties.get("value").getAsString(),
                            properties.get("signature").getAsString()));
                    } catch (Exception e) {
                        logger.warn("Erreur de parsing de la reponse de Mojang pour {}: {}", playerUUID, e.getMessage());
                    }
                }
                return Optional.<SkinData>empty();
            })
            .exceptionally(exception -> {
                logger.warn("Erreur de connexion a l'API Mojang", exception);
                return Optional.empty();
            });
    }

    private void debug(String message) {
        if (config.isDebug()) {
            logger.info("[DEBUG] {}", message);
        }
    }
}
