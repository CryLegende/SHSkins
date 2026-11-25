package org.Cry_Legende.sHSkins.paper;

import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.api.PiecePriority;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.model.SkinPart;
import org.Cry_Legende.sHSkins.service.MineSkinQueue;
import org.Cry_Legende.sHSkins.service.MojangAccount;
import org.Cry_Legende.sHSkins.service.SkinCutter;
import org.Cry_Legende.sHSkins.service.TextureHost;
import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Morceaux des skins ({@link SkinPart}), pour les statues articulees d'autres plugins. Un skin n'est decoupe et depose qu'a la
 * demande, une seule fois pour tout le reseau : les empreintes sont dans {@code shskins_pieces} et en memoire.
 * <p>
 * Les depots se font par le compte Mojang s'il y en a un, sinon par MineSkin.
 */
public final class PieceService {

    private static final long RETRY_MS = 10 * 60_000;
    private static final long OVERLAP_MS = 10_000;

    private record Task(String skin, SkinPart part) {
    }

    /** Un morceau et le service qui le depose. */
    private record Step(Task task, byte[] png, TextureHost host, PiecePriority priority) {
    }

    private static final class Job {
        final String skin;
        final String texture;
        final boolean slim;
        final CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        final long asked = System.nanoTime();
        PiecePriority priority;
        Map<SkinPart, byte[]> pieces;
        /** Pour chaque morceau, le premier qui a la meme image (bras gauche et droit identiques) : un seul depot pour les deux. */
        final Map<SkinPart, SkinPart> same = new EnumMap<>(SkinPart.class);

        Job(String skin, String texture, boolean slim, PiecePriority priority) {
            this.skin = skin;
            this.texture = texture;
            this.slim = slim;
            this.priority = priority;
        }
    }

    private final SkinDatabase database;
    private final Logger logger;
    private final MojangAccount account;
    private final MineSkinQueue mineSkin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService uploads = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread worker;
    /** Empreintes de chaque skin, par morceau ; une entree n'est jamais modifiee, elle est remplacee. */
    private final Map<String, Map<String, String>> known = new ConcurrentHashMap<>();
    private final Map<String, Long> refused = new ConcurrentHashMap<>();
    /** Verrou de ce qui suit. */
    private final Object lock = new Object();
    private final Map<String, Job> jobs = new HashMap<>();
    private final Set<Task> flying = new HashSet<>();
    private final Map<TextureHost, Integer> busy = new HashMap<>();
    private volatile PaperSettings.Pieces settings;
    private volatile boolean closed;
    private long readUntil;
    private boolean failing;

    PieceService(SkinDatabase database, PaperSettings.Pieces settings, Path folder, Logger logger) {
        this.database = database;
        this.settings = settings;
        this.logger = logger;
        account = new MojangAccount(folder.resolve("compte-mojang.json"), settings.application(), settings.perMinute(), logger);
        mineSkin = new MineSkinQueue(settings.key(), settings.reserve(), settings.reserveNormal());
        worker = new Thread(this::work, "SHSkins morceaux");
        worker.setDaemon(true);
        worker.start();
    }

    void configure(PaperSettings.Pieces settings) {
        this.settings = settings;
        account.configure(settings.application(), settings.perMinute());
        mineSkin.configure(settings.key(), settings.reserve(), settings.reserveNormal());
        wake();
    }

    void close() {
        closed = true;
        worker.interrupt();
        uploads.shutdownNow();
    }

    MojangAccount account() {
        return account;
    }

    void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Relit les morceaux faits depuis la derniere lecture (tous au premier appel). Bloquant : hors du thread principal. */
    synchronized void refresh() {
        long started = System.currentTimeMillis();
        try {
            for (SkinDatabase.StoredPiece row : database.piecesMadeSince(readUntil == 0 ? 0 : readUntil - OVERLAP_MS)) {
                add(row.skin(), row.part(), row.texture());
            }
            readUntil = started;
            failing = false;
        } catch (SQLException e) {
            if (!failing) {
                failing = true;
                logger.warn("Lecture des morceaux de skins impossible : {}", e.getMessage());
            }
        }
        if (settings.enabled()) {
            account.renew();
        }
    }

