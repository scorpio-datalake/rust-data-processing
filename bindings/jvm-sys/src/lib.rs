//! JVM-facing **`cdylib`** for Maven + Gradle bindings (**Project Panama**, **`jextract`**).
//!
//! **`Planning/PHASE3_EPICS.md`** tracks JVM parity with **`rust-data-processing`**.
#![allow(clippy::missing_safety_doc)]

#[cfg(feature = "link-main")]
use rust_data_processing as _;

mod ingest_path;
mod kafka;
mod parity;
mod parity_mirrors;
mod parity_support;
mod pipeline_run;

/// Bump only when ABI / calling conventions for exported symbols break.
#[no_mangle]
pub extern "C" fn rdp_ffi_abi_version() -> u32 {
    406
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_constant() {
        assert_eq!(rdp_ffi_abi_version(), 406);
    }
}
