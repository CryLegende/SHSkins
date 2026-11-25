package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.api.PiecePriority;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Un compte Minecraft qui pose des textures en changeant son propre skin : chaque image devient son skin, Mojang en rend
 * l'empreinte, qui reste valable ensuite. Un compte inutilise, donc : son skin change a chaque depot.
 * <p>
 * Il se connecte une fois, par un code a taper sur microsoft.com/link : ni mot de passe ni jeton ne passe par le serveur a la main.
 * Seul le jeton de renouvellement est garde, dans un fichier lisible par le serveur seul. Bloquant : hors du thread principal.
 */
public final class MojangAccount implements TextureHost {

    /**
     * Code a taper pour connecter le compte.
     *
     * @param url     page ou le taper
     * @param minutes duree de validite
     */
    public record Login(String code, String url, long minutes) {
    }

    /** @param refresh jeton de renouvellement Microsoft */
    private record Saved(String refresh, String name, String uuid, long renewedAt) {
    }

    private static final String LIVE_CODE = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String AZURE_CODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String AZURE_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String AZURE_SCOPE = "XboxLive.signin offline_access";
    private static final String XBOX = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT = "https://api.minecraftservices.com";
    private static final String USER_AGENT = "SHSkins/" + SHSkins.VERSION;
    private static final long DAY_MS = 86_400_000;
    private static final int TRIES = 2;

    private final Path file;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final UploadPace pace;
    /** Un seul depot a la fois : chacun change le skin du compte. */
    private final ReentrantLock uploading = new ReentrantLock(true);
    private volatile String application;
    private volatile Saved saved;
    private volatile String token;
    private volatile long tokenUntil;
    private volatile CompletableFuture<String> connecting;

    /**
     * @param file        fichier du compte
     * @param application identifiant de l'application Microsoft : seize chiffres (login.live.com) ou l'UUID d'une application Azure
     * @param perMinute   depots par minute
     */
    public MojangAccount(Path file, String application, int perMinute, Logger logger) {
        this.file = file;
        this.application = application;
        this.logger = logger;
        pace = new UploadPace(perMinute);
        saved = read();
    }

    public void configure(String application, int perMinute) {
        this.application = application;
        pace.perMinute(perMinute);
    }

    /** Nom du compte connecte, null s'il n'y en a pas. */
    public String name() {
        Saved account = saved;
        return account == null ? null : account.name;
    }

    /** Depots encore permis dans la minute. */
    public int left() {
        return saved == null ? 0 : pace.left(System.currentTimeMillis());
    }

    /** Minutes de pause apres un refus de Mojang, 0 s'il n'y en a pas. */
    public long pausedMinutes() {
        return (pace.pausedFor(System.currentTimeMillis()) + 59_999) / 60_000;
    }

    // ---------------------------------------------------------------- connexion

