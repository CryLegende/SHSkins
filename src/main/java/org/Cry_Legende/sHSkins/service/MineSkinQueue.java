package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.api.PiecePriority;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Depot de textures par la file d'attente de MineSkin : plusieurs a la fois.
 * <p>
 * Sans offre payante, MineSkin compte les depots par adresse (20 par minute, 100 par heure), toutes cles confondues : ceux des
 * skins du site, faits par le proxy, sont sur le meme compteur. Bloquant : hors du thread principal.
 */
public final class MineSkinQueue implements TextureHost {

    private static final String API = "https://api.mineskin.org/v2/queue";
    private static final String USER_AGENT = "SHSkins/" + SHSkins.VERSION;
    private static final int HOUR = 100;
    private static final int MINUTE = 20;
    private static final int TRIES = 6;
    /** Limite d'une file gratuite. */
    private static final int SLOTS = 8;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String key;
    private volatile int reserve;
    private volatile int reserveNormal;
    private int hour = HOUR;
    private int minute = MINUTE;
    private long hourReset;
    private long minuteReset;
    private long turn;

    public MineSkinQueue(String key, int reserve, int reserveNormal) {
        configure(key, reserve, reserveNormal);
    }

    /**
     * @param reserve       depots de l'heure jamais pris
     * @param reserveNormal la meme chose pour ce qui n'est pas urgent
     */
    public void configure(String key, int reserve, int reserveNormal) {
        this.key = key;
        this.reserve = reserve;
        this.reserveNormal = reserveNormal;
    }

    /** Depots encore permis cette heure. */
    public synchronized int left() {
        renew(System.currentTimeMillis());
        return Math.max(0, hour - reserve);
    }

    @Override
    public int slots() {
        return SLOTS;
    }

    @Override
    public synchronized long waitMs(PiecePriority priority) {
        if (priority == PiecePriority.LATER || key == null || key.isBlank()) {
            return -1;
        }
        long now = System.currentTimeMillis();
        renew(now);
        if (hour <= (priority == PiecePriority.URGENT ? reserve : reserveNormal)) {
            return Math.max(1000, hourReset - now);
        }
        if (minute <= 0) {
            return Math.max(200, minuteReset - now);
        }
        return Math.max(0, turn - now);
    }

    @Override
    public String upload(byte[] png, PiecePriority priority) throws IOException, InterruptedException {
        String last = "aucune réponse";
        for (int attempt = 1; attempt <= TRIES; attempt++) {
            take();
            JsonObject body;
            int status;
            try {
                HttpResponse<String> response = http.send(post(png), HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                body = json(response.body());
            } catch (IOException e) {
                last = String.valueOf(e.getMessage());
                Thread.sleep(1500L * attempt);
                continue;
            }
            if (status == 401 || status == 403) {
                throw new IOException("clé MineSkin refusée (" + status + ")");
            }
            learn(body);
            if (status == 429) {
                throw new Unavailable("MineSkin au bout de son quota");
            }
            String hash = hash(body);
            JsonElement job = path(body, "job", "id");
            if (hash == null && job != null && job.isJsonPrimitive()) {
                hash = follow(job.getAsString());
            }
            if (hash != null) {
                return hash;
            }
            last = status + " " + (body == null ? "" : String.valueOf(body.get("errors")));
            Thread.sleep(700L * attempt);
        }
        throw new IOException("MineSkin refuse l'image (" + last + ")");
    }

    /** Attend qu'un depot accepte soit fini ; null s'il a echoue. */
    private String follow(String job) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Thread.sleep(600);
            try {
                JsonObject body = json(http.send(request(API + "/" + job).build(), HttpResponse.BodyHandlers.ofString()).body());
                JsonElement status = path(body, "job", "status");
                if (status != null && status.getAsString().equals("failed")) {
                    return null;
                }
                if (hash(body) != null) {
                    return hash(body);
                }
            } catch (IOException e) {
                // Une reponse perdue : la suivante dira ou en est le depot
            }
        }
        return null;
    }

    /** Compte un depot, une fois venu son tour dans la minute. */
    private void take() throws InterruptedException {
        while (true) {
            long wait = 500;
            synchronized (this) {
                long now = System.currentTimeMillis();
                renew(now);
                if (minute > 0) {
                    if (now >= turn) {
                        hour--;
                        minute--;
                        turn = now + 150;
                        return;
                    }
                    wait = turn - now;
                }
            }
            Thread.sleep(Math.max(20, wait));
        }
    }

    private void renew(long now) {
        if (now >= hourReset) {
            hour = HOUR;
        }
        if (now >= minuteReset) {
            minute = MINUTE;
        }
    }

    private synchronized void learn(JsonObject body) {
        long now = System.currentTimeMillis();
        turn = Math.max(turn, now + Math.min(5000, number(body, "rateLimit", "next", "relative")));
        if (path(body, "rateLimit", "limit", "hour", "remaining") != null) {
            long reset = number(body, "rateLimit", "limit", "hour", "reset") * 1000 + 500;
            int remaining = (int) number(body, "rateLimit", "limit", "hour", "remaining");
            hour = reset > hourReset + 5000 ? remaining : Math.min(hour, remaining);
            hourReset = reset;
        }
        if (path(body, "rateLimit", "limit", "minute", "remaining") != null) {
            long reset = number(body, "rateLimit", "limit", "minute", "reset") * 1000 + 500;
            int remaining = (int) number(body, "rateLimit", "limit", "minute", "remaining");
            minute = reset > minuteReset + 5000 ? remaining : Math.min(minute, remaining);
            minuteReset = reset;
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT).header("Accept", "application/json");
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        return request;
    }

    private HttpRequest post(byte[] png) throws IOException {
        String boundary = "----SHSkins" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "variant", "classic");
        field(body, boundary, "visibility", "unlisted");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request(API).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject json(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonElement path(JsonObject object, String... keys) {
        JsonElement at = object;
        for (String name : keys) {
            if (at == null || !at.isJsonObject()) {
                return null;
            }
            at = at.getAsJsonObject().get(name);
        }
        return at;
    }

    private static long number(JsonObject object, String... keys) {
        JsonElement value = path(object, keys);
        try {
            return value == null || !value.isJsonPrimitive() ? 0 : value.getAsLong();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hash(JsonObject body) {
        JsonElement hash = path(body, "skin", "texture", "hash", "skin");
        if (hash != null && hash.isJsonPrimitive()) {
            return hash.getAsString();
        }
        JsonElement url = path(body, "skin", "texture", "url", "skin");
        if (url == null || !url.isJsonPrimitive()) {
            return null;
        }
        String text = url.getAsString();
        return text.substring(text.lastIndexOf('/') + 1);
    }
}
