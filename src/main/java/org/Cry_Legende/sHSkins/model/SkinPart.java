package org.Cry_Legende.sHSkins.model;

/**
 * Morceaux d'un skin, sans la tete (celle du skin sert telle quelle). Le client n'affiche d'une tete que la zone « tete » d'un
 * skin : chaque morceau devient donc la tete d'un skin a lui.
 * <p>
 * Un membre est coupe en deux. Chaque morceau garde huit lignes : les deux moities se recouvrent de quatre lignes autour de
 * l'articulation, qui reste pleine quand elle plie.
 */
public enum SkinPart {

    TORSO_UP("torso_up", 16, 16, 8, 0),
    TORSO_LOW("torso_low", 16, 16, 8, 4),
    ARM_R_UP("arm_r_up", 40, 16, 4, 0),
    ARM_R_LOW("arm_r_low", 40, 16, 4, 4),
    ARM_L_UP("arm_l_up", 32, 48, 4, 0),
    ARM_L_LOW("arm_l_low", 32, 48, 4, 4),
    LEG_R_UP("leg_r_up", 0, 16, 4, 0),
    LEG_R_LOW("leg_r_low", 0, 16, 4, 4),
    LEG_L_UP("leg_l_up", 16, 48, 4, 0),
    LEG_L_LOW("leg_l_low", 16, 48, 4, 4);

    /** Hauteur et profondeur, en pixels, de la boite entiere (buste, bras ou jambe). */
    public static final int HEIGHT = 12;
    public static final int DEPTH = 4;
    /** Lignes gardees par un morceau. */
    public static final int ROWS = 8;

    private final String key;
    private final int u;
    private final int v;
    private final int width;
    private final int from;

    /**
     * @param u    coin de la boite dans le skin
     * @param from premiere ligne gardee, depuis le haut
     */
    SkinPart(String key, int u, int v, int width, int from) {
        this.key = key;
        this.u = u;
        this.v = v;
        this.width = width;
        this.from = from;
    }

    /** Nom du morceau dans l'API et dans la base. */
    public String key() {
        return key;
    }

    public int u() {
        return u;
    }

    public int v() {
        return v;
    }

    /** Coin de la seconde couche (veste, manches, pantalon). */
    public int overU() {
        return switch (this) {
            case ARM_L_UP, ARM_L_LOW -> 48;
            case LEG_L_UP, LEG_L_LOW -> 0;
            default -> u;
        };
    }

    public int overV() {
        return switch (this) {
            case ARM_L_UP, ARM_L_LOW, LEG_L_UP, LEG_L_LOW -> 48;
            default -> v + 16;
        };
    }

    /** Largeur dans le skin, bras fins compris. */
    public int width(boolean slim) {
        return slim && key.startsWith("arm") ? 3 : width;
    }

    public int from() {
        return from;
    }

    public int to() {
        return from + ROWS;
    }
}
