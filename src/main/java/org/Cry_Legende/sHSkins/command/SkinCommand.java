package org.Cry_Legende.sHSkins.command;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.Cry_Legende.sHSkins.config.Messages;

/**
 * /skin : les skins se changent sur le site, la commande ne fait qu'y renvoyer.
 */
public class SkinCommand implements SimpleCommand {

    private final Messages messages;

    public SkinCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        Component message = messages.component("skin-command");
        if (message != null) {
            invocation.source().sendMessage(message);
        }
    }
}