    /** Tous les morceaux d'un skin, vide s'il en manque. */
    public Optional<Map<String, String>> find(SkinData skin) {
        String key = key(skin);
        Map<String, String> pieces = key == null ? null : known.get(key);
        return complete(pieces) ? Optional.of(pieces) : Optional.empty();
    }

    /**
     * Fait les morceaux d'un skin. La reponse arrive tout de suite s'ils sont deja faits, sinon sur un autre thread ; elle echoue
     * si le skin est illisible ou si les morceaux sont desactives sur ce serveur.
     */
    public CompletableFuture<Map<String, String>> make(SkinData skin, PiecePriority priority) {
        Optional<Map<String, String>> ready = find(skin);
        if (ready.isPresent()) {
            return CompletableFuture.completedFuture(ready.get());
        }
        String key = key(skin);
        if (!settings.enabled() || key == null || refused.getOrDefault(key, 0L) > System.currentTimeMillis()) {
            return CompletableFuture.failedFuture(new IllegalStateException("morceaux indisponibles"));
        }
        synchronized (lock) {
            Job job = jobs.computeIfAbsent(key, k -> new Job(k, skin.skinHash().orElseThrow(), skin.slim(), priority));
            if (priority.compareTo(job.priority) < 0) {
                job.priority = priority;
            }
            lock.notifyAll();
            return job.future;
        }
    }

    /** Skins dont les morceaux sont tous faits. */
    int ready() {
        return (int) known.values().stream().filter(PieceService::complete).count();
    }

    int waiting() {
        synchronized (lock) {
            return jobs.size();
        }
    }

    int mineSkinLeft() {
        return mineSkin.left();
    }

    private static boolean complete(Map<String, String> pieces) {
        return pieces != null && pieces.size() >= SkinPart.values().length;
    }

    private static String key(SkinData skin) {
        return skin.skinHash().map(hash -> skin.slim() ? hash + "-slim" : hash).orElse(null);
    }

    private Map<String, String> add(String skin, String part, String texture) {
        return known.merge(skin, Map.of(part, texture), (current, next) -> {
            Map<String, String> merged = new HashMap<>(current);
            merged.putAll(next);
            return Map.copyOf(merged);
        });
    }

    // ---------------------------------------------------------------- decoupe et depots

    private void work() {
        try {
            while (!closed) {
                Job toCut = null;
                Step step = null;
                synchronized (lock) {
                    long wait = 30_000;
                    for (Job job : order()) {
                        if (job.pieces == null) {
                            toCut = job;
                            break;
                        }
                        Task task = missing(job);
                        if (task == null) {
                            continue;
                        }
                        Choice choice = choose(job.priority);
                        if (choice.host != null) {
                            step = new Step(task, job.pieces.get(task.part), choice.host, job.priority);
                            flying.add(task);
                            busy.merge(choice.host, 1, Integer::sum);
                            break;
                        }
                        wait = Math.min(wait, choice.pause);
                    }
                    if (toCut == null && step == null) {
                        lock.wait(Math.max(50, wait));
                        continue;
                    }
                }
                if (toCut != null) {
                    cut(toCut);
                } else {
                    Step next = step;
                    uploads.execute(() -> upload(next));
                }
            }
        } catch (InterruptedException e) {
            // Arret du plugin
        }
    }

    /** @param pause millisecondes avant de redemander, si aucun service n'est libre */
    private record Choice(TextureHost host, long pause) {
    }

    /** Le compte Mojang d'abord ; MineSkin s'il n'y en a pas, ou s'il est en pause. */
    private Choice choose(PiecePriority priority) {
        long wait = 30_000;
        for (TextureHost host : List.of(account, mineSkin)) {
            long hostWait = host.waitMs(priority);
            if (hostWait < 0) {
                continue;
            }
            if (hostWait == 0 && busy.getOrDefault(host, 0) < host.slots()) {
                return new Choice(host, 0);
            }
            // Occupe : la fin de son depot reveille la file
            wait = Math.min(wait, hostWait == 0 ? 1000 : Math.max(50, hostWait));
            // Le compte sera libre dans un instant : MineSkin ne sert que s'il est vraiment arrete
            if (host == account && hostWait < 60_000) {
                break;
            }
        }
        return new Choice(null, wait);
    }

