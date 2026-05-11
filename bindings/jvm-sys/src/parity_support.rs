//! Shared UTF‑8 JSON slice helpers for JVM FFI (`parity.rs`, `parity_mirrors.rs`).

#[repr(C)]
pub struct RdpJsonSlice {
    pub ptr: *mut u8,
    pub len: usize,
    pub cap: usize,
}

pub unsafe fn write_slice(out: *mut RdpJsonSlice, slice: RdpJsonSlice) {
    if out.is_null() {
        rdp_json_slice_free(slice);
        return;
    }
    unsafe {
        out.write(slice);
    }
}

pub fn vec_to_slice(v: Vec<u8>) -> RdpJsonSlice {
    let mut v = v;
    let ptr = v.as_mut_ptr();
    let len = v.len();
    let cap = v.capacity();
    std::mem::forget(v);
    RdpJsonSlice { ptr, len, cap }
}

pub fn json_ok(interchange: serde_json::Value) -> RdpJsonSlice {
    let envelope = serde_json::json!({
        "ok": true,
        "interchange": interchange,
        "notes": {
            "tabular_json": "Rust DataSet serde ↔ JVM JSONObject / List<Map<String,Object>>",
            "polars_engine": "Plans execute in Rust; JVM receives JSON snapshots only today.",
            "arrow_ipc": "rdp_export_arrow_ipc_temp writes a temp .arrow IPC file; Spark via rust-data-processing-jvm-spark reads it with Apache Arrow Java then createDataFrame.",
            "pytest_mirror": "Built to mirror python-wrapper/tests/*.py scenarios where feasible."
        }
    });
    let bytes = serde_json::to_vec(&envelope).unwrap_or_else(|_| br#"{"ok":false}"#.to_vec());
    vec_to_slice(bytes)
}

pub fn json_err(msg: impl Into<String>) -> RdpJsonSlice {
    let v = serde_json::json!({
        "ok": false,
        "error": msg.into(),
    });
    vec_to_slice(serde_json::to_vec(&v).unwrap_or_else(|_| b"{\"ok\":false}".to_vec()))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_json_slice_free(s: RdpJsonSlice) {
    if s.ptr.is_null() {
        return;
    }
    unsafe {
        drop(Vec::from_raw_parts(s.ptr, s.len, s.cap));
    }
}
