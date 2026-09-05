mod bot_protect;
mod config;
mod db;
mod health;
mod load_balancer;
mod proxy;
mod rate_limiter;

use std::sync::Arc;

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut config_path = "config.json".to_string();
    let mut port_override: Option<u16> = None;

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--config" | "-c" => {
                i += 1;
                if let Some(v) = args.get(i) {
                    config_path = v.clone();
                }
            }
            "--port" | "-p" => {
                i += 1;
                if let Some(v) = args.get(i) {
                    if let Ok(p) = v.parse() {
                        port_override = Some(p);
                    }
                }
            }
            "--help" | "-h" => {
                print_help();
                return;
            }
            _ => {}
        }
        i += 1;
    }

    let config = Arc::new(config::Config::load(&config_path));

    // Allow the database location to come from the environment (e.g. a
    // persistent volume mounted by the platform).
    let db_path = std::env::var("DATABASE_PATH").unwrap_or_else(|_| config.database.path.clone());

    let db = match db::Database::new(&db_path) {
        Ok(d) => Arc::new(d),
        Err(e) => {
            eprintln!("[db] failed to open database '{db_path}': {e}");
            std::process::exit(1);
        }
    };

    let state = Arc::new(proxy::ProxyState::new(db.clone(), config.clone()));

    // Background health checker
    let hc_config = config.clone();
    let hc_db = db.clone();
    tokio::spawn(async move {
        health::run_health_check_loop(hc_db, hc_config).await;
    });

    // Railway/Heroku-style platforms pass the listening port via $PORT.
    let port = port_override
        .or_else(|| std::env::var("PORT").ok().and_then(|p| p.parse().ok()))
        .unwrap_or(config.proxy.port);
    let addr = format!("{}:{}", config.proxy.host, port);

    let app = axum::Router::new()
        .fallback(proxy::proxy_handler)
        .with_state(state)
        .into_make_service_with_connect_info::<std::net::SocketAddr>();

    let listener = match tokio::net::TcpListener::bind(&addr).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[proxy] failed to bind {addr}: {e}");
            std::process::exit(1);
        }
    };

    println!("[proxy] API Pooling System proxy listening on http://{addr}");
    println!("[proxy] database: {db_path}");
    println!("[proxy] load balancer: {}", config.proxy.load_balancer);
    println!("[proxy] bot protection: {}",
        if config.bot_protection.enabled { "enabled" } else { "disabled" });

    if let Err(e) = axum::serve(listener, app).await {
        eprintln!("[proxy] server error: {e}");
    }
}

fn print_help() {
    println!("Usage: proxy-server [--config <path>] [--port <port>]");
    println!("");
    println!("Options:");
    println!("  -c, --config <path>   Path to config.json (default: config.json)");
    println!("  -p, --port <port>     Override the listening port");
    println!("  -h, --help            Show this help");
}