    /** Les plus urgents d'abord, puis les plus anciens. */
    private List<Job> order() {
        List<Job> order = new ArrayList<>(jobs.values());
        order.sort(Comparator.comparing((Job job) -> job.priority).thenComparingLong(job -> job.asked));
        return order;
    }

    private Task missing(Job job) {
        Map<String, String> done = known.getOrDefault(job.skin, Map.of());
        for (SkinPart part : SkinPart.values()) {
            Task task = new Task(job.skin, part);
            if (job.same.get(part) == part && !done.containsKey(part.key()) && !flying.contains(task)) {
                return task;
            }
        }
        return null;
    }

    private void cut(Job job) throws InterruptedException {
        try {
            Map<SkinPart, byte[]> pieces = SkinCutter.cut(download(job.texture), job.slim);
            synchronized (lock) {
                for (SkinPart part : SkinPart.values()) {
                    SkinPart first = part;
                    for (SkinPart other : SkinPart.values()) {
                        if (Arrays.equals(pieces.get(other), pieces.get(part))) {
                            first = other;
                            break;
                        }
                    }
                    job.same.put(part, first);
                }
                job.pieces = pieces;
            }
            // Un morceau deja fait sert aussi a ceux qui ont la meme image
            Map<String, String> done = known.getOrDefault(job.skin, Map.of());
            job.same.forEach((part, first) -> {
                String texture = done.get(first.key());
                if (texture != null && !done.containsKey(part.key())) {
                    save(job.skin, part, texture);
                }
            });
            finish(job.skin);
        } catch (IOException | RuntimeException e) {
            refuse(job.skin, e);
        }
    }

    /** L'image d'une texture de textures.minecraft.net (sans aucune limite). */
    private byte[] download(String hash) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", "SHSkins/" + SHSkins.VERSION).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("texture " + hash + " introuvable (" + response.statusCode() + ")");
        }
        return response.body();
    }

    private void upload(Step step) {
        Task task = step.task;
        try {
            String texture = step.host.upload(step.png, step.priority);
            for (SkinPart part : sharing(task)) {
                save(task.skin, part, texture);
            }
            finish(task.skin);
        } catch (TextureHost.Unavailable e) {
            // Le morceau repart dans la file, pour l'autre service ou pour plus tard
            if (!closed) {
                logger.warn("Dépôt de texture remis : {}", e.getMessage());
            }
        } catch (IOException | RuntimeException e) {
            refuse(task.skin, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lock) {
                flying.remove(task);
                busy.merge(step.host, -1, Integer::sum);
                lock.notifyAll();
            }
        }
    }

    private void save(String skin, SkinPart part, String texture) {
        add(skin, part.key(), texture);
        try {
            database.savePiece(skin, part.key(), texture);
        } catch (SQLException e) {
            logger.warn("Morceau de skin non enregistré, il sera refait au prochain démarrage : {}", e.getMessage());
        }
    }

    /** Repond a ceux qui attendent ce skin, si tous ses morceaux sont faits. */
    private void finish(String skin) {
        Map<String, String> pieces = known.get(skin);
        if (!complete(pieces)) {
            return;
        }
        Job job;
        synchronized (lock) {
            job = jobs.remove(skin);
        }
        if (job != null) {
            job.future.complete(pieces);
        }
    }

    /** Les morceaux qui ont la meme image que celui-ci, lui compris. */
    private List<SkinPart> sharing(Task task) {
        List<SkinPart> parts = new ArrayList<>(List.of(task.part));
        synchronized (lock) {
            Job job = jobs.get(task.skin);
            if (job != null) {
                job.same.forEach((part, first) -> {
                    if (first == task.part && part != first) {
                        parts.add(part);
                    }
                });
            }
        }
        return parts;
    }

    private void refuse(String skin, Exception e) {
        Job job;
        synchronized (lock) {
            job = jobs.remove(skin);
        }
        // Ses autres morceaux, partis en meme temps, echoueront pareil : une seule ligne
        if (job == null || closed) {
            return;
        }
        refused.put(skin, System.currentTimeMillis() + RETRY_MS);
        logger.warn("Skin {} non découpé : {}", skin, e.getMessage());
        job.future.completeExceptionally(e);
    }
}
