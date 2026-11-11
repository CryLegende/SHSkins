package org.Cry_Legende.sHSkins.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Skins et reponses de Mojang du proxy, dans MariaDB ({@link SkinDatabase}). Les lectures sont faites pendant la
 * connexion (deja hors du thread du proxy) ; les ecritures passent par un thread dedie pour ne jamais la retarder.
 * Une base injoignable n'empeche pas les connexions : le joueur recoit son skin du site ou de Mojang, seul le
 * repli sur le dernier skin enregistre manque.
 */
public class SkinStorage {

    private static final String LEGACY_FOLDER = "playerdata";
    private static final String IMPORTED_FOLDER = "playerdata-importe";

    private final Logger logger;
    private final SkinDatabase database;
    private final Gson gson = new GsonBuilder().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SHSkins-DB");
        thread.setDaemon(true);
        return thread;
    });

    public SkinStorage(SkinDatabase database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Dernier skin applique a ce joueur. */
    public Optional<SkinData> getSkin(UUID playerUUID) {
        try {
            return database.findSkin(playerUUID).map(SkinDatabase.StoredSkin::skin);
        } catch (SQLException e) {
            logger.warn("Lecture du skin de {} impossible : {}", playerUUID, e.getMessage());
            return Optional.empty();
        }
    }

    /** Enregistre le skin applique a la connexion d'un joueur. */
    public void saveSkin(UUID playerUUID, String playerName, SkinData skin) {
        long now = System.currentTimeMillis();
        writer.execute(() -> {
            try {
                database.saveSkin(playerUUID, playerName, skin, now);
            } catch (SQLException e) {
                logger.warn("Skin de {} non enregistre : {}", playerName, e.getMessage());
            }
        });
    }

    /** Derniere reponse de Mojang enregistree pour ce joueur, si elle existe. */
    public Optional<SkinDatabase.AccountEntry> getAccount(UUID playerUUID) {
        try {
            return database.findAccount(playerUUID);
        } catch (SQLException e) {
            logger.warn("Lecture du compte Mojang de {} impossible : {}", playerUUID, e.getMessage());
            return Optional.empty();
        }
    }

    /** Enregistre la reponse de Mojang (textures du compte ou absence de compte). */
    public void saveAccount(UUID playerUUID, Optional<SkinData> account) {
        long now = System.currentTimeMillis();
        SkinDatabase.AccountEntry entry = account
            .map(skin -> new SkinDatabase.AccountEntry(true, skin.value(), skin.signature(), now))
            .orElseGet(() -> new SkinDatabase.AccountEntry(false, null, null, now));
        writer.execute(() -> {
            try {
                database.saveAccount(playerUUID, entry);
            } catch (SQLException e) {
                logger.warn("Compte Mojang de {} non enregistre : {}", playerUUID, e.getMessage());
            }
        });
    }

    /**
     * Reprend dans la base les fichiers des versions 2.x ({@code playerdata/<uuid>.json} et
     * {@code <uuid>.account.json}), sans jamais ecraser une donnee plus recente, puis renomme le dossier en
     * {@code playerdata-importe}. Si la base ne repond pas, le dossier reste et l'import se refait au demarrage
     * suivant.
     */
    public void importLegacyFiles(Path dataFolder) {
        Path folder = dataFolder.resolve(LEGACY_FOLDER);
        if (!Files.isDirectory(folder)) {
            return;
        }
        writer.execute(() -> {
            int skins = 0;
            int accounts = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : files) {
                    String fileName = file.getFileName().toString();
                    boolean account = fileName.endsWith(".account.json");
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(fileName.substring(0, fileName.indexOf('.')));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (account) {
                        Optional<SkinDatabase.AccountEntry> entry = readJson(file, SkinDatabase.AccountEntry.class);
                        if (entry.isPresent()) {
                            database.saveAccount(uuid, entry.get());
                            accounts++;
                        }
                    } else {
                        Optional<SkinData> skin = readJson(file, SkinData.class).filter(s -> s.value() != null);
                        if (skin.isPresent()) {
                            database.saveSkin(uuid, null, skin.get(), modified);
                            skins++;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Import des anciens fichiers de skins interrompu, il reprendra au prochain demarrage : {}",
                    e.getMessage());
                return;
            }
            try {
                Files.move(folder, dataFolder.resolve(IMPORTED_FOLDER));
            } catch (Exception e) {
                logger.warn("Dossier {} importe mais impossible a renommer : {}", LEGACY_FOLDER, e.getMessage());
            }
            logger.info("Anciens fichiers importes dans MariaDB : {} skins, {} comptes Mojang.", skins, accounts);
        });
    }

    private <T> Optional<T> readJson(Path file, Class<T> type) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(gson.fromJson(reader, type));
        } catch (Exception e) {
            logger.warn("Fichier illisible ignore : {}", file.getFileName());
            return Optional.empty();
        }
    }

    /** Termine les ecritures en attente (arret du proxy). */
    public void shutdown() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        database.close();
    }
}
