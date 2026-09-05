//! In-memory sliding/tumbling window rate limiter, keyed by IP or user key.

use std::collections::HashMap;
use std::time::{Duration, Instant};

struct Bucket {
    window_start: Instant,
    count: u64,
}

pub struct RateLimiter {
    window: Duration,
    buckets: HashMap<String, Bucket>,
}

impl RateLimiter {
    pub fn new(window: Duration) -> Self {
        RateLimiter {
            window,
            buckets: HashMap::new(),
        }
    }

    /// Returns `true` if the request is within the limit, `false` if it should
    /// be rejected with 429. Consumes one unit on success.
    pub fn check_and_consume(&mut self, key: &str, limit: u64) -> bool {
        let now = Instant::now();
        let bucket = self
            .buckets
            .entry(key.to_string())
            .or_insert(Bucket { window_start: now, count: 0 });

        if now.duration_since(bucket.window_start) >= self.window {
            bucket.window_start = now;
            bucket.count = 0;
        }

        if bucket.count >= limit {
            return false;
        }

        bucket.count += 1;

        // Occasional cleanup so the map does not grow without bound.
        if self.buckets.len() > 10_000 {
            self.buckets
                .retain(|_, b| now.duration_since(b.window_start) < self.window);
        }

        true
    }

    pub fn reset(&mut self) {
        self.buckets.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_up_to_limit() {
        let mut rl = RateLimiter::new(Duration::from_secs(60));
        for _ in 0..3 {
            assert!(rl.check_and_consume("ip:1.2.3.4", 3));
        }
        assert!(!rl.check_and_consume("ip:1.2.3.4", 3));
        // different key is unaffected
        assert!(rl.check_and_consume("ip:5.6.7.8", 3));
    }

    #[test]
    fn window_resets() {
        let mut rl = RateLimiter::new(Duration::from_millis(20));
        assert!(rl.check_and_consume("k", 1));
        assert!(!rl.check_and_consume("k", 1));
        std::thread::sleep(Duration::from_millis(40));
        assert!(rl.check_and_consume("k", 1));
    }
}