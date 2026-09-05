//! Load balancer for selecting provider keys.
//!
//! Supports round-robin (default) and least-connections modes. Weighted mode
//! can be layered on top by mapping a per-key weight onto the round-robin
//! sequence; for now all keys are treated as equal weight.

use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LbMode {
    RoundRobin,
    LeastConnections,
}

pub struct LoadBalancer {
    mode: LbMode,
    /// provider -> next round-robin index
    rr_index: HashMap<String, usize>,
    /// key id -> number of in-flight requests
    in_flight: HashMap<String, usize>,
}

impl LoadBalancer {
    pub fn new(mode: LbMode) -> Self {
        LoadBalancer {
            mode,
            rr_index: HashMap::new(),
            in_flight: HashMap::new(),
        }
    }

    /// Select one candidate from `candidates` (provider key ids).
    pub fn select<'a>(&mut self, provider: &str, candidates: &'a [String]) -> Option<&'a String> {
        if candidates.is_empty() {
            return None;
        }
        match self.mode {
            LbMode::RoundRobin => {
                let index = self.rr_index.entry(provider.to_string()).or_insert(0);
                let idx = *index % candidates.len();
                *index += 1;
                Some(&candidates[idx])
            }
            LbMode::LeastConnections => {
                let mut best = 0;
                let mut best_count = usize::MAX;
                for (idx, c) in candidates.iter().enumerate() {
                    let count = self.in_flight.get(c).copied().unwrap_or(0);
                    if count < best_count {
                        best_count = count;
                        best = idx;
                    }
                }
                Some(&candidates[best])
            }
        }
    }

    pub fn track_start(&mut self, key_id: &str) {
        *self.in_flight.entry(key_id.to_string()).or_insert(0) += 1;
    }

    pub fn track_end(&mut self, key_id: &str) {
        if let Some(c) = self.in_flight.get_mut(key_id) {
            if *c > 0 {
                *c -= 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_robin_cycles() {
        let mut lb = LoadBalancer::new(LbMode::RoundRobin);
        let candidates = vec!["a".to_string(), "b".to_string()];
        assert_eq!(lb.select("p", &candidates).unwrap(), "a");
        assert_eq!(lb.select("p", &candidates).unwrap(), "b");
        assert_eq!(lb.select("p", &candidates).unwrap(), "a");
        // independent per provider
        let other = vec!["x".to_string()];
        assert_eq!(lb.select("q", &other).unwrap(), "x");
    }

    #[test]
    fn least_connections_picks_idle() {
        let mut lb = LoadBalancer::new(LbMode::LeastConnections);
        let candidates = vec!["a".to_string(), "b".to_string()];
        lb.track_start("a");
        lb.track_start("a");
        assert_eq!(lb.select("p", &candidates).unwrap(), "b");
        lb.track_end("a");
        assert_eq!(lb.select("p", &candidates).unwrap(), "b");
    }
}