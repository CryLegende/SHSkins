package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.cache.SkinCache;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.model.CapeChoice;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.storage.SkinStorage;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Choisit les textures (skin + cape) d'un joueur a sa connexion :
 * <ol>
 *     <li>le skin envoye sur le site, genere par MineSkin avec la cape voulue ;</li>
 *     <li>sinon le skin du compte Minecraft du meme pseudo, deja signe par Mojang avec la cape du compte,
 *     regenere seulement si le joueur a choisi une autre cape (ou aucune) sur le site ;</li>
 *     <li>si le site ou Mojang ne repond pas : le dernier skin enregistre pour ce joueur.</li>
 * </ol>
 * La cape choisie sur le site (« auto » par defaut) : celle du compte Minecraft du meme pseudo, aucune, ou une
 * cape officielle. MineSkin ne pose une cape que si la cle y a droit (offre payante), voir {@link CapeCatalog}.
 */
public class SkinService {

    private static final String USER_AGENT = "SHSkins/" + SHSkins.VERSION;
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String MINESKIN_GENERATE = "https://api.mineskin.org/v2/generate";

    private enum Status { FOUND, ABSENT, ERROR }

    /**
     * Reponse d'une source : trouvee, absente (reponse nette : pas de skin, pas de compte) ou injoignable.
     */
    private record Fetched<T>(Status status, T value) {
        static <T> Fetched<T> found(T value) {
            return new Fetched<>(Status.FOUND, value);
        }

        static <T> Fetched<T> absent() {
            return new Fetched<>(Status.ABSENT, null);
        }

        static <T> Fetched<T> error() {
            return new Fetched<>(Status.ERROR, null);
        }

        Optional<T> optional() {
            return Optional.ofNullable(value);
        }
    }

    /**
     * Ce que MineSkin recoit : une image (skin du site, skin par defaut) ou l'URL d'une texture Mojang existante.
     */
    private record SkinSource(byte[] png, String url, String cacheKey) {
        static SkinSource image(byte[] png) {
            return new SkinSource(png, null, "png:" + sha256(png));
        }

        static SkinSource texture(String url) {
            return new SkinSource(null, url, "url:" + url);
        }
    }

    /**
     * Resultat d'une generation : les textures, ou rien ; {@code capeRefused} quand MineSkin refuse la cape.
     */
    private record Generation(SkinData skin, boolean capeRefused) {
    }

    /** Duree pendant laquelle une absence de compte Mojang est crue sans redemander. */
    private static final long ACCOUNT_ABSENT_TTL_MS = 24L * 60 * 60 * 1000;

    /** Joueurs dont la relecture Mojang en arriere-plan est en cours (une seule a la fois par joueur). */
    private final java.util.Set<UUID> accountRefreshes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Logger logger;
    private final SkinCache skinCache;
    private final SkinStorage skinStorage;
    private final PluginConfig config;
    private final CapeCatalog capeCatalog;
    private final HttpClient httpClient;

    public SkinService(Logger logger, SkinCache skinCache, SkinStorage skinStorage, PluginConfig config,
                       CapeCatalog capeCatalog, HttpClient httpClient) {
        this.logger = logger;
        this.skinCache = skinCache;
        this.skinStorage = skinStorage;
        this.config = config;
        this.capeCatalog = capeCatalog;
        this.httpClient = httpClient;
    }

    public static String userAgent() {
        return USER_AGENT;
    }

