//! Configuration loading for the API Pooling System proxy.
//!
//! The configuration file (`config.json`) is shared with the Java admin layer.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    #[serde(default)]
    pub proxy: ProxyConfig,
    #[serde(default)]
    pub database: DatabaseConfig,
    #[serde(default)]
    pub admin: AdminConfig,
    #[serde(default)]
    pub defaults: DefaultsConfig,
    #[serde(default)]
    pub rate_limits: RateLimitsConfig,
    #[serde(default)]
    pub bot_protection: BotProtectionConfig,
    #[serde(default)]
    pub health: HealthConfig,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProxyConfig {
    #[serde(default = "default_host")]
    pub host: String,
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_true")]
    pub trust_x_forwarded_for: bool,
    #[serde(default = "default_lb")]
    pub load_balancer: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DatabaseConfig {
    #[serde(default = "default_db_path")]
    pub path: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AdminConfig {
    #[serde(default = "default_admin_user")]
    pub username: String,
    #[serde(default = "default_admin_pass")]
    pub password: String,
    #[serde(default = "default_token_days")]
    pub token_duration_days: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DefaultsConfig {
    #[serde(default = "default_key_expiry_days")]
    pub key_expiry_days: u32,
    #[serde(default = "default_key_limit")]
    pub default_key_limit: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RateLimitsConfig {
    #[serde(default = "default_window_seconds")]
    pub window_seconds: u64,
    #[serde(default = "default_max_per_ip")]
    pub max_requests_per_ip: u32,
    #[serde(default = "default_max_per_key")]
    pub max_requests_per_key: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct BotProtectionConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_bot_max_attempts")]
    pub max_attempts_per_minute: u32,
    #[serde(default = "default_block_minutes")]
    pub block_minutes: u64,
    #[serde(default)]
    pub whitelist: Vec<String>,
    #[serde(default)]
    pub blacklist: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct HealthConfig {
    #[serde(default = "default_health_interval")]
    pub interval_seconds: u64,
    #[serde(default = "default_health_timeout")]
    pub timeout_ms: u64,
}

// ---- defaults ----

fn default_host() -> String { "0.0.0.0".to_string() }
fn default_port() -> u16 { 8080 }
fn default_true() -> bool { true }
fn default_lb() -> String { "round_robin".to_string() }
fn default_db_path() -> String { "database/db.sqlite".to_string() }
fn default_admin_user() -> String { "admin".to_string() }
fn default_admin_pass() -> String { "admin".to_string() }
fn default_token_days() -> u32 { 7 }
fn default_key_expiry_days() -> u32 { 30 }
fn default_key_limit() -> u32 { 60 }
fn default_window_seconds() -> u64 { 60 }
fn default_max_per_ip() -> u32 { 120 }
fn default_max_per_key() -> u32 { 60 }
fn default_bot_max_attempts() -> u32 { 100 }
fn default_block_minutes() -> u64 { 60 }
fn default_health_interval() -> u64 { 300 }
fn default_health_timeout() -> u64 { 5000 }

impl Default for ProxyConfig {
    fn default() -> Self {
        ProxyConfig {
            host: default_host(),
            port: default_port(),
            trust_x_forwarded_for: default_true(),
            load_balancer: default_lb(),
        }
    }
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        DatabaseConfig { path: default_db_path() }
    }
}

impl Default for AdminConfig {
    fn default() -> Self {
        AdminConfig {
            username: default_admin_user(),
            password: default_admin_pass(),
            token_duration_days: default_token_days(),
        }
    }
}

impl Default for DefaultsConfig {
    fn default() -> Self {
        DefaultsConfig {
            key_expiry_days: default_key_expiry_days(),
            default_key_limit: default_key_limit(),
        }
    }
}

impl Default for RateLimitsConfig {
    fn default() -> Self {
        RateLimitsConfig {
            window_seconds: default_window_seconds(),
            max_requests_per_ip: default_max_per_ip(),
            max_requests_per_key: default_max_per_key(),
        }
    }
}

impl Default for BotProtectionConfig {
    fn default() -> Self {
        BotProtectionConfig {
            enabled: default_true(),
            max_attempts_per_minute: default_bot_max_attempts(),
            block_minutes: default_block_minutes(),
            whitelist: Vec::new(),
            blacklist: Vec::new(),
        }
    }
}

impl Default for HealthConfig {
    fn default() -> Self {
        HealthConfig {
            interval_seconds: default_health_interval(),
            timeout_ms: default_health_timeout(),
        }
    }
}

impl Default for Config {
    fn default() -> Self {
        Config {
            proxy: ProxyConfig::default(),
            database: DatabaseConfig::default(),
            admin: AdminConfig::default(),
            defaults: DefaultsConfig::default(),
            rate_limits: RateLimitsConfig::default(),
            bot_protection: BotProtectionConfig::default(),
            health: HealthConfig::default(),
        }
    }
}

impl Config {
    /// Load configuration from a JSON file. Falls back to defaults if the file
    /// is missing or cannot be parsed.
    pub fn load(path: &str) -> Config {
        match std::fs::read_to_string(path) {
            Ok(content) => serde_json::from_str(&content).unwrap_or_else(|e| {
                eprintln!("[config] invalid config file '{path}': {e}; using defaults");
                Config::default()
            }),
            Err(_) => {
                eprintln!("[config] config file '{path}' not found; using defaults");
                Config::default()
            }
        }
    }
}