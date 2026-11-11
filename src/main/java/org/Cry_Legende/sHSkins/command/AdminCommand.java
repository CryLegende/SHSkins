package org.Cry_Legende.sHSkins.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.Cry_Legende.sHSkins.SHSkins;
import org.Cry_Legende.sHSkins.config.ConfigException;
import org.Cry_Legende.sHSkins.config.Messages;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /shskins reload : relit config.yml et messages.yml. */
public class AdminCommand implements SimpleCommand {

    private static final String RELOAD_PERMISSION = "shskins.admin.reload";

    private final SHSkins plugin;
    private final Messages messages;

    public AdminCommand(SHSkins plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            send(source, messages.component("usage", "commands", "reload"));
            return;
        }
        try {
            plugin.reload();
            send(source, messages.component("reloaded"));
        } catch (ConfigException e) {
            plugin.getLogger().error("Rechargement impossible, la configuration precedente reste active : {}", e.getMessage());
            send(source, messages.component("reload-failed", "file", e.file(), "error", e.detail()));
        }
    }

    private static void send(CommandSource source, Component message) {
        if (message != null) {
            source.sendMessage(message);
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1 && "reload".startsWith(args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT))) {
            return CompletableFuture.completedFuture(List.of("reload"));
        }
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(RELOAD_PERMISSION);
    }
}
