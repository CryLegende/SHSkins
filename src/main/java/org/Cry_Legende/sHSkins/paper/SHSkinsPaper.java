package org.Cry_Legende.sHSkins.paper;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.Cry_Legende.sHSkins.config.ConfigException;
import org.Cry_Legende.sHSkins.config.Messages;
import org.Cry_Legende.sHSkins.model.SkinData;
import org.Cry_Legende.sHSkins.storage.SkinDatabase;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * SHSkins sur un serveur Paper : garde en memoire le skin de chaque joueur enregistre par le proxy, et le donne aux
 * autres plugins ({@link org.Cry_Legende.sHSkins.api.SHSkinsAPI}) et aux menus (placeholders %shskins_...%).
 */
public final class SHSkinsPaper extends JavaPlugin implements Listener, TabExecutor {

    private static final String TEXTURES = "textures";
    private static final String PERMISSION_RELOAD = "shskins.admin.reload";
    private static final String PERMISSION_INFO = "shskins.admin.info";

    private static volatile SHSkinsPaper instance;

    private volatile PaperSettings settings;
    private Messages messages;
    private SkinDatabase database;
    private SkinRegistry registry;
    private BukkitTask refreshTask;
    private SkinPlaceholders placeholders;

    /** Le plugin actif, ou null s'il n'est pas (ou plus) active. */
    public static SHSkinsPaper instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        File configFile = new File(getDataFolder(), "config.yml");
        copyDefault("paper/config.yml", configFile);
        try {
            settings = PaperSettings.load(configFile);
        } catch (ConfigException e) {
            getSLF4JLogger().error("{} : SHSkins reste desactive.", e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        messages = new Messages(getDataFolder().toPath(), getSLF4JLogger(), "paper/messages.yml", null);

        database = new SkinDatabase(settings.database(), getSLF4JLogger());
        registry = new SkinRegistry(database, getSLF4JLogger());
        instance = this;
        // Joueurs deja la (chargement a chaud) : leur skin est connu avant meme la lecture de la base
        Bukkit.getOnlinePlayers().forEach(this::remember);
        scheduleRefresh();

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("shskins");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new SkinPlaceholders(this);
            placeholders.register();
        }
    }

    @Override
    public void onDisable() {
        instance = null;
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (placeholders != null) {
            placeholders.unregister();
        }
        if (database != null) {
            database.close();
        }
    }

    public SkinRegistry registry() {
        return registry;
    }

    public PaperSettings settings() {
        return settings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    /** Le profil d'un joueur connecte porte le skin que le proxy vient de lui appliquer. */
    private void remember(Player player) {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (TEXTURES.equals(property.getName())) {
                registry.remember(player.getUniqueId(), player.getName(), new SkinData(property.getValue(), property.getSignature()));
                return;
            }
        }
    }

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        long period = settings.refreshSeconds() * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, registry::refresh, 0L, period);
    }

    // ---------------------------------------------------------------- /shskins

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && sender.hasPermission(PERMISSION_RELOAD)) {
            reload(sender);
        } else if (sub.equals("info") && sender.hasPermission(PERMISSION_INFO)) {
            info(sender, args);
        } else {
            send(sender, "usage", "commands", String.join("|", allowedSubcommands(sender)));
        }
        return true;
    }

    /**
     * Relit config.yml et messages.yml ; si un fichier est illisible, la configuration en cours reste active.
     * L'intervalle de relecture et le texte des placeholders s'appliquent tout de suite ; base-de-donnees demande un
     * redemarrage du serveur.
     */
    private void reload(CommandSender sender) {
        PaperSettings loaded;
        Runnable applyMessages;
        try {
            loaded = PaperSettings.load(new File(getDataFolder(), "config.yml"));
            applyMessages = messages.prepareReload();
        } catch (ConfigException e) {
            getSLF4JLogger().error("Rechargement impossible, la configuration precedente reste active : {}", e.getMessage());
            send(sender, "reload-failed", "file", e.file(), "error", e.detail());
            return;
        }
        boolean databaseChanged = !loaded.database().equals(settings.database());
        // La connexion ouverte garde ses reglages jusqu'au redemarrage
        settings = new PaperSettings(settings.database(), loaded.refreshSeconds(), loaded.unknown());
        applyMessages.run();
        scheduleRefresh();
        send(sender, "reloaded", "count", String.valueOf(registry.size()));
        if (databaseChanged) {
            send(sender, "reloaded-database");
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "info-usage");
            return;
        }
        String name = args[1];
        Optional<SkinRegistry.Entry> entry = registry.find(name);
        if (entry.isEmpty()) {
            send(sender, "info-unknown", "player", name);
            return;
        }
        String url = entry.get().skin().skinUrl().orElse("?");
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(entry.get().appliedAt()));
        send(sender, "info-known", "player", entry.get().name() != null ? entry.get().name() : name, "url", url, "date", date);
    }

    private List<String> allowedSubcommands(CommandSender sender) {
        List<String> out = new ArrayList<>();
        if (sender.hasPermission(PERMISSION_INFO)) {
            out.add("info");
        }
        if (sender.hasPermission(PERMISSION_RELOAD)) {
            out.add("reload");
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return allowedSubcommands(sender).stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info") && sender.hasPermission(PERMISSION_INFO)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void send(CommandSender sender, String key, String... pairs) {
        var component = messages.component(key, pairs);
        if (component != null) {
            sender.sendMessage(component);
        }
    }

    /** Copie le fichier du jar a sa place s'il n'existe pas encore. */
    private void copyDefault(String resource, File target) {
        if (target.exists()) {
            return;
        }
        try (InputStream in = getResource(resource)) {
            if (in != null) {
                Files.createDirectories(target.getParentFile().toPath());
                Files.copy(in, target.toPath());
            }
        } catch (Exception e) {
            getSLF4JLogger().error("Impossible de creer {}", target.getName(), e);
        }
    }
}