    /**
     * Connecte un compte. Le code a taper est donne tout de suite, la reponse est le nom du compte une fois qu'il l'a ete.
     */
    public synchronized CompletableFuture<String> connect(Consumer<Login> shown) {
        if (connecting != null && !connecting.isDone()) {
            return CompletableFuture.failedFuture(new IllegalStateException("une connexion est déjà en attente"));
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        connecting = result;
        Thread.ofVirtual().name("SHSkins compte").start(() -> {
            try {
                result.complete(login(shown));
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            } catch (InterruptedException e) {
                result.completeExceptionally(new IOException("interrompu"));
            }
        });
        return result;
    }

    public synchronized void disconnect() throws IOException {
        saved = null;
        token = null;
        Files.deleteIfExists(file);
    }

    private boolean live() {
        return application.matches("[0-9a-fA-F]{16}");
    }

    private String login(Consumer<Login> shown) throws IOException, InterruptedException {
        JsonObject start = form(live() ? LIVE_CODE : AZURE_CODE, live()
            ? Map.of("client_id", application, "scope", LIVE_SCOPE, "response_type", "device_code")
            : Map.of("client_id", application, "scope", AZURE_SCOPE));
        String device = text(start, "device_code");
        if (device == null) {
            throw new IOException("Microsoft refuse la demande de code (" + reason(start) + ")");
        }
        long expires = number(start, "expires_in", 900);
        long interval = Math.max(2, number(start, "interval", 5));
        shown.accept(new Login(text(start, "user_code"), text(start, "verification_uri"), expires / 60));
        long until = System.currentTimeMillis() + expires * 1000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(interval * 1000);
            JsonObject answer = form(live() ? LIVE_TOKEN + "?client_id=" + application : AZURE_TOKEN, Map.of("client_id", application,
                "device_code", device, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
            String access = text(answer, "access_token");
            if (access != null) {
                String minecraft = minecraft(access);
                JsonObject profile = profile(minecraft);
                store(new Saved(text(answer, "refresh_token"), text(profile, "name"), text(profile, "id"), System.currentTimeMillis()));
                return text(profile, "name");
            }
            String error = text(answer, "error");
            if ("slow_down".equals(error)) {
                interval += 5;
            } else if (!"authorization_pending".equals(error)) {
                throw new IOException("connexion refusée (" + reason(answer) + ")");
            }
        }
        throw new IOException("code expiré sans avoir été tapé");
    }

    /** Jeton Microsoft vers jeton Minecraft, par Xbox Live. */
    private String minecraft(String access) throws IOException, InterruptedException {
        JsonObject xboxProperties = new JsonObject();
        xboxProperties.addProperty("AuthMethod", "RPS");
        xboxProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        // login.live.com donne un ticket « t= », une application Azure un jeton « d= »
        xboxProperties.addProperty("RpsTicket", (live() ? "t=" : "d=") + access);
        JsonObject xbox = post(XBOX, token("http://auth.xboxlive.com", xboxProperties), null).body();
        JsonArray users = new JsonArray();
        users.add(required(xbox, "Token", "Xbox Live"));
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        xstsProperties.add("UserTokens", users);
        JsonObject xsts = post(XSTS, token("rp://api.minecraftservices.com/", xstsProperties), null).body();
        if (xsts.has("XErr")) {
            throw new IOException(xboxError(xsts.get("XErr").getAsLong()));
        }
        String hash = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject identity = new JsonObject();
        identity.addProperty("identityToken", "XBL3.0 x=" + hash + ";" + required(xsts, "Token", "XSTS"));
        Answer login = post(MINECRAFT + "/authentication/login_with_xbox", identity, null);
        if (login.status() == 429) {
            throw new Unavailable("Mojang limite les connexions, " + paused(login) + " de pause");
        }
        if (login.status() == 403 && login.body().toString().contains("ACCOUNT_SUSPENDED")) {
            // Trop de refus : Mojang leve la suspension sous 24 h
            pace.pause(System.currentTimeMillis(), DAY_MS);
            throw new Unavailable("compte suspendu par Mojang, pause de 24 h");
        }
        if (login.status() == 403) {
            throw new IOException("Mojang refuse cette application (" + reason(login.body()) + ")");
        }
        token = required(login.body(), "access_token", "Minecraft");
        tokenUntil = System.currentTimeMillis() + number(login.body(), "expires_in", 86_400) * 1000 - 600_000;
        return token;
    }

    private JsonObject profile(String minecraft) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(MINECRAFT + "/minecraft/profile").header("Authorization", "Bearer " + minecraft)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("ce compte n'a pas Minecraft Java");
        }
        JsonObject profile = json(response.body());
        if (response.statusCode() != 200 || text(profile, "name") == null) {
            throw new IOException("profil illisible (" + response.statusCode() + ")");
        }
        return profile;
    }