    /**
     * @param current textures deja portees par le profil de connexion (connexion premium), sinon vide
     * @return les textures a poser sur le profil ; vide pour garder le profil tel quel
     */
    public CompletableFuture<Optional<SkinData>> getSkinData(String playerName, UUID playerUUID, Optional<SkinData> current) {
        CompletableFuture<Fetched<byte[]>> siteSkin = fetchSiteSkin(playerName, playerUUID);
        CompletableFuture<CapeChoice> capeChoice = fetchCapeChoice(playerName, playerUUID);

        return siteSkin.thenCompose(skin -> capeChoice.thenCompose(cape -> {
            debug("Connexion de " + playerName + " : skin du site " + skin.status() + ", cape " + cape + ".");
            return switch (skin.status()) {
                case FOUND -> fromSiteSkin(playerName, playerUUID, current, skin.value(), cape);
                case ABSENT -> fromMinecraftAccount(playerName, playerUUID, current, cape);
                case ERROR -> {
                    // Site injoignable : le dernier skin enregistre (souvent celui du site) plutot que celui de Mojang
                    Optional<SkinData> stored = skinStorage.getSkin(playerUUID);
                    yield stored.isPresent()
                        ? completed(stored)
                        : fromMinecraftAccount(playerName, playerUUID, current, cape);
                }
            };
        }));
    }

    private CompletableFuture<Optional<SkinData>> fromSiteSkin(String playerName, UUID playerUUID,
                                                               Optional<SkinData> current, byte[] png, CapeChoice choice) {
        return wantedCape(playerName, playerUUID, current, choice)
            .thenCompose(cape -> generate(SkinSource.image(png), cape))
            .thenApply(generated -> {
                if (generated.isPresent()) {
                    skinStorage.saveSkin(playerUUID, generated.get());
                    return generated;
                }
                debug("MineSkin indisponible pour " + playerName + " : dernier skin enregistre.");
                return skinStorage.getSkin(playerUUID);
            });
    }

    /**
     * Cape MineSkin a poser sur le skin du site, parmi celles que la cle peut utiliser.
     */
    private CompletableFuture<Optional<String>> wantedCape(String playerName, UUID playerUUID,
                                                           Optional<SkinData> current, CapeChoice choice) {
        return switch (choice.mode()) {
            case NONE -> completed(Optional.empty());
            case CAPE -> completed(choice.cape().filter(capeCatalog::isUsable));
            // Sans cape utilisable, inutile d'interroger Mojang pour connaitre celle du compte
            case AUTO -> capeCatalog.isEmpty()
                ? completed(Optional.empty())
                : accountTextures(playerName, playerUUID, current).thenApply(account -> account.optional()
                    .flatMap(SkinData::capeHash)
                    .flatMap(capeCatalog::idForHash));
        };
    }

    private CompletableFuture<Optional<SkinData>> fromMinecraftAccount(String playerName, UUID playerUUID,
                                                                       Optional<SkinData> current, CapeChoice choice) {
        return accountTextures(playerName, playerUUID, current).thenCompose(account -> {
            if (account.status() == Status.ERROR) {
                debug("Mojang injoignable pour " + playerName + " : dernier skin enregistre.");
                return completed(skinStorage.getSkin(playerUUID));
            }
            Optional<SkinData> base = account.status() == Status.FOUND ? account.optional() : skinStorage.getSkin(playerUUID);
            return withCape(base, choice).thenApply(result -> {
                if (account.status() == Status.FOUND && current.isEmpty()) {
                    result.ifPresent(skin -> skinStorage.saveSkin(playerUUID, skin));
                }
                return result;
            });
        });
    }

    /**
     * Les textures du compte, avec la cape choisie sur le site. Elles ne sont regenerees que si la cape change.
     */
    private CompletableFuture<Optional<SkinData>> withCape(Optional<SkinData> base, CapeChoice choice) {
        Optional<String> wornCape = base.flatMap(SkinData::capeHash);
        switch (choice.mode()) {
            case AUTO -> {
                return completed(base);
            }
            case NONE -> {
                Optional<String> skinUrl = base.flatMap(SkinData::skinUrl);
                if (wornCape.isEmpty() || skinUrl.isEmpty()) {
                    return completed(base);
                }
                return generate(SkinSource.texture(skinUrl.get()), Optional.empty()).thenApply(result -> result.or(() -> base));
            }
            default -> {
                Optional<String> wantedHash = choice.cape().flatMap(capeCatalog::hashOf);
                if (wantedHash.isEmpty() || wantedHash.equals(wornCape)) {
                    return completed(base);
                }
                // Sans aucun skin, la cape est posee sur Steve
                Optional<SkinSource> source = base.isPresent()
                    ? base.flatMap(SkinData::skinUrl).map(SkinSource::texture)
                    : defaultSkin().map(SkinSource::image);
                if (source.isEmpty()) {
                    return completed(base);
                }
                return generate(source.get(), choice.cape()).thenApply(result -> result.or(() -> base));
            }
        }
    }

