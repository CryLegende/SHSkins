package org.Cry_Legende.sHSkins.command;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHSkins.config.PluginConfig;

/**
 * /skin : les skins se changent sur le site, la commande ne fait qu'y renvoyer.
 */
public class SkinCommand implements SimpleCommand {

    // extractUrls rend le lien du site cliquable dans le chat
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.AMPERSAND_CHAR)
        .extractUrls()
        .build();

    private final PluginConfig config;

    public SkinCommand(PluginConfig config) {
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        invocation.source().sendMessage(LEGACY.deserialize(config.getSkinCommandMessage()));
    }
}
