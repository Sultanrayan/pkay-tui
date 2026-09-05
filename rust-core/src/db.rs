//! SQLite storage layer for the proxy.
//!
//! The schema matches `database/schema.sql` and is shared with the Java admin
//! layer, so both processes can operate on the same database file.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection};

/// Table schema shared with the Java admin layer. The `max_limit` column is
/// named to avoid the SQL reserved word `LIMIT` (the README spec called it
/// `limit`).
const SCHEMA: &str = r#"
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
    expires_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS provider_keys (
    id VARCHAR(36) PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    key VARCHAR(255) UNIQUE NOT NULL,
    model VARCHAR(50),
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

CREATE TABLE IF NOT EXISTS models (
    id VARCHAR(36) PRIMARY KEY,
    provider_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL
);
"#;

#[derive(Debug, Clone)]
pub struct UserKeyInfo {
    pub id: String,
    pub user_id: String,
    pub key: String,
    pub tier: String,
    pub status: String,
    pub max_limit: i64,
    pub used: i64,
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ProviderInfo {
    pub id: String,
    pub name: String,
    pub base_url: String,
    pub status: String,
    pub health: i64,
}

#[derive(Debug, Clone)]
pub struct ProviderKeyInfo {
    pub id: String,
    pub provider: String,
    pub key: String,
    pub model: Option<String>,
    /// Header used to send the key upstream. `None`/"authorization" means
    /// `Authorization: Bearer <key>`; e.g. "x-goog-api-key" for Gemini.
    pub auth_header: Option<String>,
    pub status: String,
    pub used: i64,
    pub failed: i64,
}

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    /// Open (or create) the SQLite database and ensure the schema exists.
    pub fn new(path: &str) -> rusqlite::Result<Database> {
        if let Some(parent) = Path::new(path).parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA busy_timeout=5000;
             PRAGMA foreign_keys=ON;",
        )?;
        conn.execute_batch(SCHEMA)?;
        // Migration: older databases lack the auth_header column.
        let _ = conn.execute("ALTER TABLE provider_keys ADD COLUMN auth_header TEXT", []);
        Ok(Database { conn: Mutex::new(conn) })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap()
    }

    // ---- providers ----

    pub fn get_provider_by_name(&self, name: &str) -> Option<ProviderInfo> {
        let conn = self.lock();
        let mut stmt = conn
            .prepare(
                "SELECT id, name, base_url, status, health FROM providers WHERE name = ?1",
            )
            .ok()?;
        let mut rows = stmt
            .query_map(params![name], |row| {
                Ok(ProviderInfo {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    base_url: row.get(2)?,
                    status: row.get(3)?,
                    health: row.get(4)?,
                })
            })
            .ok()?;
        rows.next().and_then(|r| r.ok())
    }

    pub fn list_providers(&self) -> Vec<ProviderInfo> {
        let conn = self.lock();
        let mut stmt = conn
            .prepare("SELECT id, name, base_url, status, health FROM providers ORDER BY name")
            .unwrap();
        let rows = stmt
            .query_map([], |row| {
                Ok(ProviderInfo {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    base_url: row.get(2)?,
                    status: row.get(3)?,
                    health: row.get(4)?,
                })
            })
            .unwrap();
        rows.filter_map(|r| r.ok()).collect()
    }

    pub fn set_provider_status(&self, name: &str, status: &str) {
        let conn = self.lock();
        let _ = conn.execute(
            "UPDATE providers SET status = ?1 WHERE name = ?2",
            params![status, name],
        );
    }

    pub fn set_provider_health(&self, name: &str, health: i64) {
        let conn = self.lock();
        let _ = conn.execute(
            "UPDATE providers SET health = ?1 WHERE name = ?2",
            params![health, name],
        );
    }

    // ---- provider keys ----

    pub fn list_provider_keys(&self, provider: &str) -> Vec<ProviderKeyInfo> {
        let conn = self.lock();
        let mut stmt = conn
            .prepare(
                "SELECT id, provider, key, model, auth_header, status, used, failed
                 FROM provider_keys WHERE provider = ?1 ORDER BY created_at",
            )
            .unwrap();
        let rows = stmt
            .query_map(params![provider], |row| {
                Ok(ProviderKeyInfo {
                    id: row.get(0)?,
                    provider: row.get(1)?,
                    key: row.get(2)?,
                    model: row.get(3)?,
                    auth_header: row.get(4)?,
                    status: row.get(5)?,
                    used: row.get(6)?,
                    failed: row.get(7)?,
                })
            })
            .unwrap();
        rows.filter_map(|r| r.ok()).collect()
    }

    pub fn mark_provider_key_success(&self, id: &str) {
        let conn = self.lock();
        let _ = conn.execute(
            "UPDATE provider_keys SET used = used + 1, failed = 0 WHERE id = ?1",
            params![id],
        );
    }

    pub fn mark_provider_key_failure(&self, id: &str) {
        let conn = self.lock();
        let _ = conn.execute(
            "UPDATE provider_keys SET failed = failed + 1 WHERE id = ?1",
            params![id],
        );
    }

    // ---- user keys ----

    pub fn get_user_key(&self, key_value: &str) -> Option<UserKeyInfo> {
        let conn = self.lock();
        let mut stmt = conn
            .prepare(
                "SELECT id, user_id, key, tier, status, max_limit, used, expires_at
                 FROM user_keys WHERE key = ?1",
            )
            .ok()?;
        let mut rows = stmt
            .query_map(params![key_value], |row| {
                Ok(UserKeyInfo {
                    id: row.get(0)?,
                    user_id: row.get(1)?,
                    key: row.get(2)?,
                    tier: row.get(3)?,
                    status: row.get(4)?,
                    max_limit: row.get(5)?,
                    used: row.get(6)?,
                    expires_at: row.get(7)?,
                })
            })
            .ok()?;
        rows.next().and_then(|r| r.ok())
    }

    pub fn mark_user_key_used(&self, id: &str) {
        let conn = self.lock();
        let _ = conn.execute(
            "UPDATE user_keys SET used = used + 1 WHERE id = ?1",
            params![id],
        );
    }

    // ---- logs ----

    pub fn add_log(
        &self,
        log_type: &str,
        message: impl Into<String>,
        ip: Option<&str>,
        user_id: Option<&str>,
    ) {
        let conn = self.lock();
        let id = uuid::Uuid::new_v4().to_string();
        let message = message.into();
        let _ = conn.execute(
            "INSERT INTO logs (id, type, message, ip, user_id) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, log_type, message, ip, user_id],
        );
    }
}