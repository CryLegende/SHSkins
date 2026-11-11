package org.Cry_Legende.sHSkins;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.Cry_Legende.sHSkins.cache.SkinCache;
import org.Cry_Legende.sHSkins.command.AdminCommand;
import org.Cry_Legende.sHSkins.command.SkinCommand;
import org.Cry_Legende.sHSkins.config.ConfigException;
import org.Cry_Legende.sHSkins.config.Messages;
import org.Cry_Legende.sHSkins.config.PluginConfig;
import org.Cry_Legende.sHSkins.listener.SkinListener;
import org.Cry_Legende.sHSkins.service.CapeCatalog;
import org.Cry_Legende.sHSkins.service.SkinService;
import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.Cry_Legende.sHSkins.storage.SkinStorage;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Plugin(
    id = "shskins",
    name = "SHSkins",
    version = SHSkins.VERSION,
    description = "Skins SkyHand : applique le skin a la connexion et le garde pour les serveurs",
    authors = {"Cry_Legende"}
)
public final class SHSkins {

    public static final String VERSION = "3.0";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataFolder;

    private PluginConfig pluginConfig;
    private Messages messages;
    private SkinCache skinCache;
    private SkinStorage skinStorage;
    private CapeCatalog capeCatalog;
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
        // messages.yml est cree au premier demarrage, avec le texte de /skin de l'ancien config.yml
        this.messages = new Messages(dataFolder, logger, pluginConfig.getLegacySkinCommandMessage());
        this.skinCache = new SkinCache(pluginConfig);
        SkinDatabase database = new SkinDatabase(pluginConfig.getDatabase(), logger);
        if (database.check()) {
            logger.info("Connecte a MariaDB : les skins appliques y sont enregistres.");
        }
        this.skinStorage = new SkinStorage(database, logger);
        // Versions 2.x : un fichier par joueur dans playerdata/, repris une fois dans la base
        skinStorage.importLegacyFiles(dataFolder);
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.capeCatalog = new CapeCatalog(logger, pluginConfig, httpClient, SkinService.userAgent());
        this.skinService = new SkinService(logger, skinCache, skinStorage, pluginConfig, capeCatalog, httpClient);

        // Capes autorisees par l'offre MineSkin : relues toutes les heures, un abonnement s'applique sans redemarrage
        proxy.getScheduler().buildTask(this, capeCatalog::refresh)
            .repeat(1, TimeUnit.HOURS)
            .schedule();

        proxy.getEventManager().register(this, new SkinListener(logger, skinService, skinStorage));
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("skin").aliases("skins").plugin(this).build(),
            new SkinCommand(messages));
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("shskins").plugin(this).build(),
            new AdminCommand(this, messages));

        logger.info("SHSkins a ete active avec succes !");
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont lus et valides avant que quoi que ce soit change : en cas
     * d'erreur la configuration en cours reste en place. Reconstruit le cache memoire des skins avec les reglages
     * lus (les textures se redemandent a la connexion suivante) et relance la lecture des capes MineSkin pour une
     * cle changee. Les skins enregistres en base ne sont pas touches ; changer base-de-donnees demande un
     * redemarrage du proxy.
     */
    public void reload() throws ConfigException {
        Runnable applyConfig = pluginConfig.prepareReload();
        Runnable applyMessages = messages.prepareReload();
        applyConfig.run();
        applyMessages.run();
        skinCache.configure(pluginConfig);
        capeCatalog.refresh();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (skinStorage != null) {
            skinStorage.shutdown();
        }
    }

    public Logger getLogger() {
        return logger;
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
