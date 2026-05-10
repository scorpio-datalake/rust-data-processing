# `rdp-jvm-sys` — JVM `cdylib`

Maven + Gradle load this library via **Panama**. Header: **`include/rdp_jvm_sys.h`** (`cbindgen.toml` documents regen). **`ffi_manifest.json`** pairs with **`scripts/check_jvm_ffi_manifest.py`** in CI.

## Build

```bash
# Default — small graph (no Polars link unless features enabled)
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml
cargo test  --manifest-path bindings/jvm-sys/Cargo.toml

# Phase 3 pipeline (`jvm_ffi` aliases `link-main`)
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features jvm_ffi

# Full linkage (Polars default stack; crates.io-parity builds).
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
```

Output: **`bindings/jvm-sys/target/release/`**
