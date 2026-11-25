package org.Cry_Legende.sHSkins.api;

/** Ordre dans lequel les morceaux de skins se font ({@link SHSkinsAPI#makePieces}). */
public enum PiecePriority {

    /** Une statue va se montrer : avant tout le reste. */
    URGENT,

    /** Une statue se montre, mais elle peut attendre. */
    NORMAL,

    /** Pourrait servir : seulement s'il reste de la place, sans gener les autres. */
    LATER
}
