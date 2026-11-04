-- =====================================================================
-- V8 : Align fixed-width token columns with what the JPA entities declare.
--
-- V1 created these as CHAR, which is defensible for fixed-length hex, but the
-- entities map plain Strings and Hibernate's `ddl-auto: validate` therefore
-- expects VARCHAR. Rather than leak a database-specific columnDefinition into
-- the entity, the schema moves to VARCHAR so both sides agree naturally.
--
-- A forward migration rather than an edit to V1: V1 has already been applied,
-- and changing an applied migration breaks Flyway's checksum for every
-- database that ran it.
-- =====================================================================

ALTER TABLE refresh_tokens
    MODIFY COLUMN token_hash VARCHAR(64) NOT NULL,
    MODIFY COLUMN family_id  VARCHAR(36) NOT NULL;

ALTER TABLE password_reset_tokens
    MODIFY COLUMN token_hash VARCHAR(64) NOT NULL;
