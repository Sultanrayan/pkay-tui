//! Admin HTTP API.
//!
//! Lets the deployed proxy be managed remotely (login, add providers/keys,
//! create users, generate keys, import models, view stats). Sessions are
//! in-memory bearer tokens stored in `ProxyState.admin_sessions`; login uses
//! the `admin` section of config (which can be overridden by the
//! `ADMIN_USERNAME` / `ADMIN_PASSWORD` environment variables).
//!
//! Formats match the Java admin layer so both can operate on the same
//! database: user passwords are stored as `sha256:<salt-hex>:<hash-hex>`
//! (SHA-256 over salt || password) and user keys are `pkay_` + 48 hex chars.

use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::body::{to_bytes, Body};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::Response;
use serde::Deserialize;
use serde_json::{json, Value};

use sha2::{Digest, Sha256};

use crate::db::{LogEntry, UserKeyInfo};
use crate::proxy::ProxyState;

use rand::RngCore;

// ---- request bodies ----

#[derive(Deserialize)]
struct LoginReq {
    username: String,
    password: String,
}

#[derive(Deserialize)]
struct AddProviderReq {
    name: String,
    url: String,
}

#[derive(Deserialize)]
struct AddKeyReq {
    key: String,
    #[serde(default)]
    auth_header: Option<String>,
}

#[derive(Deserialize)]
struct AddUserReq {
    username: String,
    password: String,
    #[serde(default)]
    tier: Option<String>,
}

#[derive(Deserialize)]
struct GenerateKeyReq {
    #[serde(default)]
    tier: Option<String>,
    #[serde(default)]
    max_limit: Option<i64>,
}

#[derive(Deserialize)]
struct RevokeKeyReq {
    key: String,
}

// ---- response helpers ----

fn json(status: StatusCode, value: Value) -> Response {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(value.to_string()))
        .unwrap()
}

fn err(status: StatusCode, message: &str) -> Response {
    json(status, json!({ "error": message, "status": status.as_u16() }))
}

async fn json_body<T: serde::de::DeserializeOwned>(req: axum::extract::Request) -> Result<T, Response> {
    let bytes = to_bytes(req.into_body(), 1024 * 1024)
        .await
        .map_err(|e| err(StatusCode::BAD_REQUEST, &format!("failed to read body: {e}")))?;
    serde_json::from_slice(&bytes)
        .map_err(|e| err(StatusCode::BAD_REQUEST, &format!("invalid JSON body: {e}")))
}

// ---- auth ----

fn bearer_token(headers: &HeaderMap) -> Option<String> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    for prefix in ["Bearer ", "bearer "] {
        if let Some(t) = value.strip_prefix(prefix) {
            return Some(t.trim().to_string());
        }
    }
    None
}

/// Validate the admin bearer token, pruning expired sessions. Returns the
/// token on success, or an error response.
fn require_admin(state: &ProxyState, headers: &HeaderMap) -> Result<String, Response> {
    let token = bearer_token(headers)
        .ok_or_else(|| err(StatusCode::UNAUTHORIZED, "missing admin token"))?;
    let mut sessions = state.admin_sessions.write().unwrap();
    let now = Instant::now();
    sessions.retain(|_, expiry| *expiry > now);
    match sessions.get(&token) {
        Some(expiry) if *expiry > now => Ok(token),
        _ => Err(err(StatusCode::UNAUTHORIZED, "invalid or expired admin token")),
    }
}

async fn login(state: &ProxyState, req: axum::extract::Request) -> Response {
    let body: LoginReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    let admin = &state.config.admin;
    if body.username != admin.username || body.password != admin.password {
        return err(StatusCode::UNAUTHORIZED, "invalid admin credentials");
    }
    let token = random_hex(32);
    let days = admin.token_duration_days.max(1) as u64;
    let expiry = Instant::now() + Duration::from_secs(days * 86400);
    state.admin_sessions.write().unwrap().insert(token.clone(), expiry);
    state.db.add_log("admin_login", format!("admin '{}' logged in", body.username), None, None);
    json(
        StatusCode::OK,
        json!({ "token": token, "expires_in_days": days, "username": body.username }),
    )
}

