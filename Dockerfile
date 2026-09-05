# Build stage
FROM rust:1-slim AS builder
# pkg-config + libssl-dev are required by openssl-sys (reqwest default-tls)
RUN apt-get update \
    && apt-get install -y --no-install-recommends pkg-config libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY rust-core/Cargo.toml rust-core/Cargo.lock ./rust-core/
COPY rust-core/src ./rust-core/src
RUN cd rust-core && cargo build --release

# Runtime stage
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/rust-core/target/release/proxy-server /app/proxy-server
COPY config.json /app/config.json
EXPOSE 8080
CMD ["./proxy-server", "--config", "config.json"]