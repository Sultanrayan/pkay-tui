//! API Pooling System - Rust core library.
//!
//! Exposes the proxy building blocks as a library so they can be reused and,
//! in the future, wrapped with JNI bindings for the Java admin layer
//! (`lib.rs # JNI Bindings` in the README structure). JNI bindings are not
//! implemented yet; add them behind a cargo feature that depends on the `jni`
//! crate when needed.

pub mod bot_protect;
pub mod config;
pub mod db;
pub mod health;
pub mod load_balancer;
pub mod proxy;
pub mod rate_limiter;