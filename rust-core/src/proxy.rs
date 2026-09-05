//! Reverse proxy server.
//!
//! Request pipeline (matches the README data-flow diagram):
//!   1. Bot protection  -> check IP (blocked/allowed)
//!   2. Rate limiter    -> check user key and IP
//!   3. Load balancer   -> select the best provider key (round robin / least
//!                         connections) with failover on retriable errors
//!   4. Forward request -> send to provider API with the provider's key
//!   5. Handle response -> return to user, update usage counters and logs
//!
//! Responses are **streamed** back to the client: as soon as the provider
//! emits data (e.g. SSE / chat completions), it is relayed immediately.
//! Request bodies are buffered so failover retries can replay them to the
//! next provider key.

use std::sync::{Arc, RwLock};
use std::time::Duration;

use axum::body::{to_bytes, Body};
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{header, HeaderMap, Method, StatusCode};
use axum::response::Response;

use crate::bot_protect::{BotDecision, BotProtect};
use crate::config::Config;
use crate::db::{Database, ProviderKeyInfo};
use crate::load_balancer::{LbMode, LoadBalancer};
use crate::rate_limiter::RateLimiter;

/// Headers that must not be forwarded between hops.
const HOP_BY_HOP: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
];

pub struct ProxyState {
    pub db: Arc<Database>,
    pub config: Arc<Config>,
    pub client: reqwest::Client,
    pub limiter: RwLock<RateLimiter>,
    pub bot: RwLock<BotProtect>,
    pub lb: RwLock<LoadBalancer>,
}

impl ProxyState {
    pub fn new(db: Arc<Database>, config: Arc<Config>) -> Self {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("failed to build http client");

        let window = Duration::from_secs(config.rate_limits.window_seconds.max(1));
        let limiter = RateLimiter::new(window);
        let bot = BotProtect::new(&config.bot_protection);
        let mode = match config.proxy.load_balancer.as_str() {
            "least_connections" => LbMode::LeastConnections,
            _ => LbMode::RoundRobin,
        };
        let lb = LoadBalancer::new(mode);

        ProxyState {
            db,
            config,
            client,
            limiter: RwLock::new(limiter),
            bot: RwLock::new(bot),
            lb: RwLock::new(lb),
        }
    }
}

pub struct ProxyError {
    pub status: StatusCode,
    pub message: String,
}

impl ProxyError {
    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        ProxyError {
            status,
            message: message.into(),
        }
    }
}

/// Catch-all handler. All requests that reach the server are routed here and
/// handled based on the URL path (`/{provider}/{...}`).
pub async fn proxy_handler(State(state): State<Arc<ProxyState>>, req: Request) -> Response {
    // Liveness endpoint for platform health checks (no auth required).
    if req.uri().path() == "/health" {
        let body = serde_json::json!({"status": "ok"}).to_string();
        return Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body))
            .unwrap();
    }
    match route_request(&state, req).await {
        Ok(resp) => resp,
        Err(e) => {
            let body = serde_json::json!({
                "error": e.message,
                "status": e.status.as_u16()
            })
            .to_string();
            Response::builder()
                .status(e.status)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body))
                .unwrap()
        }
    }
}

