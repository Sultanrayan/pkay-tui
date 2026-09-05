# API Pooling System — Architecture

This document describes how the implemented system works. It follows the
specification in the root `README.md`.

## Components

```
┌─────────────────────────────┐        ┌──────────────────────────────────┐
│      Java Admin Layer        │        │         Rust Core Layer          │
│  (java-admin, JDK 17)        │        │  (rust-core, proxy-server)       │
│                              │        │                                  │
│  • CLI (one-shot/REPL)       │        │  • Reverse proxy (axum/hyper)    │
│  • TUI (menu driven)         │        │  • Load balancer + failover      │
│  • User & key management     │        │  • Rate limiter (per key/IP)     │
│  • Auth (login + 7-day token)│        │  • Bot protection (IP blocking)  │
│  • Dashboard / system info   │        │  • Health checker (background)   │
└──────────────┬───────────────┘        └───────────────┬──────────────────┘
               │                                         │
               └──────────────┬──────────────────────────┘
                              ▼
                  ┌───────────────────────┐
                  │  SQLite database       │
                  │  database/db.sqlite    │
                  │  (shared, WAL mode)    │
                  └───────────────────────┘
                              │
                              ▼
                   External APIs (Anthropic /
                   OpenAI / Gemini / Grok / any)
```

Both layers operate on the **same SQLite file**, so keys and usage created by
the admin are immediately visible to the proxy and vice versa (request logs and
usage counters written by the proxy show up in the admin dashboard).

## Rust core (`rust-core/`)

| Module            | Responsibility                                                        |
|-------------------|-----------------------------------------------------------------------|
| `main.rs`         | Entry point; loads config, opens DB, spawns health loop, serves HTTP. |
| `proxy.rs`        | Request pipeline, forwarding, failover.                               |
| `load_balancer.rs`| Picks a provider key: round-robin or least-connections.               |
| `rate_limiter.rs` | In-memory windowed counters keyed by IP and user key.                 |
| `bot_protect.rs`  | Blocks IPs exceeding a threshold; whitelist/blacklist.                |
| `health.rs`       | Periodically probes providers; disables unreachable ones.             |
| `config.rs`       | Parses `config.json` (shared with Java).                              |
| `db.rs`           | SQLite access (rusqlite, bundled SQLite).                             |

### Request pipeline (`proxy.rs`)

Every request follows the README's data-flow diagram:

1. **Bot protection** — IP checked against whitelist/blacklist and request
   volume. Over threshold ⇒ blocked for the configured duration (403).
2. **IP rate limit** — token bucket per IP (window + max from config). Over ⇒ 429.
3. **Route** — path `/{provider}/{rest}` selects the provider; the rest is
   appended to the provider's base URL (query string preserved).
4. **User auth** — the user's key is read from `Authorization: Bearer <key>`
   or `X-API-Key`. Checked against `user_keys` (status, expiry). Bad/expired ⇒ 401.
5. **Per-key rate limit** — windowed counter using the key's `max_limit`
   (default from config). Over ⇒ 429.
6. **Load balancing** — active provider keys are collected and one is selected
   (round-robin default, `least_connections` also available).
7. **Forwarding** — request is replayed upstream with the user's credentials
   stripped and the provider key injected as `Authorization: Bearer <provider-key>`.
8. **Failover** — if the upstream returns 401/403/429/5xx, the next key is tried
   (up to 3 attempts or the number of active keys). Client errors (other 4xx)
   are returned to the user as-is.
9. **Response** — upstream status/headers/body are relayed; usage counters and
   logs are written to SQLite.

### Health checking

A background task probes every provider base URL on the configured interval.
Any HTTP response counts as reachable; repeated network failures degrade the
provider's `health` score and, after 3 consecutive failures, set its status to
`inactive` so the load balancer skips it. A successful probe reactivates it.

## Java admin (`java-admin/`)

