package org.Cry_Legende.sHSkins.service;

import org.Cry_Legende.sHSkins.api.PiecePriority;

import java.io.IOException;

/** Un service qui pose une image sur textures.minecraft.net, le seul hote que les clients acceptent. */
public interface TextureHost {

    /** Ce service ne peut plus deposer pour l'instant : l'image n'y est pour rien, un autre service peut la prendre. */
    final class Unavailable extends IOException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** Depots possibles en meme temps. */
    int slots();

    /** Millisecondes avant de pouvoir deposer (0 : tout de suite), -1 s'il n'est pas utilisable. */
    long waitMs(PiecePriority priority);

    /** Depose une image et rend son empreinte. Bloquant : hors du thread principal. */
    String upload(byte[] png, PiecePriority priority) throws IOException, InterruptedException;
}
