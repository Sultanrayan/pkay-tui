# API Pooling System

**High-performance API key management and reverse proxy for resellers.**

Pool API keys from third-party providers, issue keys to your own users, balance
traffic across keys, and protect the system from bots and abuse — all through a
fast Rust proxy core managed by a Java CLI/TUI admin layer.

```
┌──────────────┐      ┌───────────────────────┐      ┌──────────────────┐
│  Your users  │ ───► │   Rust Proxy Core     │ ───► │ Third-party API  │
│  (AI clients)│      │  auth · rate limit    │      │ providers        │
│              │      │  bot protect · pool   │      │ (OpenAI-compat)  │
└──────────────┘      │  load balance · stream│      └──────────────────┘
                      └───────────┬───────────┘
                                  │
┌──────────────┐      ┌───────────▼───────────┐
│  Admin       │      │   Shared SQLite DB    │
│  Java CLI/TUI│ ◄──► │  users · keys · logs  │
└──────────────┘      └───────────────────────┘
```

## Features

- **Reverse proxy** — clients call `/{provider}/{path}` with their own key; the
  proxy forwards to the provider with the provider's key (never exposed).
- **API pooling & load balancing** — round-robin or least-connections across
  active provider keys, with automatic **failover** on 401/403/429/5xx.
- **Streaming responses** — SSE / chat-completion tokens are relayed to the
  client as they arrive; no buffering.
- **Rate limiting** — configurable windowed limits per user key and per IP.
- **Bot protection** — blocks IPs that exceed a request threshold; whitelist /
  blacklist support.
- **Health checks** — background probes mark unreachable providers inactive.
- **Third-party providers** — any OpenAI-compatible base URL works; per-key
  auth headers (e.g. `x-goog-api-key`) supported via `--auth-header`.
- **Your own model list** — add models one at a time or bulk-upload a list from
  a JSON / text file.
- **Admin CLI + TUI** — user & key management, provider setup, dashboard,
  system info, audit logs.
- **Usage tracking** — per-key and per-provider usage counters plus request
  logs, shared between proxy and admin via one SQLite database.

## Tech stack

| Layer    | Technology |
|----------|------------|
| Proxy core | Rust · axum · reqwest · rusqlite (SQLite) |
| Admin layer | Java 17 · Maven · sqlite-jdbc · Gson |
| Database  | SQLite (WAL mode), schema in `database/schema.sql` |
| Deployment| Dockerfile · Railway (or any container host) |

## Getting started

### Prerequisites

- **Rust** 1.70+ (cargo) — build the proxy
- **JDK 17+** and **Maven 3.8+** — build the admin layer

### Build

```bash
# Rust proxy core
cd rust-core && cargo build --release

# Java admin layer (produces java-admin/target/api-pool-admin.jar)
cd ../java-admin && mvn clean package
```

### Run

```bash
# Terminal 1 — proxy server
./rust-core/target/release/proxy-server --config config.json

# Terminal 2 — admin CLI (or --tui for the menu-driven interface)
java -jar java-admin/target/api-pool-admin.jar --cli
```

### First-time setup

The default admin (`admin` / `admin`) is created from `config.json` on first
run. Then add a provider, upload your model list, add a key, and issue a user
key:

```bash
J="java -jar java-admin/target/api-pool-admin.jar"

$J login --username admin --password admin
$J add-provider --name openrouter --url https://openrouter.ai/api/v1
$J import-models --provider openrouter --file database/models.json.example
$J add-key --provider openrouter --key sk-or-YOUR_KEY
$J add-user --username alice --password secret --tier premium
$J generate-key --user alice --tier premium     # prints pkay_...
```

Your user can now call the proxy:

```bash
curl -N -X POST http://localhost:8080/openrouter/v1/chat/completions \
  -H "Authorization: Bearer pkay_YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

## Admin CLI reference

| Command | Description |
|---------|-------------|
| `login --username <user> --password <pass>` | Login (session token valid 7 days) |
| `logout` | Logout |
| `add-provider --name <name> --url <url>` | Add a (third-party) provider |
| `add-model --provider <name> --model <model>` | Add one model to a provider |
| `import-models --provider <name> --file <path>` | Bulk-upload models (JSON or one-per-line) |
| `add-key --provider <name> --key <api-key> [--model <m>] [--auth-header <h>]` | Add a provider key |
| `remove-provider-key --key <api-key>` | Remove a provider key |
| `list-providers` / `list-provider-keys` | List providers / provider keys |
| `add-user --username <u> --password <p> [--email <e>] [--tier <t>]` | Create a user |
| `list-users` / `set-tier` / `delete-user` | Manage users |
| `generate-key --user <user_id> --tier <tier>` | Generate a user key (`pkay_...`, 30-day expiry by default) |
| `list-keys` | List all user keys with usage |
| `revoke-key --key <key>` | Revoke a user key |
| `system-info` / `dashboard` / `logs` | Status, dashboard, audit logs |
| `help` | Full help text |

## Proxy API

**Endpoint format:** `http://<host>:<port>/<provider>/<path>?<query>` — the
remaining path and query are appended to the provider's base URL.