// ---- providers ----

fn list_providers(state: &ProxyState) -> Response {
    let providers = state.db.list_providers();
    let arr: Vec<Value> = providers
        .iter()
        .map(|p| {
            let keys: Vec<Value> = state
                .db
                .list_provider_keys(&p.name)
                .iter()
                .map(|k| {
                    json!({
                        "id": k.id,
                        "key": mask(&k.key),
                        "auth_header": k.auth_header,
                        "status": k.status,
                        "used": k.used,
                        "failed": k.failed,
                    })
                })
                .collect();
            json!({
                "name": p.name,
                "base_url": p.base_url,
                "status": p.status,
                "health": p.health,
                "models": state.db.list_models(&p.name),
                "keys": keys,
            })
        })
        .collect();
    json(StatusCode::OK, json!({ "providers": arr }))
}

async fn add_provider(state: &ProxyState, req: axum::extract::Request) -> Response {
    let body: AddProviderReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    match state.db.add_provider(&body.name, &body.url) {
        Ok(()) => {
            state.db.add_log("provider", format!("added provider '{}'", body.name), None, None);
            json(
                StatusCode::CREATED,
                json!({ "name": body.name, "base_url": body.url, "status": "active" }),
            )
        }
        Err(e) => err(StatusCode::BAD_REQUEST, &e),
    }
}

fn delete_provider(state: &ProxyState, name: &str) -> Response {
    if state.db.delete_provider(name) {
        state.db.add_log("provider", format!("deleted provider '{name}'"), None, None);
        json(StatusCode::OK, json!({ "status": "deleted", "name": name }))
    } else {
        err(StatusCode::NOT_FOUND, &format!("provider '{name}' not found"))
    }
}

async fn add_provider_key(state: &ProxyState, provider: &str, req: axum::extract::Request) -> Response {
    let body: AddKeyReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    let auth_header = body.auth_header.filter(|h| !h.trim().is_empty());
    match state.db.add_provider_key(provider, &body.key, auth_header.as_deref()) {
        Ok(()) => {
            state.db.add_log("key", format!("added key for provider '{provider}'"), None, None);
            json(StatusCode::CREATED, json!({ "status": "added", "provider": provider }))
        }
        Err(e) => err(StatusCode::BAD_REQUEST, &e),
    }
}

fn delete_provider_key(state: &ProxyState, provider: &str, key_id: &str) -> Response {
    if state.db.remove_provider_key(key_id) {
        state.db.add_log("key", format!("removed key '{key_id}' from provider '{provider}'"), None, None);
        json(StatusCode::OK, json!({ "status": "deleted", "provider": provider, "key_id": key_id }))
    } else {
        err(StatusCode::NOT_FOUND, "provider key not found")
    }
}

