-- Tables de SHSkins (base Global ; bo_Global sur backopale).
-- L'utilisateur shskins n'a que SELECT, INSERT, UPDATE sur ces deux tables.

-- Skin appliqué par le proxy à la dernière connexion de chaque joueur.
-- applied_at : date où ce skin a été appliqué (le plus récent gagne) ; written_at : date d'écriture de la ligne,
-- relue par les serveurs Paper.
CREATE TABLE IF NOT EXISTS shskins_skins (
    uuid       CHAR(36)    NOT NULL,
    name       VARCHAR(16) NULL,
    value      TEXT        NOT NULL,
    signature  TEXT        NULL,
    applied_at BIGINT      NOT NULL,
    written_at BIGINT      NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_shskins_skins_name (name),
    KEY idx_shskins_skins_written (written_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- Dernière réponse de Mojang pour le compte du même pseudo (found = 0 : aucun compte).
CREATE TABLE IF NOT EXISTS shskins_accounts (
    uuid       CHAR(36)   NOT NULL,
    found      TINYINT(1) NOT NULL,
    value      TEXT       NULL,
    signature  TEXT       NULL,
    checked_at BIGINT     NOT NULL,
    PRIMARY KEY (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
