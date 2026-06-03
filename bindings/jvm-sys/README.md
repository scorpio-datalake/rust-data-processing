# `rdp-jvm-sys` — JVM `cdylib`

Maven + Gradle load this library via **Panama**. Header: **`include/rdp_jvm_sys.h`** (`cbindgen.toml` documents regen). **`ffi_manifest.json`** pairs with **`scripts/check_jvm_ffi_manifest.py`** in CI.

## Build

```bash
# Default — small graph (no Polars link unless features enabled)
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml
cargo test  --manifest-path bindings/jvm-sys/Cargo.toml

# Phase 3 pipeline (`jvm_ffi` aliases `link-main`)
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features jvm_ffi

# Full linkage — every batch connector in docs/CONNECTORS.md (`--features full`).
# CI validates the feature set via scripts/check_jvm_full_features.py.
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full

# Kafka streaming ELT (add to full on Linux in CI):
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full,kafka
```

Output: **`bindings/jvm-sys/target/release/`**
