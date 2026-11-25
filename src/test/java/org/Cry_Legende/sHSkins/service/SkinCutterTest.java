package org.Cry_Legende.sHSkins.service;

import org.Cry_Legende.sHSkins.model.SkinPart;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkinCutterTest {

    private static BufferedImage skin(int height) {
        BufferedImage skin = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 64; x++) {
                skin.setRGB(x, y, 0xFF000000 | x * 4 << 16 | y * 4 << 8);
            }
        }
        return skin;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void lesMorceauxGardentLesPixelsDuSkin() throws IOException {
        BufferedImage skin = skin(64);
        Map<SkinPart, byte[]> pieces = SkinCutter.cut(png(skin), false);
        assertEquals(10, pieces.size());
        BufferedImage up = ImageIO.read(new ByteArrayInputStream(pieces.get(SkinPart.TORSO_UP)));
        BufferedImage low = ImageIO.read(new ByteArrayInputStream(pieces.get(SkinPart.TORSO_LOW)));
        // Buste : la face avant est en (20, 20), 8 x 12 ; le haut garde les lignes 0 a 7, le bas les lignes 4 a 11
        assertEquals(skin.getRGB(20, 20), up.getRGB(8, 8));
        assertEquals(skin.getRGB(27, 27), up.getRGB(15, 15));
        assertEquals(skin.getRGB(20, 24), low.getRGB(8, 8));
        assertEquals(skin.getRGB(27, 31), low.getRGB(15, 15));
        // Bras droit : avant en (44, 20), 4 x 12, deux pixels de large par pixel ; bras fins : 3 de large
        assertEquals(skin.getRGB(47, 27), SkinCutter.piece(skin, SkinPart.ARM_R_UP, false).getRGB(15, 15));
        assertEquals(skin.getRGB(46, 20), SkinCutter.piece(skin, SkinPart.ARM_R_UP, true).getRGB(15, 8));
        // Seconde couche de la manche gauche : (52, 52) dans le skin, a cote de la premiere dans la tete
        assertEquals(skin.getRGB(52, 52), SkinCutter.piece(skin, SkinPart.ARM_L_UP, false).getRGB(40, 8));
    }

    @Test
    void unAncienSkinALesMembresGauchesEnMiroir() throws IOException {
        BufferedImage skin = SkinCutter.read(png(skin(32)));
        assertEquals(skin.getRGB(4, 20), skin.getRGB(23, 52));
        assertEquals(skin.getRGB(47, 25), skin.getRGB(36, 57));
        assertThrows(IOException.class, () -> SkinCutter.cut(png(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)), false));
    }
}
