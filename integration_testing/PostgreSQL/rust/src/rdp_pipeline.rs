//! Call ``rdp_run_pipeline_json`` from the built ``librdp_jvm_sys`` (RDP-only integration path).

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
    let default = integration_root().join("libs/java/librdp_jvm_sys.so");
    if default.is_file() {
        return Ok(default);
    }
    Err("RDP_JVM_SYS not set and libs/java/librdp_jvm_sys.so missing".into())
}

fn strip_url_query(url: &str) -> Result<String, String> {
    let parsed = url::Url::parse(url).map_err(|e| e.to_string())?;
    if parsed.scheme() != "postgresql" && parsed.scheme() != "postgres" {
        return Err(format!("expected postgresql:// URL, got {url}"));
    }
    let mut no_query = parsed;
    no_query.set_query(None);
    no_query.set_fragment(None);
    Ok(no_query.to_string())
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
    unsafe {
        let lib =
            Library::new(&lib_path).map_err(|e| format!("load {}: {e}", lib_path.display()))?;
        let run: Symbol<RunPipelineFn> = lib
            .get(b"rdp_run_pipeline_json")
            .map_err(|e| format!("symbol rdp_run_pipeline_json: {e}"))?;
        let free: Symbol<JsonSliceFreeFn> = lib
            .get(b"rdp_json_slice_free")
            .map_err(|e| format!("symbol rdp_json_slice_free: {e}"))?;

        let text = serde_json::to_string(&payload).map_err(|e| e.to_string())?;
        let c_text = std::ffi::CString::new(text).map_err(|e| format!("pipeline json nul: {e}"))?;
        let mut out = RdpJsonSlice {
            ptr: std::ptr::null_mut(),
            len: 0,
            cap: 0,
        };
        run(&mut out, c_text.as_ptr());
        if out.ptr.is_null() {
            return Err("rdp_run_pipeline_json returned null slice".into());
        }
        let slice = std::slice::from_raw_parts(out.ptr, out.len);
        let root: Value = serde_json::from_slice(slice).map_err(|e| e.to_string())?;
        free(out);
        if !root.get("ok").and_then(Value::as_bool).unwrap_or(false) {
            return Err(format!("rdp_run_pipeline_json failed: {root}"));
        }
        Ok(root["interchange"].clone())
    }
}

pub fn import_csv_postgresql(
    csv: &Path,
    connect_url: &str,
    max_rows: usize,
) -> Result<(usize, usize), String> {
    let table_spec = load_json(&schema_dir().join("uber_pickups.table.json"))?;
    let dataset_schema = load_json(&schema_dir().join("uber_pickups.schema.json"))?;
    let table = table_spec["connectors"]["postgresql"]["table"]
        .as_str()
        .ok_or("postgresql table missing in table spec")?;
    let url = strip_url_query(connect_url)?;

    let payload = json!({
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [csv.to_string_lossy()],
            "schema": dataset_schema,
            "options": {
                "format": "csv",
                "max_rows": max_rows,
            },
        },
        "transform": { "sql": transform_sql(&table_spec)? },
        "sinks": [{
            "kind": "postgresql",
            "url": url,
            "table": table,
            "create_table_if_missing": true,
            "truncate_before_load": true,
        }],
        "orchestration": { "max_ingested_rows": max_rows },
    });

    let inter = run_pipeline(payload)?;
    let ingested = inter["ingested_row_count"]
        .as_u64()
        .ok_or("missing ingested_row_count")? as usize;
    let sink = inter["sink_results"]
        .as_array()
        .and_then(|arr| arr.iter().find(|s| s["kind"] == "postgresql"))
        .ok_or("missing postgresql sink result")?;
    if sink["status"] != "ok" {
        return Err(format!("postgresql sink failed: {sink}"));
    }
    let loaded = sink["row_count"].as_u64().ok_or("missing row_count")? as usize;
    Ok((ingested, loaded))
}

pub fn verify_count_postgresql(connect_url: &str, expected: usize) -> Result<usize, String> {
    use rust_data_processing::ingestion::{ingest_from_db, IngestionOptions};
    use rust_data_processing::types::{DataType, Field, Schema};

    let table_spec = load_json(&schema_dir().join("uber_pickups.table.json"))?;
    let table = table_spec["connectors"]["postgresql"]["table"]
        .as_str()
        .ok_or("postgresql table missing")?;
    let url = strip_url_query(connect_url)?;
    let schema = Schema::new(vec![Field::new("cnt", DataType::Int64)]);
    let query = format!("SELECT COUNT(*)::bigint AS cnt FROM {table}");
    let ds = ingest_from_db(&url, &query, &schema, &IngestionOptions::default())
        .map_err(|e| e.to_string())?;
    let count = match ds.rows.first().and_then(|r| r.first()) {
        Some(rust_data_processing::types::Value::Int64(n)) if *n >= 0 => *n as usize,
        _ => return Err("COUNT(*) did not return Int64".into()),
    };
    if count != expected {
        return Err(format!("expected {expected} rows in {table}, got {count}"));
    }
    Ok(count)
}
