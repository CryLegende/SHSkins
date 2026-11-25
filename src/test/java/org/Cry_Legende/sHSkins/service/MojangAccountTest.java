package org.Cry_Legende.sHSkins.service;

import com.google.gson.JsonParser;
import org.Cry_Legende.sHSkins.api.PiecePriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MojangAccountTest {

    @Test
    void lEmpreinteEstCelleDuSkinActif() {
        String hash = "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
        String profile = "{\"id\":\"a\",\"name\":\"b\",\"skins\":["
            + "{\"state\":\"INACTIVE\",\"url\":\"http://textures.minecraft.net/texture/aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
            + "{\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/" + hash + "\",\"variant\":\"CLASSIC\"}]}";
        assertEquals(hash, MojangAccount.active(JsonParser.parseString(profile).getAsJsonObject()));
        assertNull(MojangAccount.active(JsonParser.parseString("{\"skins\":[]}").getAsJsonObject()));
        assertNull(MojangAccount.active(null));
    }

    @Test
    void sansCompteIlNeDeposePas(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("compte-mojang.json");
        MojangAccount account = new MojangAccount(file, "00000000402b5328", 16, null);
        assertNull(account.name());
        assertEquals(-1, account.waitMs(PiecePriority.URGENT));
        Files.writeString(file, "{\"refresh\":\"jeton\",\"name\":\"Compte\",\"uuid\":\"x\",\"renewed\":1}");
        MojangAccount known = new MojangAccount(file, "00000000402b5328", 16, null);
        assertEquals("Compte", known.name());
        assertEquals(0, known.waitMs(PiecePriority.LATER));
        assertEquals(16, known.left());
        known.disconnect();
        assertFalse(Files.exists(file));
        assertEquals(-1, known.waitMs(PiecePriority.URGENT));
    }
}