**Authentication:** user keys via `Authorization: Bearer <key>` or
`X-API-Key: <key>`. The proxy replaces them with the provider's key upstream.

**Streaming:** responses are relayed live — SSE/chat streams start immediately.

| Code | Meaning |
|------|---------|
| 200 | Success (body streamed from provider) |
| 400 / 404 | Missing provider in path / unknown provider |
| 401 | Missing, invalid, expired or revoked user key |
| 403 | IP blocked by bot protection |
| 429 | Rate limit exceeded (per IP or per user key) |
| 502 / 503 | All provider keys failed / provider inactive or no keys |

`GET /health` (no auth) returns `{"status":"ok"}` for platform health checks.

## Configuration

One shared `config.json` (working directory, with a bundled fallback in the
jar):

```jsonc
{
  "proxy":   { "host": "0.0.0.0", "port": 8080, "trust_x_forwarded_for": true,
               "load_balancer": "round_robin" },        // or "least_connections"
  "database": { "path": "database/db.sqlite" },
  "admin":   { "username": "admin", "password": "admin", "token_duration_days": 7 },
  "defaults": { "key_expiry_days": 30, "default_key_limit": 60 },
  "rate_limits": { "window_seconds": 60, "max_requests_per_ip": 120,
                   "max_requests_per_key": 60 },
  "bot_protection": { "enabled": true, "max_attempts_per_minute": 100,
                      "block_minutes": 60, "whitelist": [], "blacklist": [] },
  "health": { "interval_seconds": 300, "timeout_ms": 5000 }
}
```

## Deployment

The repo ships a `Dockerfile` and `railway.json`. The proxy is deployed as a
container — currently live at **https://pkay-tui-production.up.railway.app**
with a persistent volume (`pkay-tui-volume` at `/data`) and
`DATABASE_PATH=/data/db.sqlite`.

| Variable | Purpose |
|----------|---------|
| `PORT` | Listening port (Railway sets it automatically) |
| `DATABASE_PATH` | SQLite file location (e.g. `/data/db.sqlite` for a volume) |
| `ADMIN_USERNAME` | Admin username for the `/admin` HTTP API (default `admin`) |
| `ADMIN_PASSWORD` | Admin password for the `/admin` HTTP API (default `admin` — set this in production) |

The deployed instance is managed remotely through the **admin HTTP API**
(`POST /admin/login`, then `POST /admin/providers`, `POST /admin/users`,
`POST /admin/users/<name>/keys`, … — full reference in `docs/api.md` §3). It
keeps its own database on the volume, separate from the local CLI's.

```bash
railway init --name <project>
railway up -y -d --service <service>
railway domain          # get the public URL
```

## Project structure

```
├── rust-core/               # Rust proxy core
│   ├── src/
│   │   ├── main.rs          # entry point (args, PORT/DATABASE_PATH env)
│   │   ├── proxy.rs         # request pipeline, streaming, failover
│   │   ├── load_balancer.rs # round-robin / least-connections
│   │   ├── rate_limiter.rs  # windowed per-key / per-IP limits
│   │   ├── bot_protect.rs   # IP blocking + whitelist/blacklist
│   │   ├── health.rs        # background provider probing
│   │   ├── db.rs            # SQLite access (shared schema)
│   │   └── config.rs        # config.json parsing
│   └── Cargo.toml
├── java-admin/              # Java admin layer
│   └── src/main/java/com/api/pool/
│       ├── Main.java        # entry point (--cli / --tui / commands)
│       ├── cli/             # CLIHandler (all logic) + Commands
│       ├── tui/             # TUIMain + screens
│       ├── auth/            # AuthManager + TokenManager (7-day sessions)
│       ├── db/              # DatabaseManager (JDBC/SQLite)
│       └── config/          # ConfigManager
├── database/
│   ├── schema.sql           # shared schema (users, keys, providers, logs)
│   ├── data.json            # example seed data (third-party providers)
│   └── models.json.example  # example model list for import-models
├── scripts/mock_server.py   # local mock provider (incl. /sse for streaming)
├── docs/                    # architecture.md · api.md
├── Dockerfile               # container build for the proxy
└── railway.json             # Railway deployment config
```

## Testing

```bash
cd rust-core && cargo test      # proxy core unit tests
cd java-admin && mvn test       # admin layer unit tests
```

A full end-to-end check (auth, forwarding, streaming, load balancing) can be
run locally with `scripts/mock_server.py` — see `docs/api.md`.

## Security

- Admin login with salted SHA-256 passwords and 7-day session tokens.
- User keys expire after 30 days by default and can be revoked.
- Per-key / per-IP rate limiting and IP-based bot protection.
- User keys are never forwarded upstream; provider keys are injected server-side.

> Note: provider API keys are stored in the database in plaintext — encrypting
> them at rest is on the roadmap.

## Roadmap

- Admin HTTP API so the deployed instance can be managed remotely.
- Postgres / Redis backend for horizontal scaling.
- Weighted load balancing and streaming request bodies.
- Encryption of stored provider keys.

## License

MIT License — free for educational and research purposes. Commercial use
requires permission.

## Author

**Sultan Ryan (السلطان ريان)** — [GitHub](https://github.com/Sultanrayan)