    /**
     * Textures du compte Minecraft du meme pseudo : celles du profil si la connexion est premium, sinon Mojang.
     */
    private CompletableFuture<Fetched<SkinData>> accountTextures(String playerName, UUID playerUUID, Optional<SkinData> current) {
        if (current.isPresent()) {
            return completed(Fetched.found(current.get()));
        }
        Optional<Optional<SkinData>> cached = skinCache.getAccount(playerName);
        if (cached.isPresent()) {
            return completed(cached.get().isPresent() ? Fetched.found(cached.get().get()) : Fetched.absent());
        }

        // Reponse de Mojang deja connue (fichier <uuid>.account.json) : servie tout de suite, sans attendre Mojang
        // pendant la connexion (il repond parfois en 4-5 s), puis relue en arriere-plan pour la connexion suivante.
        // Une absence de compte n'est gardee que 24 h.
        Optional<SkinStorage.AccountEntry> stored = skinStorage.getAccount(playerUUID);
        if (stored.isPresent() && (stored.get().found()
                || System.currentTimeMillis() - stored.get().checkedAt() < ACCOUNT_ABSENT_TTL_MS)) {
            Optional<SkinData> skin = stored.get().skin();
            debug("Compte Mojang de " + playerName + " lu dans son fichier (" + (skin.isPresent() ? "textures" : "pas de compte")
                + "), relu en arriere-plan.");
            skinCache.putAccount(playerName, skin);
            if (accountRefreshes.add(playerUUID)) {
                fetchAccount(playerName, playerUUID).whenComplete((result, error) -> accountRefreshes.remove(playerUUID));
            }
            return completed(skin.isPresent() ? Fetched.found(skin.get()) : Fetched.absent());
        }
        return fetchAccount(playerName, playerUUID);
    }

    /**
     * Interroge Mojang ; une reponse (compte ou absence de compte) remplit le cache memoire et le fichier du joueur.
     */
    private CompletableFuture<Fetched<SkinData>> fetchAccount(String playerName, UUID playerUUID) {
        // Le reseau tourne en UUID offline (version 3) : sauf UUID Mojang reel, il faut d'abord retrouver celui du pseudo
        CompletableFuture<Fetched<String>> mojangId = playerUUID.version() == 4
            ? completed(Fetched.found(playerUUID.toString().replace("-", "")))
            : lookupMojangId(playerName);

        return mojangId
            .thenCompose(id -> switch (id.status()) {
                case FOUND -> fetchMojangProfile(id.value());
                case ABSENT -> completed(Fetched.<SkinData>absent());
                case ERROR -> completed(Fetched.<SkinData>error());
            })
            .thenApply(result -> {
                if (result.status() != Status.ERROR) {
                    skinCache.putAccount(playerName, result.optional());
                    skinStorage.saveAccount(playerUUID, result.optional());
                }
                return result;
            });
    }

