//! Cloud storage pipeline helpers — export/import via ``rdp_run_pipeline_json``.

use libloading::{Library, Symbol};
use serde_json::{json, Value};
use std::ffi::c_char;
use std::path::{Path, PathBuf};

#[repr(C)]
struct RdpJsonSlice {
    ptr: *mut u8,
    len: usize,
    cap: usize,
}

type RunPipelineFn = unsafe extern "C" fn(*mut RdpJsonSlice, *const c_char);
type JsonSliceFreeFn = unsafe extern "C" fn(RdpJsonSlice);

fn integration_root() -> PathBuf {
    std::env::var("RDP_INTEGRATION_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("integration_testing"))
}

fn schema_dir() -> PathBuf {
    integration_root().join("schema")
}

fn load_json(path: &Path) -> Result<Value, String> {
    let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(&text).map_err(|e| format!("parse {}: {e}", path.display()))
}

fn lib_path() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("RDP_JVM_SYS") {
        return Ok(PathBuf::from(p));
    }
    Ok(integration_root().join("libs/java/librdp_jvm_sys.so"))
}

fn transform_sql(table_spec: &Value) -> Result<String, String> {
    let columns = table_spec["columns"]
        .as_array()
        .ok_or("table spec missing columns")?;
    let mut parts = Vec::new();
    for col in columns {
        let src = col["source_field"]
            .as_str()
            .ok_or("column missing source_field")?;
        let name = col["name"].as_str().ok_or("column missing name")?;
        if src.contains(' ') || src.contains('/') {
            parts.push(format!("\"{src}\" AS {name}"));
        } else {
            parts.push(format!("{src} AS {name}"));
        }
    }
    Ok(format!("SELECT {} FROM df", parts.join(", ")))
}

fn run_pipeline(payload: Value) -> Result<Value, String> {
    let lib_path = lib_path()?;
    if !lib_path.is_file() {
        return Err("RDP_JVM_SYS missing".into());
    }
    unsafe {
        let lib = Library::new(&lib_path).map_err(|e| e.to_string())?;
        let run: Symbol<RunPipelineFn> = lib
            .get(b"rdp_run_pipeline_json")
            .map_err(|e| e.to_string())?;
        let free: Symbol<JsonSliceFreeFn> =
            lib.get(b"rdp_json_slice_free").map_err(|e| e.to_string())?;
        let text = serde_json::to_string(&payload).map_err(|e| e.to_string())?;
        let c_text = std::ffi::CString::new(text).map_err(|e| e.to_string())?;
        let mut out = RdpJsonSlice {
            ptr: std::ptr::null_mut(),
            len: 0,
            cap: 0,
        };
        run(&mut out, c_text.as_ptr());
        let slice = std::slice::from_raw_parts(out.ptr, out.len);
        let root: Value = serde_json::from_slice(slice).map_err(|e| e.to_string())?;
        free(out);
        if !root.get("ok").and_then(Value::as_bool).unwrap_or(false) {
            return Err(format!("pipeline failed: {root}"));
        }
        Ok(root["interchange"].clone())
    }
}

fn env_uri(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn curated_schema(table_spec: &Value, dataset_schema: &Value) -> Result<Value, String> {
    let src_types: std::collections::HashMap<String, String> = dataset_schema["fields"]
        .as_array()
        .ok_or("dataset schema missing fields")?
        .iter()
        .filter_map(|f| {
            Some((
                f["name"].as_str()?.to_string(),
                f["data_type"].as_str()?.to_string(),
            ))
        })
        .collect();
    let mut fields = Vec::new();
    for col in table_spec["columns"].as_array().ok_or("missing columns")? {
        let name = col["name"].as_str().ok_or("column missing name")?;
        let src = col["source_field"]
            .as_str()
            .ok_or("column missing source_field")?;
        let dt = src_types.get(src).cloned().unwrap_or_else(|| "Utf8".into());
        fields.push(json!({"name": name, "data_type": dt}));
    }
    Ok(json!({"fields": fields}))
}

pub fn verify_object_store_roundtrip(
    protocol: &str,
    csv: &Path,
    max_rows: usize,
) -> Result<usize, String> {
    let table_spec = load_json(&schema_dir().join("uber_pickups.table.json"))?;
    let dataset_schema = load_json(&schema_dir().join("uber_pickups.schema.json"))?;
    let curated = curated_schema(&table_spec, &dataset_schema)?;
    let uri = match protocol {
        "s3" => env_uri("CLOUD_S3_EXPORT_URI", "s3://rdp-cloud-s3/out.parquet"),
        "gcs" => env_uri("CLOUD_GCS_EXPORT_URI", "gs://rdp-cloud-gcs/out.parquet"),
        "azure" => env_uri(
            "CLOUD_AZURE_EXPORT_URI",
            "azure://rdp-cloud-azure/out.parquet",
        ),
        other => return Err(format!("unknown object_store protocol: {other}")),
    };
    let export = json!({
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [csv.to_string_lossy()],
            "schema": dataset_schema,
            "options": { "format": "csv", "max_rows": max_rows },
        },
        "transform": { "sql": transform_sql(&table_spec)? },
        "sinks": [{ "kind": "object_store", "uri": uri, "format": "parquet" }],
        "orchestration": { "max_ingested_rows": max_rows },
    });
    let inter = run_pipeline(export)?;
    let expected = inter["ingested_row_count"]
        .as_u64()
        .ok_or("missing ingested_row_count")? as usize;
    let read_back = run_pipeline(json!({
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [],
            "schema": curated,
            "options": { "format": "parquet" },
            "object_store_uris": [uri],
        },
        "sinks": [{
            "kind": "parquet_file",
            "path": format!("/tmp/rdp-cloud-{protocol}-readback.parquet"),
        }],
    }))?;
    let got = read_back["ingested_row_count"]
        .as_u64()
        .ok_or("missing read count")? as usize;
    if got != expected {
        return Err(format!(
            "{protocol} roundtrip: expected {expected}, got {got}"
        ));
    }
    Ok(expected)
}

pub fn verify_file_transfer_import(protocol: &str) -> Result<usize, String> {
    let uri = match protocol {
        "sftp" => env_uri(
            "CLOUD_SFTP_SOURCE_URI",
            "sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv",
        ),
        "ftp" => env_uri(
            "CLOUD_FTP_SOURCE_URI",
            "ftp://rdp:rdp_ftp_secret@127.0.0.1:21/incoming.csv",
        ),
        other => return Err(format!("unknown file_transfer protocol: {other}")),
    };
    let max_rows = std::env::var("INTEG_MAX_IMPORT_ROWS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500);
    let import = json!({
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [],
            "schema": load_json(&schema_dir().join("uber_pickups.schema.json"))?,
            "options": { "format": "csv", "max_rows": max_rows },
            "file_transfer_uris": [uri],
        },
        "sinks": [{
            "kind": "parquet_file",
            "path": format!("/tmp/rdp-cloud-{protocol}-import.parquet"),
        }],
    });
    let inter = run_pipeline(import)?;
    let count = inter["ingested_row_count"]
        .as_u64()
        .ok_or("missing ingested_row_count")? as usize;
    if count == 0 {
        return Err(format!("{protocol} import returned no rows"));
    }
    Ok(count)
}
