package org.Cry_Legende.sHSkins;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.Cry_Legende.sHSkins.cache.SkinCache;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.listener.SkinListener;
import org.Cry_Legende.sHSkins.service.SkinService;
import org.Cry_Legende.sHSkins.storage.SkinStorage;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "shskins",
    name = "SHSkins",
    version = SHSkins.VERSION,
    description = "Applique les skins SkyHand a la connexion",
    authors = {"Cry_Legende"}
)
public final class SHSkins {

    public static final String VERSION = "2.0-velocity";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataFolder;

    private PluginConfig pluginConfig;
    private SkinCache skinCache;
    private SkinStorage skinStorage;
    private SkinService skinService;

    @Inject
    public SHSkins(ProxyServer proxy, Logger logger, @DataDirectory Path dataFolder) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("Demarrage de SHSkins...");

        this.pluginConfig = new PluginConfig(dataFolder, logger);
        this.skinCache = new SkinCache(pluginConfig);
        this.skinStorage = new SkinStorage(dataFolder, logger);
        this.skinService = new SkinService(logger, skinCache, skinStorage, pluginConfig);

        proxy.getEventManager().register(this, new SkinListener(logger, skinService));

        logger.info("SHSkins a ete active avec succes !");
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }

    public SkinStorage getSkinStorage() {
        return skinStorage;
    }

    public SkinService getSkinService() {
        return skinService;
    }
}