async fn route_request(state: &Arc<ProxyState>, req: Request) -> Result<Response, ProxyError> {
    let ip = client_ip(state, &req);

    // 1. Bot protection
    {
        let mut bot = state.bot.write().unwrap();
        if bot.check(&ip) == BotDecision::Blocked {
            drop(bot);
            state.db.add_log("bot_block", format!("blocked ip {ip}"), Some(&ip), None);
            return Err(ProxyError::new(StatusCode::FORBIDDEN, "ip blocked by bot protection"));
        }
    }

    // 2. IP rate limit
    {
        let mut limiter = state.limiter.write().unwrap();
        let limit = state.config.rate_limits.max_requests_per_ip.max(1) as u64;
        if !limiter.check_and_consume(&format!("ip:{ip}"), limit) {
            drop(limiter);
            return Err(ProxyError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "too many requests from this ip",
            ));
        }
    }

    // 3. Parse the path: /{provider}/{rest...}
    let path = req.uri().path().to_string();
    let trimmed = path.trim_matches('/');
    if trimmed.is_empty() {
        return Err(ProxyError::new(StatusCode::BAD_REQUEST, "missing provider in path"));
    }
    let mut segments = trimmed.split('/');
    let provider_name = segments.next().unwrap().to_string();
    let rest = segments.collect::<Vec<_>>().join("/");
    let rest_path = if rest.is_empty() {
        String::new()
    } else {
        format!("/{rest}")
    };

    // 4. Authenticate the user key
    let user_key_value = extract_user_key(&req)
        .ok_or_else(|| ProxyError::new(StatusCode::UNAUTHORIZED, "missing api key"))?;
    let key_info = state
        .db
        .get_user_key(&user_key_value)
        .ok_or_else(|| ProxyError::new(StatusCode::UNAUTHORIZED, "invalid api key"))?;
    if key_info.status != "active" {
        return Err(ProxyError::new(StatusCode::UNAUTHORIZED, "api key is not active"));
    }
    if let Some(exp) = &key_info.expires_at {
        if is_expired(exp) {
            return Err(ProxyError::new(StatusCode::UNAUTHORIZED, "api key expired"));
        }
    }

    // 5. Per-key rate limit
    {
        let mut limiter = state.limiter.write().unwrap();
        let limit = if key_info.max_limit > 0 {
            key_info.max_limit as u64
        } else {
            state.config.rate_limits.max_requests_per_key as u64
        };
        if !limiter.check_and_consume(&format!("key:{}", key_info.id), limit) {
            return Err(ProxyError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "user key rate limit exceeded",
            ));
        }
    }

    // 6. Resolve the provider
    let provider = state.db.get_provider_by_name(&provider_name).ok_or_else(|| {
        ProxyError::new(StatusCode::NOT_FOUND, format!("provider '{provider_name}' not found"))
    })?;
    if provider.status != "active" {
        return Err(ProxyError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            format!("provider '{provider_name}' is not active"),
        ));
    }

    // 7. Load balancer candidates (active provider keys)
    let keys = state.db.list_provider_keys(&provider.name);
    let candidates: Vec<&ProviderKeyInfo> = keys.iter().filter(|k| k.status == "active").collect();
    if candidates.is_empty() {
        return Err(ProxyError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            format!("no active keys for provider '{provider_name}'"),
        ));
    }

    // 8. Extract what we need from the request, then read the body once so
    //    failover retries can reuse it
    let method = req.method().clone();
    let req_headers = req.headers().clone();
    let query = req
        .uri()
        .query()
        .map(|q| format!("?{q}"))
        .unwrap_or_default();

    let body_bytes = to_bytes(req.into_body(), usize::MAX)
        .await
        .map_err(|e| ProxyError::new(StatusCode::BAD_REQUEST, format!("failed to read body: {e}")))?;

    // 9. Build the upstream target URL
    let base_url = provider.base_url.trim_end_matches('/');
    let target = format!("{base_url}{rest_path}{query}");

    let user_id = key_info.user_id.clone();
    let key_info_id = key_info.id.clone();

    // 10. Forward with failover (up to 3 attempts, or one per active key)
    let max_attempts = candidates.len().min(3);
    let candidate_ids: Vec<String> = candidates.iter().map(|k| k.id.clone()).collect();
    let mut last_resp: Option<Response> = None;
    let mut attempted = 0;

    for _ in 0..max_attempts {
        if attempted >= max_attempts {
            break;
        }
        attempted += 1;

        let key_id = {
            let mut lb = state.lb.write().unwrap();
            lb.select(&provider.name, &candidate_ids).cloned()
        };
        let Some(key_id) = key_id else { break };
        let key_entry = candidates.iter().find(|k| k.id == key_id).unwrap();

        match send_upstream(state, key_entry, method.clone(), &target, &body_bytes, &req_headers)
            .await
        {
            Ok(upstream) => {
                let status = upstream.status();
                if retriable_status(status) {
                    state.db.mark_provider_key_failure(&key_entry.id);
                    state.db.add_log(
                        "request_fail",
                        format!("{method} {target} -> {status}"),
                        Some(&ip),
                        Some(&user_id),
                    );
                    // Buffer the (small) error body so it can be returned if
                    // every key fails.
                    let headers = upstream.headers().clone();
                    let bytes = upstream.bytes().await.unwrap_or_default();
                    last_resp = Some(response_with_body(status, &headers, Body::from(bytes)));
                    continue;
                }
                state.db.mark_user_key_used(&key_info_id);
                if status.is_success() {
                    state.db.mark_provider_key_success(&key_entry.id);
                }
                state.db.add_log(
                    "request",
                    format!("{status} {method} {target}"),
                    Some(&ip),
                    Some(&user_id),
                );
                // Stream the upstream body to the client so chat/SSE responses
                // start flowing as soon as the provider emits data.
                let headers = upstream.headers().clone();
                return Ok(response_with_body(
                    status,
                    &headers,
                    Body::from_stream(upstream.bytes_stream()),
                ));
            }
            Err(e) => {
                state.db.mark_provider_key_failure(&key_entry.id);
                state.db.add_log(
                    "request_fail",
                    format!("{method} {target}: {e}"),
                    Some(&ip),
                    Some(&user_id),
                );
                continue;
            }
        }
    }

    if let Some(resp) = last_resp {
        state.db.mark_user_key_used(&key_info_id);
        Ok(resp)
    } else {
        Err(ProxyError::new(
            StatusCode::BAD_GATEWAY,
            "all provider keys failed",
        ))
    }
}