    private CompletableFuture<Fetched<byte[]>> fetchSiteSkin(String playerName, UUID playerUUID) {
        Optional<HttpRequest> request = siteRequest(config.getApiUrl(), playerName, playerUUID);
        if (request.isEmpty()) {
            return completed(Fetched.error());
        }

        return httpClient.sendAsync(request.get(), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                Optional<String> contentType = response.headers().firstValue("Content-Type");
                if (response.statusCode() == 404 || contentType.map(type -> type.contains("application/json")).orElse(false)) {
                    // skin-api repond en JSON 404 quand le joueur n'a pas envoye de skin
                    return Fetched.<byte[]>absent();
                }
                if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                    return Fetched.found(response.body());
                }
                debug("API des skins injoignable pour " + playerName + " (Code: " + response.statusCode() + ")");
                return Fetched.<byte[]>error();
            })
            .exceptionally(exception -> {
                logger.warn("Erreur de connexion a l'API des skins pour " + playerName, exception);
                return Fetched.error();
            });
    }

    /**
     * Cape choisie sur le site. Sans reponse exploitable : AUTO, la cape du compte Minecraft, comme avant ce choix.
     */
    private CompletableFuture<CapeChoice> fetchCapeChoice(String playerName, UUID playerUUID) {
        if (config.getCapeApiUrl().isBlank()) {
            return completed(CapeChoice.AUTO);
        }
        Optional<HttpRequest> request = siteRequest(config.getCapeApiUrl(), playerName, playerUUID);
        if (request.isEmpty()) {
            return completed(CapeChoice.AUTO);
        }

        return httpClient.sendAsync(request.get(), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    return CapeChoice.AUTO;
                }
                JsonElement cape = JsonParser.parseString(response.body()).getAsJsonObject().get("cape");
                return cape == null || cape.isJsonNull() ? CapeChoice.AUTO : CapeChoice.parse(cape.getAsString());
            })
            .exceptionally(exception -> {
                debug("Choix de cape illisible pour " + playerName + " : " + exception.getMessage());
                return CapeChoice.AUTO;
            });
    }

    private Optional<HttpRequest> siteRequest(String template, String playerName, UUID playerUUID) {
        String url = template
            .replace("%player%", URLEncoder.encode(playerName, StandardCharsets.UTF_8))
            .replace("%uuid%", playerUUID.toString());
        try {
            return Optional.of(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(5))
                .build());
        } catch (IllegalArgumentException e) {
            // URL de config invalide : sans ce garde, l'exception remonterait dans l'evenement de connexion de chaque joueur
            logger.warn("URL invalide pour " + playerName + " : " + url);
            return Optional.empty();
        }
    }

    /**
     * Genere (ou relit en cache) les textures signees d'un skin avec une cape. Si MineSkin refuse la cape, le skin
     * est genere sans elle : le joueur garde au moins son skin.
     */
    private CompletableFuture<Optional<SkinData>> generate(SkinSource source, Optional<String> cape) {
        String cacheKey = source.cacheKey() + "|" + cape.orElse("-");
        Optional<SkinData> cached = skinCache.getGenerated(cacheKey);
        if (cached.isPresent()) {
            return completed(cached);
        }

        return requestMineSkin(source, cape).thenCompose(generation -> {
            if (generation.skin() != null) {
                skinCache.putGenerated(cacheKey, generation.skin());
                return completed(Optional.of(generation.skin()));
            }
            if (generation.capeRefused() && cape.isPresent()) {
                capeCatalog.revoke();
                return generate(source, Optional.empty());
            }
            return completed(Optional.<SkinData>empty());
        });
    }

    private CompletableFuture<Generation> requestMineSkin(SkinSource source, Optional<String> cape) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(MINESKIN_GENERATE))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10));
        if (!config.getMineskinApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getMineskinApiKey());
        }

        if (source.png() != null) {
            String boundary = "----SHSkinsBoundary" + UUID.randomUUID().toString().replace("-", "");
            builder.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, cape, source.png()));
        } else {
            JsonObject body = new JsonObject();
            body.addProperty("url", source.url());
            body.addProperty("visibility", "public");
            cape.ifPresent(id -> body.addProperty("cape", id));
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 200) {
                    try {
                        JsonObject textureData = JsonParser.parseString(response.body()).getAsJsonObject()
                            .getAsJsonObject("skin")
                            .getAsJsonObject("texture")
                            .getAsJsonObject("data");
                        return new Generation(new SkinData(
                            textureData.get("value").getAsString(),
                            textureData.get("signature").getAsString()), false);
                    } catch (Exception e) {
                        logger.warn("Erreur de parsing de la reponse de MineSkin v2", e);
                        return new Generation(null, false);
                    }
                }
                if (cape.isPresent() && response.body().contains("\"insufficient_grants\"")) {
                    return new Generation(null, true);
                }
                logger.warn("Erreur MineSkin (Code: {}): {}", response.statusCode(), response.body());
                return new Generation(null, false);
            })
            .exceptionally(exception -> {
                logger.error("Impossible de contacter l'API MineSkin v2", exception);
                return new Generation(null, false);
            });
    }

    private static HttpRequest.BodyPublisher multipartBody(String boundary, Optional<String> cape, byte[] png) {
        List<byte[]> parts = new ArrayList<>();
        String separator = "--" + boundary + "\r\nContent-Disposition: form-data; name=";
        parts.add((separator + "\"visibility\"\r\n\r\npublic\r\n").getBytes(StandardCharsets.UTF_8));
        cape.ifPresent(id -> parts.add((separator + "\"cape\"\r\n\r\n" + id + "\r\n").getBytes(StandardCharsets.UTF_8)));
        parts.add((separator + "\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        parts.add(png);
        parts.add(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(parts);
    }

    private CompletableFuture<Fetched<String>> lookupMojangId(String playerName) {
        // Un pseudo hors de ce format n'existe pas chez Mojang, et il finirait tel quel dans l'URL
        if (!MOJANG_NAME.matcher(playerName).matches()) {
            return completed(Fetched.absent());
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + playerName))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(5))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 200) {
                    try {
                        return Fetched.found(JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString());
                    } catch (Exception e) {
                        logger.warn("Erreur de parsing de l'UUID Mojang de {}: {}", playerName, e.getMessage());
                        return Fetched.<String>error();
                    }
                }
                if (response.statusCode() == 404) {
                    debug("Aucun compte Mojang pour le pseudo " + playerName);
                    return Fetched.<String>absent();
                }
                logger.warn("Recherche Mojang du pseudo {} impossible (Code: {})", playerName, response.statusCode());
                return Fetched.<String>error();
            })
            .exceptionally(exception -> {
                logger.warn("Erreur de connexion a l'API Mojang", exception);
                return Fetched.error();
            });
    }

    private CompletableFuture<Fetched<SkinData>> fetchMojangProfile(String mojangId) {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + mojangId + "?unsigned=false";
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
                        for (JsonElement element : json.getAsJsonArray("properties")) {
                            JsonObject property = element.getAsJsonObject();
                            if ("textures".equals(property.get("name").getAsString())) {
                                return Fetched.found(new SkinData(
                                    property.get("value").getAsString(),
                                    property.get("signature").getAsString()));
                            }
                        }
                        return Fetched.<SkinData>absent();
                    } catch (Exception e) {
                        logger.warn("Erreur de parsing de la reponse de Mojang pour {}: {}", mojangId, e.getMessage());
                        return Fetched.<SkinData>error();
                    }
                }
                if (response.statusCode() == 204 || response.statusCode() == 404) {
                    return Fetched.<SkinData>absent();
                }
                return Fetched.<SkinData>error();
            })
            .exceptionally(exception -> {
                logger.warn("Erreur de connexion a l'API Mojang", exception);
                return Fetched.error();
            });
    }

    /**
     * Skin de Steve, embarque dans le jar : support d'une cape choisie par un joueur qui n'a aucun skin.
     */
    private static Optional<byte[]> defaultSkin() {
        try (InputStream in = SkinService.class.getClassLoader().getResourceAsStream("steve.png")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private void debug(String message) {
        if (config.isDebug()) {
            logger.info("[DEBUG] {}", message);
        }
    }
}
