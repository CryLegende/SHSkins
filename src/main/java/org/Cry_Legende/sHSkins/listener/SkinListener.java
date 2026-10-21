package org.Cry_Legende.sHSkins.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.service.SkinService;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SkinListener {

    private static final String TEXTURES = "textures";

    private final Logger logger;
    private final SkinService skinService;

    public SkinListener(Logger logger, SkinService skinService) {
        this.logger = logger;
        this.skinService = skinService;
    }

    /**
     * Equivalent Velocity du LoginEvent(LOWEST) + reflection sur InitialHandler.loginProfile
     * de la version BungeeCord. Velocity expose directement le GameProfile en ecriture,
     * donc plus aucune reflection n'est necessaire.
     * <p>
     * EventTask.resumeWhenComplete remplace le couple registerIntent/completeIntent.
     */
    @Subscribe(order = PostOrder.LATE)
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        GameProfile originalProfile = event.getGameProfile();
        String playerName = event.getUsername();
        UUID playerUUID = originalProfile.getId();

        Optional<SkinData> currentSkin = getExistingSkin(originalProfile);

        CompletableFuture<Void> work = skinService.getSkinData(playerName, playerUUID)
            .thenAccept(targetSkinOptional -> {
                if (targetSkinOptional.isEmpty()) {
                    return;
                }
                SkinData targetSkin = targetSkinOptional.get();

                if (currentSkin.isPresent() && currentSkin.get().equals(targetSkin)) {
                    logger.info("Le joueur {} a deja le bon skin.", playerName);
                    return;
                }

                event.setGameProfile(withTextures(event.getGameProfile(), targetSkin));
                logger.info("Profil de connexion defini avec succes pour {}.", playerName);
            })
            .exceptionally(throwable -> {
                logger.error("Erreur asynchrone lors du processus de skin pour " + playerName, throwable);
                return null;
            });

        return EventTask.resumeWhenComplete(work);
    }

    /**
     * Remplace la propriete "textures" en conservant les autres proprietes du profil.
     */
    private static GameProfile withTextures(GameProfile profile, SkinData skin) {
        List<GameProfile.Property> properties = new ArrayList<>();
        for (GameProfile.Property property : profile.getProperties()) {
            if (!TEXTURES.equals(property.getName())) {
                properties.add(property);
            }
        }
        properties.add(new GameProfile.Property(TEXTURES, skin.value(), skin.signature()));
        return profile.withProperties(properties);
    }

    private Optional<SkinData> getExistingSkin(GameProfile profile) {
        for (GameProfile.Property property : profile.getProperties()) {
            if (TEXTURES.equals(property.getName())) {
                return Optional.of(new SkinData(property.getValue(), property.getSignature()));
            }
        }
        return Optional.empty();
    }
}