| Package            | Responsibility                                             |
|--------------------|------------------------------------------------------------|
| `config`           | `ConfigManager` — reads `config.json` (cwd first, then bundled). |
| `db`               | `DatabaseManager` — JDBC/SQLite CRUD + records.            |
| `auth`             | `AuthManager` (salted SHA-256), `TokenManager` (7-day session file). |
| `cli`              | `CLIHandler` (all business logic), `Commands` (help/constants). |
| `tui`              | `TUIMain` menu-driven UI; `screens` reuse CLIHandler output. |

Design notes:

- **Single source of logic**: CLI and TUI both call `CLIHandler.handle(args)`,
  so behavior is identical.
- **Auth**: the admin logs in with a password; a random 32-byte token is stored
  in `.admin_session` with its expiry (7 days by default). Commands that change
  or list data require a valid session.
- **First run**: if no admin user exists, one is created from `config.json`
  (`admin` / `admin` by default — change it in the config file).

## Database

Schema lives in `database/schema.sql` and is created automatically at startup
by both layers. Three pragmatic deviations from the README spec:

- `max_limit` instead of `limit` (LIMIT is a reserved SQL word). Same meaning.
- An extra `models` table so the `add-model` / `import-models` commands have
  somewhere to store models per provider.
- An `auth_header` column on `provider_keys` so providers that do not use
  `Authorization: Bearer` (e.g. Gemini's `x-goog-api-key`) still work.

### Providers and models

Providers are arbitrary third-party endpoints: `add-provider --name X --url Y`
accepts any base URL (typically an OpenAI-compatible reseller API). The admin
owns the model list — either one at a time with `add-model`, or in bulk with
`import-models --provider X --file models.json`, which accepts a JSON array
of names (or `{"name": ...}` objects / a `{"models": [...]}` wrapper) or a
plain text file with one model per line. The models table is informational;
the proxy picks any active key for the provider, and the client chooses the
model in the request body.

Timestamps are stored as `YYYY-MM-DD HH:MM:SS`; IDs are UUID strings.

## Configuration (`config.json`)

One file, shared by both layers:

```jsonc
{
  "proxy":   { "host": "0.0.0.0", "port": 8080,
               "trust_x_forwarded_for": true,
               "load_balancer": "round_robin" },   // or "least_connections"
  "database": { "path": "database/db.sqlite" },
  "admin":   { "username": "admin", "password": "admin",
               "token_duration_days": 7 },
  "defaults": { "key_expiry_days": 30, "default_key_limit": 60 },
  "rate_limits": { "window_seconds": 60, "max_requests_per_ip": 120,
                   "max_requests_per_key": 60 },
  "bot_protection": { "enabled": true, "max_attempts_per_minute": 100,
                      "block_minutes": 60, "whitelist": [], "blacklist": [] },
  "health": { "interval_seconds": 300, "timeout_ms": 5000 }
}
```

The Java app looks for `config.json` in the working directory first, then falls
back to the copy bundled inside the jar (`java-admin/src/main/resources/config.json`).

## Streaming behavior

Responses are **streamed** to the client: as soon as the provider emits data
(chunks, SSE events, chat-completion tokens), it is relayed immediately. Only
retriable error responses (401/403/429/5xx) are buffered, and only so failover
can fall back to the next key with the last error body.

## Known simplifications / future work

- Request bodies are buffered in memory so failover retries can replay them to
  the next provider key. Streaming request bodies (with replay support) is the
  natural next step.
- Rate limits live in memory (per-process); they reset on restart. A Redis or
  DB-backed limiter would scale horizontally.
- Weighted load balancing is not implemented (keys are treated as equal); add a
  weight column and scale the round-robin sequence.
- JNI bindings (`lib.rs` in the README structure) are stubbed: the Java layer
  talks to SQLite directly instead of through the Rust core.
- Health checks probe provider base URLs without credentials; a provider that
  requires auth for every endpoint is still marked healthy on network success.