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
import java.nio.file.StandardCopyOption;
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
        // Fichier temporaire puis remplacement : un arret pendant l'ecriture (ou deux connexions simultanees du meme
        // joueur) ne laisse pas un fichier de skin tronque, qui serait ensuite ignore par getSkin
        Path temp = playerDataFolder.resolve(playerUUID + "." + UUID.randomUUID() + ".tmp");
        CompletableFuture.runAsync(() -> {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(skinData, writer);
            } catch (Exception e) {
                logger.error("Impossible de sauvegarder le skin pour " + playerUUID, e);
                return;
            }
            try {
                Files.move(temp, playerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    /**
     * Derniere reponse de Mojang pour le compte du meme pseudo : ses textures brutes, ou l'absence de compte.
     * Fichier a part de {@code <uuid>.json} : il ne contient jamais un skin du site ni une cape posee par MineSkin.
     *
     * @param found     un compte Mojang existe pour ce pseudo
     * @param checkedAt date de la reponse, en millisecondes
     */
    public record AccountEntry(boolean found, String value, String signature, long checkedAt) {

        public Optional<SkinData> skin() {
            return found && value != null ? Optional.of(new SkinData(value, signature)) : Optional.empty();
        }
    }

    /**
     * Sauvegarde la reponse de Mojang (textures du compte ou absence de compte), de maniere asynchrone et atomique.
     */
    public void saveAccount(UUID playerUUID, Optional<SkinData> account) {
        long now = System.currentTimeMillis();
        AccountEntry entry = account
            .map(skin -> new AccountEntry(true, skin.value(), skin.signature(), now))
            .orElseGet(() -> new AccountEntry(false, null, null, now));
        Path accountFile = playerDataFolder.resolve(playerUUID + ".account.json");
        Path temp = playerDataFolder.resolve(playerUUID + "." + UUID.randomUUID() + ".tmp");
        CompletableFuture.runAsync(() -> {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(entry, writer);
            } catch (Exception e) {
                logger.error("Impossible de sauvegarder le compte Mojang pour " + playerUUID, e);
                deleteQuietly(temp);
                return;
            }
            try {
                Files.move(temp, accountFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                logger.error("Impossible de sauvegarder le compte Mojang pour " + playerUUID, e);
                deleteQuietly(temp);
            }
        });
    }

    /** Un fichier temporaire d'une ecriture ratee ne doit pas rester dans playerdata. */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // Rien de plus a faire : le fichier final n'a pas ete touche
        }
    }

    /**
     * Derniere reponse de Mojang enregistree pour ce joueur, si elle existe.
     */
    public Optional<AccountEntry> getAccount(UUID playerUUID) {
        Path accountFile = playerDataFolder.resolve(playerUUID + ".account.json");
        if (!Files.exists(accountFile)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(accountFile, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(gson.fromJson(reader, AccountEntry.class));
        } catch (Exception e) {
            logger.warn("Impossible de lire le compte Mojang enregistre pour " + playerUUID, e);
            return Optional.empty();
        }
    }
}
