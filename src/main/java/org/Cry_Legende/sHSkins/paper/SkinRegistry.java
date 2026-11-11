package org.Cry_Legende.sHSkins.paper;

import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skins de tous les joueurs, gardes en memoire : toute la table est lue au demarrage puis seules les lignes
 * ecrites depuis la derniere lecture (connexions sur le proxy). Une recherche ne touche donc jamais la base et
 * repond aussi vite depuis le thread principal que depuis un thread de menu.
 */
public final class SkinRegistry {

    /**
     * Skin connu d'un joueur.
     *
     * @param name      pseudo, null si le skin vient d'un ancien fichier et que le joueur n'est pas revenu depuis
     * @param appliedAt date ou le proxy a applique ce skin, en millisecondes
     */
    public record Entry(UUID uuid, String name, SkinData skin, long appliedAt) {
    }

    /** Marge de relecture : une ligne ecrite juste avant la lecture precedente mais validee apres n'est pas perdue. */
    private static final long OVERLAP_MS = 10_000;

    private final SkinDatabase database;
    private final Logger logger;
    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private long readUntil;
    private volatile boolean loaded;
    private boolean failing;

    public SkinRegistry(SkinDatabase database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Lit les skins ecrits depuis la derniere lecture (tous au premier appel). Bloquant : hors du thread principal. */
    public synchronized void refresh() {
        long started = System.currentTimeMillis();
        try {
            List<SkinDatabase.StoredSkin> rows = database.skinsWrittenSince(loaded ? readUntil - OVERLAP_MS : 0);
            for (SkinDatabase.StoredSkin row : rows) {
                merge(new Entry(row.uuid(), row.name(), row.skin(), row.appliedAt()));
            }
            readUntil = started;
            if (!loaded) {
                loaded = true;
                logger.info("{} skins charges depuis MariaDB.", byUuid.size());
            }
            if (failing) {
                failing = false;
                logger.info("MariaDB repond de nouveau, skins a jour.");
            }
        } catch (SQLException e) {
            if (!failing) {
                failing = true;
                logger.warn("Lecture des skins impossible, nouvel essai a la prochaine relecture : {}", e.getMessage());
            }
        }
    }

    /** Skin porte par un joueur connecte a ce serveur : c'est le plus recent, la base suit a la relecture. */
    public void remember(UUID uuid, String name, SkinData skin) {
        merge(new Entry(uuid, name, skin, System.currentTimeMillis()));
    }

    public Optional<Entry> find(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(byUuid.get(uuid));
    }

    /**
     * Par pseudo, sans tenir compte des majuscules. Un skin repris d'un ancien fichier n'a pas encore de pseudo :
     * il est retrouve par l'UUID offline du pseudo, celui de tout le reseau.
     */
    public Optional<Entry> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        UUID uuid = byName.get(name.toLowerCase(Locale.ROOT));
        return find(uuid != null ? uuid : offlineUuid(name));
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int size() {
        return byUuid.size();
    }

    /** UUID d'un joueur sur le reseau SkyHand (mode offline) : md5 de « OfflinePlayer:pseudo ». */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void merge(Entry incoming) {
        Entry merged = byUuid.merge(incoming.uuid(), incoming, (current, next) -> {
            Entry newest = next.appliedAt() >= current.appliedAt() ? next : current;
            Entry other = newest == next ? current : next;
            // Un skin repris d'un ancien fichier n'a pas de pseudo : on garde celui deja connu
            return newest.name() != null || other.name() == null ? newest
                : new Entry(newest.uuid(), other.name(), newest.skin(), newest.appliedAt());
        });
        if (merged.name() != null) {
            byName.put(merged.name().toLowerCase(Locale.ROOT), merged.uuid());
        }
    }
}
