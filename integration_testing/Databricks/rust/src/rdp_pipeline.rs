//! Databricks S3 warehouse pipeline + object_store read-back verify.

use libloading::{Library, Symbol};
use rust_data_processing::ingestion::{delta_table_uri, ingest_from_object_store_uri, IngestionOptions};
use rust_data_processing::types::{DataType, Field, Schema};
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
        let src = col["source_field"].as_str().ok_or("column missing source_field")?;
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
        let run: Symbol<RunPipelineFn> = lib.get(b"rdp_run_pipeline_json").map_err(|e| e.to_string())?;
        let free: Symbol<JsonSliceFreeFn> = lib.get(b"rdp_json_slice_free").map_err(|e| e.to_string())?;
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

fn databricks_warehouse_uri() -> Result<String, String> {
    let uri = std::env::var("DATABRICKS_WAREHOUSE_URI").map_err(|_| "DATABRICKS_WAREHOUSE_URI".to_string())?;
    if !uri.starts_with("s3://") {
        return Err(format!("DATABRICKS_WAREHOUSE_URI must be s3://, got {uri}"));
    }
    Ok(if uri.ends_with('/') {
        uri
    } else {
        format!("{uri}/")
    })
}

fn output_parquet_uri(table_spec: &Value) -> Result<String, String> {
    let db = &table_spec["connectors"]["databricks"];
    let table_uri = delta_table_uri(
        &databricks_warehouse_uri()?,
        db["namespace"].as_str(),
        db["table"].as_str().ok_or("table missing")?,
    );
    Ok(format!("{table_uri}part-rdp-000.parquet"))
}

pub fn import_csv_databricks(csv: &Path, max_rows: usize) -> Result<(usize, usize), String> {
    let table_spec = load_json(&schema_dir().join("uber_pickups.table.json"))?;
    let dataset_schema = load_json(&schema_dir().join("uber_pickups.schema.json"))?;
    let db = &table_spec["connectors"]["databricks"];
    let payload = json!({
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [csv.to_string_lossy()],
            "schema": dataset_schema,
            "options": { "format": "csv", "max_rows": max_rows },
        },
        "transform": { "sql": transform_sql(&table_spec)? },
        "sinks": [{
            "kind": "databricks",
            "workspace_url": db["workspace_url"],
            "warehouse": databricks_warehouse_uri()?,
            "namespace": db["namespace"],
            "table": db["table"],
        }],
        "orchestration": { "max_ingested_rows": max_rows },
    });
    let inter = run_pipeline(payload)?;
    let ingested = inter["ingested_row_count"].as_u64().ok_or("missing ingested_row_count")? as usize;
    let sink = inter["sink_results"]
        .as_array()
        .and_then(|a| a.iter().find(|s| s["kind"] == "databricks"))
        .ok_or("missing databricks sink")?;
    if sink["status"] != "ok" {
        return Err(format!("databricks sink failed: {sink}"));
    }
    let loaded = sink["row_count"].as_u64().ok_or("missing row_count")? as usize;
    Ok((ingested, loaded))
}

pub fn verify_databricks_sql(expected: usize) -> Result<usize, String> {
    let script = integration_root().join("scripts/platform_sql.py");
    let python = std::env::var("RDP_PLATFORM_PYTHON").map(PathBuf::from).unwrap_or_else(|_| {
        integration_root().join("python-wrapper/.venv/bin/python")
    });
    let out = std::process::Command::new(&python)
        .arg(&script)
        .arg("databricks")
        .arg("--expected")
        .arg(expected.to_string())
        .envs(std::env::vars())
        .output()
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Err(format!(
            "databricks SQL verify failed:\n{}\n{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        ));
    }
    Ok(expected)
}

pub fn verify_databricks_output(expected: usize) -> Result<usize, String> {
    let table_spec = load_json(&schema_dir().join("uber_pickups.table.json"))?;
    let uri = output_parquet_uri(&table_spec)?;
    let schema_json = load_json(&schema_dir().join("uber_pickups.schema.json"))?;
    let fields: Vec<Field> = schema_json["fields"]
        .as_array()
        .ok_or("schema fields")?
        .iter()
        .map(|f| {
            let name = f["name"].as_str().unwrap_or("col");
            let dt = match f["data_type"].as_str().unwrap_or("Utf8") {
                "Int64" => DataType::Int64,
                "Float64" => DataType::Float64,
                "Bool" => DataType::Bool,
                _ => DataType::Utf8,
            };
            Field::new(name, dt)
        })
        .collect();
    let schema = Schema::new(fields);
    let ds = ingest_from_object_store_uri(
        &uri,
        &schema,
        &IngestionOptions {
            format: Some(rust_data_processing::ingestion::IngestionFormat::Parquet),
            ..Default::default()
        },
    )
    .map_err(|e| e.to_string())?;
    let count = ds.row_count();
    if count != expected {
        return Err(format!("expected {expected} rows at {uri}, got {count}"));
    }
    Ok(count)
}
