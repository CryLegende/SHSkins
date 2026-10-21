package org.Cry_Legende.sHSkins.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SkinStorage {

    private final Logger logger;
    private final Path playerDataFolder;
    private final Gson gson = new GsonBuilder().create();

    public SkinStorage(Path dataFolder, Logger logger) {
        this.logger = logger;
        this.playerDataFolder = dataFolder.resolve("playerdata");
        try {
            Files.createDirectories(playerDataFolder);
        } catch (Exception e) {
            logger.error("Impossible de creer le dossier playerdata", e);
        }
    }

    /**
     * Sauvegarde le skin d'un joueur dans son fichier personnel, de maniere asynchrone.
     */
    public void saveSkin(UUID playerUUID, SkinData skinData) {
        Path playerFile = playerDataFolder.resolve(playerUUID + ".json");
        CompletableFuture.runAsync(() -> {
            try (Writer writer = Files.newBufferedWriter(playerFile, StandardCharsets.UTF_8)) {
                gson.toJson(skinData, writer);
            } catch (Exception e) {
                logger.error("Impossible de sauvegarder le skin pour " + playerUUID, e);
            }
        });
    }

    /**
     * Recupere le skin d'un joueur depuis son fichier personnel.
     */
    public Optional<SkinData> getSkin(UUID playerUUID) {
        Path playerFile = playerDataFolder.resolve(playerUUID + ".json");
        if (!Files.exists(playerFile)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(playerFile, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(gson.fromJson(reader, SkinData.class));
        } catch (Exception e) {
            logger.warn("Impossible de lire le fichier de skin pour " + playerUUID, e);
            return Optional.empty();
        }
    }
}
