-- ============================================================
-- API Pooling System - Database Schema
-- ============================================================
-- Shared by the Rust proxy core (SQLite via rusqlite) and the Java
-- admin layer (SQLite via sqlite-jdbc). The schema is created
-- automatically at startup if it does not exist.
--
-- Notes:
--  * `max_limit` is used instead of the README's `limit` because
--    LIMIT is a reserved word in SQL; the field means the same thing
--    (max requests per window for a user key).
--  * `key` is kept as-is (fine in SQLite); quote it if you deploy on
--    MySQL.
--  * IDs are UUID strings (36 chars).

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    tier VARCHAR(20) DEFAULT 'default',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_keys (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    key VARCHAR(255) UNIQUE NOT NULL,
    tier VARCHAR(20) DEFAULT 'default',
    status VARCHAR(20) DEFAULT 'active',
    max_limit INT DEFAULT 60,
    used INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS provider_keys (
    id VARCHAR(36) PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    key VARCHAR(255) UNIQUE NOT NULL,
    model VARCHAR(50),
    -- Header used to send the key upstream: NULL/'authorization' means
    -- 'Authorization: Bearer <key>'; set e.g. 'x-goog-api-key' for Gemini.
    auth_header VARCHAR(50),
    status VARCHAR(20) DEFAULT 'active',
    used INT DEFAULT 0,
    failed INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS providers (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'active',
    health INT DEFAULT 100,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS logs (
    id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    ip VARCHAR(45),
    user_id VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Models per provider (used by the `add-model` command)
CREATE TABLE IF NOT EXISTS models (
    id VARCHAR(36) PRIMARY KEY,
    provider_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    FOREIGN KEY (provider_id) REFERENCES providers(id)
);