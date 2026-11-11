package org.Cry_Legende.sHSkins.config;

import java.io.IOException;

/** Fichier de configuration illisible : porte le nom du fichier et la premiere ligne de l'erreur. */
public final class ConfigException extends IOException {

    private final String file;
    private final String detail;

    public ConfigException(String file, Throwable cause) {
        super(file + " : " + firstLine(cause), cause);
        this.file = file;
        this.detail = firstLine(cause);
    }

    public String file() {
        return file;
    }

    public String detail() {
        return detail;
    }

    private static String firstLine(Throwable cause) {
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return message.lines().findFirst().orElse("");
    }
}