fn extract_model_names(v: &Value) -> Vec<String> {
    match v {
        Value::Array(items) => items
            .iter()
            .filter_map(|m| match m {
                Value::String(s) => Some(s.clone()),
                Value::Object(o) => o.get("name").and_then(|n| n.as_str()).map(String::from),
                _ => None,
            })
            .collect(),
        Value::Object(o) => o
            .get("models")
            .and_then(|m| m.as_array())
            .map(|a| extract_model_names(&Value::Array(a.clone())))
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

async fn import_models(state: &ProxyState, provider: &str, req: axum::extract::Request) -> Response {
    let body: Value = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    let names = extract_model_names(&body);
    if names.is_empty() {
        return err(
            StatusCode::BAD_REQUEST,
            "no models in body; expected {\"models\": [...]} or a JSON array",
        );
    }
    match state.db.import_models(provider, &names) {
        Ok(added) => {
            state.db.add_log(
                "model",
                format!("imported {added} models for provider '{provider}'"),
                None,
                None,
            );
            json(
                StatusCode::OK,
                json!({ "provider": provider, "imported": added, "total": names.len() }),
            )
        }
        Err(e) => err(StatusCode::BAD_REQUEST, &e),
    }
}

// ---- users ----

fn list_users(state: &ProxyState) -> Response {
    let users = state.db.list_users();
    let arr: Vec<Value> = users
        .iter()
        .map(|u| {
            let keys: Vec<Value> = state
                .db
                .list_user_keys(&u.id)
                .iter()
                .map(|k| key_json(k, true))
                .collect();
            json!({
                "id": u.id,
                "username": u.username,
                "tier": u.tier,
                "email": u.email,
                "created_at": u.created_at,
                "keys": keys,
            })
        })
        .collect();
    json(StatusCode::OK, json!({ "users": arr }))
}

/// Hash a password with a fresh random salt, matching the Java admin layer's
/// `sha256:<salt-hex>:<hash-hex>` format.
fn hash_password(password: &str) -> String {
    let mut salt = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut salt);
    let mut hasher = Sha256::new();
    hasher.update(&salt);
    hasher.update(password.as_bytes());
    let hash = hasher.finalize();
    format!("sha256:{}:{}", hex::encode(salt), hex::encode(hash))
}

async fn add_user(state: &ProxyState, req: axum::extract::Request) -> Response {
    let body: AddUserReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    let tier = body.tier.unwrap_or_else(|| "default".to_string());
    let hash = hash_password(&body.password);
    match state.db.add_user(&body.username, &hash, &tier) {
        Ok(()) => {
            state.db.add_log("user", format!("added user '{}'", body.username), None, None);
            json(
                StatusCode::CREATED,
                json!({ "username": body.username, "tier": tier, "status": "created" }),
            )
        }
        Err(e) => err(StatusCode::BAD_REQUEST, &e),
    }
}

fn key_json(k: &UserKeyInfo, mask_value: bool) -> Value {
    json!({
        "id": k.id,
        "key": if mask_value { mask(&k.key) } else { k.key.clone() },
        "tier": k.tier,
        "status": k.status,
        "used": k.used,
        "max_limit": k.max_limit,
        "expires_at": k.expires_at,
    })
}

async fn generate_key(state: &ProxyState, username: &str, req: axum::extract::Request) -> Response {
    let body: GenerateKeyReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    let user = match state.db.get_user_by_name(username) {
        Some(u) => u,
        None => return err(StatusCode::NOT_FOUND, &format!("user '{username}' not found")),
    };
    let tier = body.tier.unwrap_or_else(|| user.tier.clone());
    let max_limit = body.max_limit.unwrap_or(state.config.defaults.default_key_limit as i64);
    let key = format!("pkay_{}", random_hex(24));
    let expiry_days = state.config.defaults.key_expiry_days as i64;
    let expires_at = chrono::Utc::now()
        .naive_utc()
        .checked_add_signed(chrono::Duration::days(expiry_days))
        .map(|t| t.format("%Y-%m-%d %H:%M:%S").to_string());
    match state.db.add_user_key(&user.id, &key, &tier, max_limit, expires_at.clone()) {
        Ok(()) => {
            state.db.add_log("key", format!("generated key for user '{username}'"), None, Some(&user.id));
            json(
                StatusCode::CREATED,
                json!({
                    "key": key,
                    "username": user.username,
                    "tier": tier,
                    "max_limit": max_limit,
                    "expires_at": expires_at,
                }),
            )
        }
        Err(e) => err(StatusCode::BAD_REQUEST, &e),
    }
}

async fn revoke_key(state: &ProxyState, req: axum::extract::Request) -> Response {
    let body: RevokeKeyReq = match json_body(req).await {
        Ok(b) => b,
        Err(r) => return r,
    };
    if state.db.revoke_user_key(&body.key) {
        state.db.add_log("key", "revoked user key", None, None);
        json(StatusCode::OK, json!({ "status": "revoked", "key": mask(&body.key) }))
    } else {
        err(StatusCode::NOT_FOUND, "active key not found")
    }
}

// ---- stats ----

fn stats(state: &ProxyState) -> Response {
    let logs: Vec<Value> = state
        .db
        .recent_logs(20)
        .iter()
        .map(|l: &LogEntry| json!({ "type": l.log_type, "message": l.message, "ip": l.ip, "created_at": l.created_at }))
        .collect();
    json(
        StatusCode::OK,
        json!({
            "users": state.db.count("users"),
            "user_keys": state.db.count("user_keys"),
            "provider_keys": state.db.count("provider_keys"),
            "providers": state.db.count("providers"),
            "total_user_requests": state.db.sum_column("user_keys", "used"),
            "total_provider_requests": state.db.sum_column("provider_keys", "used"),
            "recent_logs": logs,
        }),
    )
}

// ---- helpers ----

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    hex::encode(buf)
}