    /** Un jeton Minecraft valable, renouvele au besoin. */
    private String fresh() throws IOException, InterruptedException {
        Saved account = saved;
        if (account == null) {
            throw new Unavailable("aucun compte connecté");
        }
        if (token != null && System.currentTimeMillis() < tokenUntil) {
            return token;
        }
        JsonObject answer = form(live() ? LIVE_TOKEN : AZURE_TOKEN, Map.of("client_id", application, "grant_type", "refresh_token",
            "refresh_token", account.refresh, "scope", live() ? LIVE_SCOPE : AZURE_SCOPE));
        String access = text(answer, "access_token");
        if (access == null) {
            if ("invalid_grant".equals(text(answer, "error"))) {
                disconnect();
                logger.warn("Le compte {} n'est plus connecté (mot de passe changé, ou accès retiré) : « shskins compte connecter ».", account.name);
            }
            throw new Unavailable("Microsoft refuse le compte (" + reason(answer) + ")");
        }
        String refresh = text(answer, "refresh_token");
        store(new Saved(refresh == null ? account.refresh : refresh, account.name, account.uuid, System.currentTimeMillis()));
        return minecraft(access);
    }

    /** Renouvelle le jeton garde s'il vieillit : sans depot pendant trois mois, il expirerait. */
    public void renew() {
        Saved account = saved;
        if (account == null || System.currentTimeMillis() - account.renewedAt < 7 * DAY_MS) {
            return;
        }
        try {
            token = null;
            fresh();
        } catch (IOException e) {
            logger.warn("Compte {} non renouvelé : {}", account.name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- depots

    @Override
    public int slots() {
        return 1;
    }

    @Override
    public long waitMs(PiecePriority priority) {
        return saved == null ? -1 : pace.waitMs(System.currentTimeMillis(), priority == PiecePriority.LATER);
    }

    @Override
    public String upload(byte[] png, PiecePriority priority) throws IOException, InterruptedException {
        uploading.lockInterruptibly();
        try {
            long wait = pace.waitMs(System.currentTimeMillis(), priority == PiecePriority.LATER);
            if (wait > 60_000) {
                throw new Unavailable("compte en pause");
            }
            if (wait > 0) {
                Thread.sleep(wait);
            }
            for (int attempt = 1; ; attempt++) {
                String minecraft;
                try {
                    minecraft = fresh();
                } catch (IOException e) {
                    // Deja en pause si Mojang l'a demande ; sinon on ne redemande pas sans cesse
                    if (saved != null && pace.pausedFor(System.currentTimeMillis()) == 0) {
                        pace.refused(System.currentTimeMillis(), 0);
                    }
                    throw e instanceof Unavailable ? e : new Unavailable(e.getMessage());
                }
                pace.sent(System.currentTimeMillis());
                HttpResponse<String> response;
                try {
                    response = http.send(skin(png, minecraft), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    if (attempt >= TRIES) {
                        pace.refused(System.currentTimeMillis(), 0);
                        throw new Unavailable("Mojang ne répond pas (" + e.getMessage() + ")");
                    }
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                int status = response.statusCode();
                if (status == 200) {
                    pace.accepted();
                    String hash = active(json(response.body()));
                    if (hash == null) {
                        throw new IOException("Mojang n'a pas rendu la texture");
                    }
                    return hash;
                }
                if (status == 429) {
                    throw new Unavailable("trop de dépôts pour Mojang, " + paused(new Answer(status, json(response.body()), retryAfter(response))) + " de pause");
                }
                if (status == 400) {
                    throw new IOException("image refusée par Mojang (" + reason(json(response.body())) + ")");
                }
                // Jeton perime ou service en panne : un nouvel essai, avec un jeton neuf
                token = null;
                if (attempt >= TRIES) {
                    throw new Unavailable("Mojang refuse le dépôt (" + status + " " + reason(json(response.body())) + "), "
                        + paused(new Answer(status, json(response.body()), retryAfter(response))) + " de pause");
                }
                Thread.sleep(2000L * attempt);
            }
        } finally {
            uploading.unlock();
        }
    }

    private String paused(Answer answer) {
        long pause = pace.refused(System.currentTimeMillis(), answer.retryAfterMs());
        return (pause + 59_999) / 60_000 + " min";
    }

    private HttpRequest skin(byte[] png, String minecraft) throws IOException {
        String boundary = "----SHSkins" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\nclassic\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request(MINECRAFT + "/minecraft/profile/skins").header("Authorization", "Bearer " + minecraft)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    /** Empreinte du skin que le compte porte maintenant. */
    static String active(JsonObject profile) {
        if (profile == null || !profile.has("skins")) {
            return null;
        }
        for (JsonElement element : profile.getAsJsonArray("skins")) {
            JsonObject skin = element.getAsJsonObject();
            if ("ACTIVE".equals(text(skin, "state")) && text(skin, "url") != null) {
                String url = text(skin, "url");
                String hash = url.substring(url.lastIndexOf('/') + 1);
                return hash.matches("[0-9a-f]{20,}") ? hash : null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- fichier du compte

    private Saved read() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject object = json(Files.readString(file));
            return object == null || text(object, "refresh") == null ? null
                : new Saved(text(object, "refresh"), text(object, "name"), text(object, "uuid"), number(object, "renewed", 0));
        } catch (IOException e) {
            logger.warn("{} illisible ({})", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Ecrit dans un fichier prive, puis le met en place d'un coup. */
    private synchronized void store(Saved account) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("refresh", account.refresh);
        object.addProperty("name", account.name);
        object.addProperty("uuid", account.uuid);
        object.addProperty("renewed", account.renewedAt);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // Pas de droits POSIX (Windows)
            Files.createFile(temp);
        }
        Files.writeString(temp, object.toString());
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        saved = account;
    }

    // ---------------------------------------------------------------- HTTP

    /** @param retryAfterMs pause demandee par le service, 0 s'il n'en donne pas */
    private record Answer(int status, JsonObject body, long retryAfterMs) {
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT).header("Accept", "application/json");
    }

    private JsonObject form(String url, Map<String, String> fields) throws IOException, InterruptedException {
        StringJoiner body = new StringJoiner("&");
        fields.forEach((name, value) -> body.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpResponse<String> response = http.send(request(url).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject answer = json(response.body());
        if (answer == null) {
            throw new IOException("réponse illisible de Microsoft (" + response.statusCode() + ")");
        }
        return answer;
    }

    private Answer post(String url, JsonObject body, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(url).header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString());
        JsonObject answer = json(response.body());
        return new Answer(response.statusCode(), answer == null ? new JsonObject() : answer, retryAfter(response));
    }

    private static long retryAfter(HttpResponse<?> response) {
        try {
            return response.headers().firstValue("Retry-After").map(value -> Long.parseLong(value.trim()) * 1000).orElse(0L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject token(String relyingParty, JsonObject properties) {
        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", relyingParty);
        body.addProperty("TokenType", "JWT");
        return body;
    }

    private static String xboxError(long code) {
        return switch (Long.toString(code)) {
            case "2148916227" -> "compte banni de Xbox";
            case "2148916233" -> "ce compte Microsoft n'a pas de profil Xbox : en créer un sur minecraft.net";
            case "2148916235" -> "Xbox Live indisponible dans le pays du compte";
            case "2148916236", "2148916237" -> "le compte doit confirmer sa majorité sur Xbox";
            case "2148916238" -> "compte d'enfant : un adulte doit l'ajouter a sa famille Microsoft";
            default -> "Xbox refuse le compte (" + code + ")";
        };
    }

    private static String required(JsonObject object, String name, String from) throws IOException {
        String value = text(object, name);
        if (value == null) {
            throw new IOException("réponse inattendue de " + from + " (" + reason(object) + ")");
        }
        return value;
    }

    /** Ce que dit le service de son refus, sans aucun jeton. */
    private static String reason(JsonObject object) {
        for (String name : new String[]{"reason", "error", "errorMessage", "Message", "errorType"}) {
            String value = text(object, name);
            if (value != null && !value.isBlank()) {
                return value.length() > 120 ? value.substring(0, 120) : value;
            }
        }
        return "sans raison";
    }

    private static JsonObject json(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long number(JsonObject object, String name, long fallback) {
        try {
            String value = text(object, name);
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
