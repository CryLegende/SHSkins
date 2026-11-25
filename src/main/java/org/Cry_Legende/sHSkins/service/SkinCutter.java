package org.Cry_Legende.sHSkins.service;

import org.Cry_Legende.sHSkins.model.SkinPart;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Decoupe un skin en morceaux, au pixel pres : chacun devient la tete d'un skin (une face de 8 x 8 par cote), sa seconde couche
 * (veste, manches, pantalon) va dans celle de la tete.
 */
public final class SkinCutter {

    private static final int[][] FACES = {{8, 0}, {16, 0}, {0, 8}, {8, 8}, {16, 8}, {24, 8}};
    private static final int TOP = 0, BOTTOM = 1, RIGHT = 2, FRONT = 3, LEFT = 4, BACK = 5;
    private static final int OVER = 32;

    private SkinCutter() {
    }

    /**
     * Les dix morceaux d'un skin, en PNG.
     *
     * @param png  image du skin (64 x 64, ou 64 x 32 pour l'ancien format)
     * @param slim bras fins
     */
    public static Map<SkinPart, byte[]> cut(byte[] png, boolean slim) throws IOException {
        BufferedImage skin = read(png);
        Map<SkinPart, byte[]> out = new EnumMap<>(SkinPart.class);
        for (SkinPart part : SkinPart.values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(piece(skin, part, slim), "png", bytes);
            out.put(part, bytes.toByteArray());
        }
        return out;
    }

    static BufferedImage read(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IOException("skin illisible (64 x 64 attendu)");
        }
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        skin.createGraphics().drawImage(image, 0, 0, null);
        if (image.getHeight() == 32) {
            // Ancien format : le bras et la jambe gauches sont les droits en miroir
            mirror(skin, 40, 16, 32, 48);
            mirror(skin, 0, 16, 16, 48);
        }
        return skin;
    }

    private static void mirror(BufferedImage skin, int fromU, int fromV, int toU, int toV) {
        for (int face = 0; face < FACES.length; face++) {
            int source = face == RIGHT ? LEFT : face == LEFT ? RIGHT : face;
            int[] a = face(fromU, fromV, 4, source);
            int[] b = face(toU, toV, 4, face);
            for (int y = 0; y < a[3]; y++) {
                for (int x = 0; x < a[2]; x++) {
                    skin.setRGB(b[0] + x, b[1] + y, skin.getRGB(a[0] + a[2] - 1 - x, a[1] + y));
                }
            }
        }
    }

    /** x, y, largeur et hauteur d'une face d'une boite du skin. */
    private static int[] face(int u, int v, int w, int face) {
        int h = SkinPart.HEIGHT;
        int d = SkinPart.DEPTH;
        return switch (face) {
            case TOP -> new int[]{u + d, v, w, d};
            case BOTTOM -> new int[]{u + d + w, v, w, d};
            case RIGHT -> new int[]{u, v + d, d, h};
            case FRONT -> new int[]{u + d, v + d, w, h};
            case LEFT -> new int[]{u + d + w, v + d, d, h};
            case BACK -> new int[]{u + d + w + d, v + d, w, h};
            default -> throw new IllegalArgumentException("face " + face);
        };
    }

    static BufferedImage piece(BufferedImage skin, SkinPart part, boolean slim) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int w = part.width(slim);
        for (int face = 0; face < FACES.length; face++) {
            copy(skin, out, part, part.u(), part.v(), w, face, 0);
            copy(skin, out, part, part.overU(), part.overV(), w, face, OVER);
        }
        return out;
    }

    private static void copy(BufferedImage skin, BufferedImage out, SkinPart part, int u, int v, int w, int face, int shift) {
        int[] box = face(u, v, w, face);
        int x0 = box[0], y0 = box[1], fw = box[2], fh = box[3];
        if (face == TOP || face == BOTTOM) {
            boolean inside = face == TOP ? part.from() > 0 : part.to() < SkinPart.HEIGHT;
            if (inside) {
                // Face cachee dans le membre : la rangee voisine, etalee
                if (shift > 0) {
                    return;
                }
                int[] front = face(u, v, w, FRONT);
                x0 = front[0];
                fw = front[2];
                y0 = front[1] + (face == TOP ? part.from() : part.to() - 1);
                fh = 1;
            }
        } else {
            y0 += part.from();
            fh = SkinPart.ROWS;
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int argb = skin.getRGB(x0 + x * fw / 8, y0 + y * fh / 8);
                // La premiere couche d'une tete est toujours opaque
                out.setRGB(FACES[face][0] + shift + x, FACES[face][1] + y, shift == 0 ? argb | 0xFF000000 : argb);
            }
        }
    }
}