/// Send a single request to the provider, replacing the user's credentials
/// with the provider's key. Returns the raw upstream response so the caller
/// can decide whether to stream it or buffer it (for retriable errors).
async fn send_upstream(
    state: &ProxyState,
    key: &ProviderKeyInfo,
    method: Method,
    target: &str,
    body: &[u8],
    req_headers: &HeaderMap,
) -> Result<reqwest::Response, String> {
    let mut rb = state.client.request(method, target);

    for (name, value) in req_headers {
        let name_str = name.as_str();
        if HOP_BY_HOP.contains(&name_str)
            || name_str.eq_ignore_ascii_case("authorization")
            || name_str.eq_ignore_ascii_case("x-api-key")
            || name_str.eq_ignore_ascii_case("content-length")
        {
            continue;
        }
        rb = rb.header(name, value);
    }

    // Inject the provider's credentials. Most providers accept
    // `Authorization: Bearer <key>`; some (e.g. Gemini) expect a dedicated
    // header such as `x-goog-api-key`, configured via the key's auth_header.
    match key.auth_header.as_deref() {
        Some(h) if !h.is_empty() && !h.eq_ignore_ascii_case("authorization") => {
            rb = rb.header(h, &key.key);
        }
        _ => {
            rb = rb.header(header::AUTHORIZATION, format!("Bearer {}", key.key));
        }
    }
    if !req_headers.contains_key(header::CONTENT_TYPE) {
        rb = rb.header(header::CONTENT_TYPE, "application/json");
    }
    rb = rb.body(body.to_vec());

    rb.send().await.map_err(|e| format!("upstream error: {e}"))
}

/// Build an axum response from upstream status/headers and a body, which may
/// be an already-buffered `Bytes` (retriable error fallbacks) or a live
/// stream (successful responses).
fn response_with_body(status: StatusCode, headers: &HeaderMap, body: Body) -> Response {
    let mut builder = Response::builder().status(status);
    if let Some(h) = builder.headers_mut() {
        copy_headers(h, headers);
    }
    builder.body(body).unwrap()
}

/// Copy relayable upstream headers, dropping hop-by-hop headers only.
/// `Content-Length` is preserved: the relayed body is byte-identical (reqwest
/// does not decompress by default), so the length stays accurate.
fn copy_headers(dst: &mut HeaderMap, src: &HeaderMap) {
    for (name, value) in src {
        let name_str = name.as_str();
        if HOP_BY_HOP.contains(&name_str) {
            continue;
        }
        dst.insert(name, value.clone());
    }
}

/// The user sends their key either as `Authorization: Bearer <key>` or
/// `X-API-Key: <key>`.
fn extract_user_key(req: &Request) -> Option<String> {
    let hdrs = req.headers();
    if let Some(v) = hdrs.get("x-api-key") {
        if let Ok(s) = v.to_str() {
            return Some(s.trim().to_string());
        }
    }
    if let Some(v) = hdrs.get(header::AUTHORIZATION) {
        if let Ok(s) = v.to_str() {
            for prefix in ["Bearer ", "bearer "] {
                if let Some(k) = s.strip_prefix(prefix) {
                    return Some(k.trim().to_string());
                }
            }
        }
    }
    None
}

fn client_ip(state: &ProxyState, req: &Request) -> String {
    if state.config.proxy.trust_x_forwarded_for {
        if let Some(v) = req.headers().get("x-forwarded-for") {
            if let Ok(s) = v.to_str() {
                if let Some(first) = s.split(',').next() {
                    return first.trim().to_string();
                }
            }
        }
    }
    req.extensions()
        .get::<ConnectInfo<std::net::SocketAddr>>()
        .map(|ci| ci.ip().to_string())
        .unwrap_or_else(|| "unknown".to_string())
}

/// Errors worth failing over to another key: auth failures, rate limits and
/// server errors. Client errors (4xx except 401/403/429) are returned as-is.
fn retriable_status(status: StatusCode) -> bool {
    status.is_server_error()
        || status == StatusCode::TOO_MANY_REQUESTS
        || status == StatusCode::UNAUTHORIZED
        || status == StatusCode::FORBIDDEN
}

fn is_expired(exp: &str) -> bool {
    chrono::NaiveDateTime::parse_from_str(exp, "%Y-%m-%d %H:%M:%S")
        .map(|t| chrono::Utc::now().naive_utc() > t)
        .unwrap_or(false)
}