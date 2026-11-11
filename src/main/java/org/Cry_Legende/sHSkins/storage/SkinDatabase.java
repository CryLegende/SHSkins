package org.Cry_Legende.sHSkins.storage;

import org.Cry_Legende.sHSkins.model.SkinData;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Stockage MariaDB des skins, commun au proxy et aux serveurs Paper. Tables (schema.sql du depot) :
 * <ul>
 *     <li>{@code shskins_skins} : le skin que le proxy a applique a la derniere connexion de chaque joueur ;</li>
 *     <li>{@code shskins_accounts} : la derniere reponse de Mojang pour le compte du meme pseudo (proxy seulement).</li>
 * </ul>
 * Une seule connexion, rouverte apres une erreur. Les appels sont bloquants : a faire hors du thread principal.
 */
public final class SkinDatabase implements AutoCloseable {

    /** Reglages de connexion lus dans config.yml (base-de-donnees). */
    public record Settings(String url, String user, String password) {
    }

    /**
     * Ligne de {@code shskins_skins}.
     *
     * @param name      pseudo a la derniere connexion (null pour un skin repris des anciens fichiers)
     * @param appliedAt date ou ce skin a ete applique, en millisecondes
     * @param writtenAt date de l'ecriture de la ligne, en millisecondes (relecture des changements)
     */
    public record StoredSkin(UUID uuid, String name, SkinData skin, long appliedAt, long writtenAt) {
    }

    /**
     * Ligne de {@code shskins_accounts}.
     *
     * @param found     un compte Mojang existe pour ce pseudo
     * @param checkedAt date de la reponse, en millisecondes
     */
    public record AccountEntry(boolean found, String value, String signature, long checkedAt) {

        public Optional<SkinData> skin() {
            return found && value != null ? Optional.of(new SkinData(value, signature)) : Optional.empty();
        }
    }

    // Le skin le plus recent gagne : un import d'anciens fichiers n'ecrase jamais un skin applique depuis.
    // MariaDB applique les affectations dans l'ordre : applied_at est mis a jour en dernier.
    private static final String UPSERT_SKIN = "INSERT INTO shskins_skins (uuid, name, value, signature, applied_at, written_at)"
        + " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
        + " name = COALESCE(VALUES(name), name),"
        + " value = IF(VALUES(applied_at) >= applied_at, VALUES(value), value),"
        + " signature = IF(VALUES(applied_at) >= applied_at, VALUES(signature), signature),"
        + " written_at = VALUES(written_at),"
        + " applied_at = GREATEST(applied_at, VALUES(applied_at))";

    private static final String UPSERT_ACCOUNT = "INSERT INTO shskins_accounts (uuid, found, value, signature, checked_at)"
        + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
        + " found = IF(VALUES(checked_at) >= checked_at, VALUES(found), found),"
        + " value = IF(VALUES(checked_at) >= checked_at, VALUES(value), value),"
        + " signature = IF(VALUES(checked_at) >= checked_at, VALUES(signature), signature),"
        + " checked_at = GREATEST(checked_at, VALUES(checked_at))";

    private static final String SKIN_COLUMNS = "uuid, name, value, signature, applied_at, written_at";

    private final Settings settings;
    private final Logger logger;
    // Pilote relocalise par le shade : instancie directement, DriverManager ne le verrait pas
    private final Driver driver = new org.mariadb.jdbc.Driver();
    private Connection connection;

    public SkinDatabase(Settings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    /** Ouvre la connexion tout de suite : une erreur de configuration se voit au demarrage. */
    public synchronized boolean check() {
        try {
            connection();
            return true;
        } catch (SQLException e) {
            logger.error("Connexion a MariaDB impossible ({}) : {}", settings.url(), e.getMessage());
            return false;
        }
    }

    public synchronized Optional<StoredSkin> findSkin(UUID uuid) throws SQLException {
        return withRetry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SKIN_COLUMNS + " FROM shskins_skins WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readSkin(rs)) : Optional.<StoredSkin>empty();
                }
            }
        });
    }

    /** Lignes ecrites a partir de {@code since} (millisecondes), toutes avec 0. */
    public synchronized List<StoredSkin> skinsWrittenSince(long since) throws SQLException {
        return withRetry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SKIN_COLUMNS + " FROM shskins_skins WHERE written_at >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredSkin> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(readSkin(rs));
                    }
                    return out;
                }
            }
        });
    }

    /**
     * Enregistre le skin d'un joueur, sauf si la base en a deja un plus recent.
     *
     * @param name      pseudo, ou null pour garder celui de la base
     * @param appliedAt date ou ce skin a ete applique
     */
    public synchronized void saveSkin(UUID uuid, String name, SkinData skin, long appliedAt) throws SQLException {
        withRetry(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT_SKIN)) {
                ps.setString(1, uuid.toString());
                if (name == null) {
                    ps.setNull(2, Types.VARCHAR);
                } else {
                    ps.setString(2, name);
                }
                ps.setString(3, skin.value());
                if (skin.signature() == null) {
                    ps.setNull(4, Types.LONGVARCHAR);
                } else {
                    ps.setString(4, skin.signature());
                }
                ps.setLong(5, appliedAt);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public synchronized Optional<AccountEntry> findAccount(UUID uuid) throws SQLException {
        return withRetry(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT found, value, signature, checked_at FROM shskins_accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<AccountEntry>empty();
                    }
                    return Optional.of(new AccountEntry(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        });
    }

    /** Enregistre une reponse de Mojang, sauf si la base en a deja une plus recente. */
    public synchronized void saveAccount(UUID uuid, AccountEntry entry) throws SQLException {
        withRetry(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT_ACCOUNT)) {
                ps.setString(1, uuid.toString());
                ps.setBoolean(2, entry.found());
                ps.setString(3, entry.value());
                ps.setString(4, entry.signature());
                ps.setLong(5, entry.checkedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Fermeture : rien a recuperer
            }
            connection = null;
        }
    }

    private interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Une connexion coupee (MariaDB redemarre, wait_timeout) est rouverte une fois avant d'abandonner. */
    private <T> T withRetry(Query<T> query) throws SQLException {
        try {
            return query.run(connection());
        } catch (SQLException first) {
            close();
            return query.run(connection());
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        Properties props = new Properties();
        props.setProperty("user", settings.user());
        props.setProperty("password", settings.password());
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "10000");
        Connection opened = driver.connect(settings.url(), props);
        if (opened == null) {
            throw new SQLException("URL non reconnue par le pilote MariaDB : " + settings.url());
        }
        connection = opened;
        return connection;
    }

    private static StoredSkin readSkin(ResultSet rs) throws SQLException {
        return new StoredSkin(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            new SkinData(rs.getString(3), rs.getString(4)),
            rs.getLong(5),
            rs.getLong(6));
    }
}
