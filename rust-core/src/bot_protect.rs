//! Simple bot protection: blocks IPs that exceed a request threshold within a
//! window, plus explicit whitelist/blacklist support.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use crate::config::BotProtectionConfig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BotDecision {
    Allow,
    Blocked,
}

pub struct BotProtect {
    enabled: bool,
    max_attempts: u64,
    window: Duration,
    block_for: Duration,
    whitelist: HashSet<String>,
    blacklist: HashSet<String>,
    /// ip -> (window start, hit count)
    hits: HashMap<String, (Instant, u64)>,
    /// ip -> time until which it stays blocked
    blocked_until: HashMap<String, Instant>,
    last_cleanup: Instant,
}

impl BotProtect {
    pub fn new(config: &BotProtectionConfig) -> Self {
        BotProtect {
            enabled: config.enabled,
            max_attempts: config.max_attempts_per_minute.max(1) as u64,
            window: Duration::from_secs(60),
            block_for: Duration::from_secs(config.block_minutes.max(1) * 60),
            whitelist: config.whitelist.iter().cloned().collect(),
            blacklist: config.blacklist.iter().cloned().collect(),
            hits: HashMap::new(),
            blocked_until: HashMap::new(),
            last_cleanup: Instant::now(),
        }
    }

    pub fn check(&mut self, ip: &str) -> BotDecision {
        if !self.enabled {
            return BotDecision::Allow;
        }
        if self.whitelist.contains(ip) {
            return BotDecision::Allow;
        }
        if self.blacklist.contains(ip) {
            return BotDecision::Blocked;
        }

        let now = Instant::now();

        if let Some(until) = self.blocked_until.get(ip) {
            if now < *until {
                return BotDecision::Blocked;
            }
            self.blocked_until.remove(ip);
        }

        let entry = self
            .hits
            .entry(ip.to_string())
            .or_insert((now, 0));
        if now.duration_since(entry.0) >= self.window {
            entry.0 = now;
            entry.1 = 0;
        }
        entry.1 += 1;

        if entry.1 > self.max_attempts {
            self.blocked_until.insert(ip.to_string(), now + self.block_for);
            return BotDecision::Blocked;
        }

        // Periodic cleanup of stale entries.
        if now.duration_since(self.last_cleanup) > Duration::from_secs(300) {
            self.hits
                .retain(|_, (t, _)| now.duration_since(*t) < self.window);
            self.blocked_until.retain(|_, t| now < *t);
            self.last_cleanup = now;
        }

        BotDecision::Allow
    }

    pub fn is_blocked(&self, ip: &str) -> bool {
        self.blocked_until
            .get(ip)
            .map(|until| Instant::now() < *until)
            .unwrap_or(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> BotProtectionConfig {
        BotProtectionConfig {
            enabled: true,
            max_attempts_per_minute: 3,
            block_minutes: 1,
            whitelist: vec![],
            blacklist: vec![],
        }
    }

    #[test]
    fn blocks_after_threshold() {
        let mut bp = BotProtect::new(&config());
        assert_eq!(bp.check("1.2.3.4"), BotDecision::Allow);
        assert_eq!(bp.check("1.2.3.4"), BotDecision::Allow);
        assert_eq!(bp.check("1.2.3.4"), BotDecision::Allow);
        assert_eq!(bp.check("1.2.3.4"), BotDecision::Blocked);
        assert!(bp.is_blocked("1.2.3.4"));
    }

    #[test]
    fn blacklist_wins() {
        let mut cfg = config();
        cfg.blacklist.push("9.9.9.9".to_string());
        let mut bp = BotProtect::new(&cfg);
        assert_eq!(bp.check("9.9.9.9"), BotDecision::Blocked);
    }
}