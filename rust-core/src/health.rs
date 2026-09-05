//! Background health checking of configured providers.
//!
//! Periodically probes each provider base URL. Any HTTP response (even an
//! error status) proves the endpoint is reachable; repeated network failures
//! mark the provider inactive so the load balancer stops selecting it.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use crate::config::Config;
use crate::db::Database;

pub async fn run_health_check_loop(db: Arc<Database>, config: Arc<Config>) {
    let interval = Duration::from_secs(config.health.interval_seconds.max(5));
    let timeout = Duration::from_millis(config.health.timeout_ms.max(1000));

    let client = match reqwest::Client::builder().timeout(timeout).build() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[health] failed to build http client: {e}");
            return;
        }
    };

    let mut failures: HashMap<String, u32> = HashMap::new();
    let mut ticker = tokio::time::interval(interval);

    loop {
        ticker.tick().await;
        for provider in db.list_providers() {
            let reachable = check_provider(&client, &provider.base_url).await;
            let fail_count = failures.entry(provider.name.clone()).or_insert(0);
            if reachable {
                *fail_count = 0;
                db.set_provider_status(&provider.name, "active");
                db.set_provider_health(&provider.name, 100);
            } else {
                *fail_count += 1;
                let health = (100_i64 - (*fail_count as i64) * 20).max(0);
                db.set_provider_health(&provider.name, health);
                if *fail_count >= 3 {
                    db.set_provider_status(&provider.name, "inactive");
                }
            }
        }
    }
}

async fn check_provider(client: &reqwest::Client, base_url: &str) -> bool {
    match client.get(base_url).send().await {
        Ok(_) => true,
        Err(_) => false,
    }
}