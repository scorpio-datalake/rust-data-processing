//! Minimal **Phase 3** JVM FFI spike: a tiny stable **C ABI** from a `cdylib`.
//!
//! - **Rust:** no crates.io deps (tiny build).
//! - **Java (Phase 3):** Project Panama (**FFM**, JDK **21+**) via [`jextract`] is **mandatory**
//!   for the shipped product (**Maven + Gradle** builds also **mandatory**—see **`PHASE3_EPICS.md`** /
//!   ADR **003**). This spike uses a small handwritten downcall ahead of codegen against
//!   [`include/rdp_ffi.h`](../../include/rdp_ffi.h); see crate **`README.md`** and
//!   **[`docs/adr/003-jvm-panama-ffi-spike.md`](../../../docs/adr/003-jvm-panama-ffi-spike.md)**.
//!
//! This is **not** the production `rust-data-processing` API; it only proves toolchain + linkage.

/// Return ABI version (**bump** when changing any exported symbol signatures or semantics).
#[no_mangle]
pub extern "C" fn rdp_ffi_abi_version() -> u32 {
    3
}

/// Sum `xs[0..len]` into `out` (i64 accumulator).
///
/// Returns `RDP_FFI_OK` on success or `RDP_FFI_NULL_ARG` if any pointer argument is NULL.
///
/// # Safety
/// `xs` must point to **at least** `len` readable `int32_t` values when non-null and `len > 0`.
#[no_mangle]
pub unsafe extern "C" fn rdp_ffi_sum_i32(xs: *const i32, len: usize, out: *mut i64) -> i32 {
    if out.is_null() {
        return RDP_FFI_NULL_ARG;
    }
    if len == 0 {
        unsafe { out.write(0_i64) };
        return RDP_FFI_OK;
    }
    if xs.is_null() {
        return RDP_FFI_NULL_ARG;
    }
    let slice = unsafe { std::slice::from_raw_parts(xs, len) };
    let mut acc = 0_i64;
    for &v in slice {
        acc += i64::from(v);
    }
    unsafe { out.write(acc) };
    RDP_FFI_OK
}

pub const RDP_FFI_OK: i32 = 0;
pub const RDP_FFI_NULL_ARG: i32 = -1;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_stable() {
        assert_eq!(rdp_ffi_abi_version(), 3);
    }

    #[test]
    fn sum_happy_path() {
        let xs = [100_i32, 200, -50];
        let mut out = 0_i64;
        let rc = unsafe { rdp_ffi_sum_i32(xs.as_ptr(), xs.len(), &mut out) };
        assert_eq!(rc, RDP_FFI_OK);
        assert_eq!(out, 250);
    }

    #[test]
    fn sum_empty_is_zero() {
        let xs: [i32; 0] = [];
        let mut out = 99_i64;
        let rc = unsafe { rdp_ffi_sum_i32(xs.as_ptr(), xs.len(), &mut out) };
        assert_eq!(rc, RDP_FFI_OK);
        assert_eq!(out, 0);
    }

    #[test]
    fn rejects_null_slices_or_out() {
        let xs = [1_i32];
        let mut out = 0_i64;
        assert_eq!(
            unsafe { rdp_ffi_sum_i32(std::ptr::null(), 1, &mut out) },
            RDP_FFI_NULL_ARG
        );
        assert_eq!(
            unsafe { rdp_ffi_sum_i32(xs.as_ptr(), 1, std::ptr::null_mut()) },
            RDP_FFI_NULL_ARG
        );
    }
}
