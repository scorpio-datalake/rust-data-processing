# Arrow FFI / IPC — JVM milestone (**P3-E1-S1d**)

**Status:** Scaffold — **no Arrow payloads cross FFI yet** (ABI delivers **`rdp_ffi_abi_version` only**).

## Planned contract

1. **Preferred:** **Arrow C Data Interface** (`ArrowArray` / `ArrowSchema`) pointers with explicit **release callback** registered from Rust.
2. **Fallback:** **Arrow IPC stream** bytes (`UInt8` heap) + length — extra copy; acceptable for narrow prototypes.

## Ownership checklist (before GA)

- [ ] Document **who calls `release`** for each exported handle.
- [ ] **`Arena`** nesting rules for Panama callers (no segments escaping scope).
- [ ] Align Rust **`arrow`** crate feature flags with **`rdp-jvm-sys`** **`full`** builds.

See **ADR 005** for policy; **P3-E2-Conn** for Kafka batch shapes feeding the same boundary.
