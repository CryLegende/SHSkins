package org.Cry_Legende.sHSkins.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinDataTest {

    private static final String HASH = "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";

    private static SkinData skin(String metadata) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + HASH + "\"" + metadata + "}}}";
        return new SkinData(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    void empreinteEtBrasFins() {
        assertEquals(HASH, skin("").skinHash().orElseThrow());
        assertFalse(skin("").slim());
        assertTrue(skin(",\"metadata\":{\"model\":\"slim\"}").slim());
        assertTrue(new SkinData("illisible", null).skinHash().isEmpty());
        assertFalse(new SkinData("illisible", null).slim());
    }
}