/// Show only the edges of a secret (e.g. `sk-or-…abcd`) in list responses.
fn mask(key: &str) -> String {
    if key.len() <= 8 {
        return "••••".to_string();
    }
    format!("{}…{}", &key[..4], &key[key.len() - 4..])
}

// ---- router ----

/// Entry point for every `/admin/...` request. Bypasses the proxy pipeline
/// (bot protection / rate limits) — admin access is gated by the session
/// token instead.
pub async fn handle_admin(state: &Arc<ProxyState>, req: axum::extract::Request) -> Response {
    let method = req.method().clone();
    let path = req.uri().path().to_string();
    let segs: Vec<&str> = path.trim_matches('/').split('/').collect();

    match (method.as_str(), segs.as_slice()) {
        ("POST", ["admin", "login"]) => login(state, req).await,
        ("POST", ["admin", "logout"]) => match require_admin(state, req.headers()) {
            Ok(token) => {
                state.admin_sessions.write().unwrap().remove(&token);
                json(StatusCode::OK, json!({ "status": "logged out" }))
            }
            Err(r) => r,
        },
        ("GET", ["admin", "providers"]) => match require_admin(state, req.headers()) {
            Ok(_) => list_providers(state),
            Err(r) => r,
        },
        ("POST", ["admin", "providers"]) => match require_admin(state, req.headers()) {
            Ok(_) => add_provider(state, req).await,
            Err(r) => r,
        },
        ("DELETE", ["admin", "providers", name]) => match require_admin(state, req.headers()) {
            Ok(_) => delete_provider(state, name),
            Err(r) => r,
        },
        ("POST", ["admin", "providers", name, "keys"]) => match require_admin(state, req.headers()) {
            Ok(_) => add_provider_key(state, name, req).await,
            Err(r) => r,
        },
        ("DELETE", ["admin", "providers", name, "keys", key_id]) => match require_admin(state, req.headers()) {
            Ok(_) => delete_provider_key(state, name, key_id),
            Err(r) => r,
        },
        ("POST", ["admin", "providers", name, "models"]) => match require_admin(state, req.headers()) {
            Ok(_) => import_models(state, name, req).await,
            Err(r) => r,
        },
        ("GET", ["admin", "users"]) => match require_admin(state, req.headers()) {
            Ok(_) => list_users(state),
            Err(r) => r,
        },
        ("POST", ["admin", "users"]) => match require_admin(state, req.headers()) {
            Ok(_) => add_user(state, req).await,
            Err(r) => r,
        },
        ("POST", ["admin", "users", username, "keys"]) => match require_admin(state, req.headers()) {
            Ok(_) => generate_key(state, username, req).await,
            Err(r) => r,
        },
        ("POST", ["admin", "keys", "revoke"]) => match require_admin(state, req.headers()) {
            Ok(_) => revoke_key(state, req).await,
            Err(r) => r,
        },
        ("GET", ["admin", "stats"]) => match require_admin(state, req.headers()) {
            Ok(_) => stats(state),
            Err(r) => r,
        },
        _ => err(StatusCode::NOT_FOUND, "unknown admin endpoint"),
    }
}