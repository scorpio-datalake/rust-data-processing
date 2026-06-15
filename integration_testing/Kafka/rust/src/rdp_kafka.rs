//! Kafka streaming via ``rdp_kafka_export_dataset_json`` / ``rdp_kafka_poll_window_loaded_json``.

use libloading::{Library, Symbol};
use serde_json::{json, Value};
use std::ffi::c_char;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};

#[repr(C)]
struct RdpJsonSlice {
    ptr: *mut u8,
    len: usize,
    cap: usize,
}

type KafkaTwoArgFn = unsafe extern "C" fn(*mut RdpJsonSlice, *const c_char, *const c_char);
type JsonSliceFreeFn = unsafe extern "C" fn(RdpJsonSlice);

fn integration_root() -> PathBuf {
    std::env::var("RDP_INTEGRATION_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| "integration_testing".into())
}

fn lib_path() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("RDP_JVM_SYS") {
        return Ok(PathBuf::from(p));
    }
    Ok(integration_root().join("libs/java/librdp_jvm_sys.so"))
}

fn kafka_brokers() -> String {
    std::env::var("KAFKA_BROKERS").unwrap_or_else(|_| "127.0.0.1:19092".into())
}

fn kafka_topic() -> String {
    std::env::var("KAFKA_TOPIC").unwrap_or_else(|_| "rdp-uber-pickups".into())
}

fn kafka_group() -> String {
    std::env::var("KAFKA_GROUP_ID").unwrap_or_else(|_| "rdp-integration-test".into())
}

fn landing_schema() -> Value {
    json!({
        "fields": [
            {"name": "pickup_time", "data_type": "Utf8"},
            {"name": "lat", "data_type": "Float64"},
            {"name": "lon", "data_type": "Float64"},
            {"name": "base_code", "data_type": "Utf8"},
            {"name": "_kafka_offset", "data_type": "Int64"},
            {"name": "_kafka_partition", "data_type": "Int64"},
        ]
    })
}

fn invoke_two_arg(symbol: &[u8], arg1: &str, arg2: &str) -> Result<Value, String> {
    let lib_path = lib_path()?;
    if !lib_path.is_file() {
        return Err("RDP_JVM_SYS missing (rebuild with full,kafka)".into());
    }
    unsafe {
        let lib = Library::new(&lib_path).map_err(|e| e.to_string())?;
        let func: Symbol<KafkaTwoArgFn> = lib.get(symbol).map_err(|e| e.to_string())?;
        let free: Symbol<JsonSliceFreeFn> =
            lib.get(b"rdp_json_slice_free").map_err(|e| e.to_string())?;
        let c1 = std::ffi::CString::new(arg1).map_err(|e| e.to_string())?;
        let c2 = std::ffi::CString::new(arg2).map_err(|e| e.to_string())?;
        let mut out = RdpJsonSlice {
            ptr: std::ptr::null_mut(),
            len: 0,
            cap: 0,
        };
        func(&mut out, c1.as_ptr(), c2.as_ptr());
        let slice = std::slice::from_raw_parts(out.ptr, out.len);
        let root: Value = serde_json::from_slice(slice).map_err(|e| e.to_string())?;
        free(out);
        if !root.get("ok").and_then(Value::as_bool).unwrap_or(false) {
            return Err(format!("kafka FFI failed: {root}"));
        }
        Ok(root)
    }
}

pub fn stream_csv_to_kafka(csv: &Path, max_rows: usize) -> Result<usize, String> {
    let file = File::open(csv).map_err(|e| e.to_string())?;
    let mut reader = BufReader::new(file);
    let mut header = String::new();
    reader.read_line(&mut header).map_err(|e| e.to_string())?;
    let producer_cfg = json!({
        "brokers": kafka_brokers(),
        "topic": kafka_topic(),
        "message_timeout_ms": 10_000,
    })
    .to_string();
    let row_schema = json!({
        "schema": {
            "fields": [
                {"name": "pickup_time", "data_type": "Utf8"},
                {"name": "lat", "data_type": "Float64"},
                {"name": "lon", "data_type": "Float64"},
                {"name": "base_code", "data_type": "Utf8"},
            ]
        }
    });
    let mut sent = 0usize;
    for line in reader.lines() {
        if sent >= max_rows {
            break;
        }
        let line = line.map_err(|e| e.to_string())?;
        let cols: Vec<&str> = line.split(',').collect();
        if cols.len() < 4 {
            continue;
        }
        let lat = cols[1].parse::<f64>().unwrap_or(0.0);
        let lon = cols[2].parse::<f64>().unwrap_or(0.0);
        let dataset = json!({
            "schema": row_schema["schema"],
            "rows": [[
                {"Utf8": cols[0]},
                {"Float64": lat},
                {"Float64": lon},
                {"Utf8": cols[3]},
            ]],
        });
        invoke_two_arg(
            b"rdp_kafka_export_dataset_json",
            &producer_cfg,
            &dataset.to_string(),
        )?;
        sent += 1;
    }
    if sent == 0 {
        return Err("no rows streamed to kafka".into());
    }
    Ok(sent)
}

pub fn poll_kafka_count(expected: usize, group_id: &str) -> Result<usize, String> {
    let consumer_cfg = json!({
        "brokers": kafka_brokers(),
        "group_id": group_id,
        "topic": kafka_topic(),
        "max_records": expected,
        "auto_offset_reset": "earliest",
    })
    .to_string();
    let root = invoke_two_arg(
        b"rdp_kafka_poll_window_loaded_json",
        &consumer_cfg,
        &landing_schema().to_string(),
    )?;
    let rows = root["interchange"]["dataset"]["rows"]
        .as_array()
        .ok_or("missing dataset rows")?;
    let count = rows.len();
    if count != expected {
        return Err(format!("kafka poll: expected {expected}, got {count}"));
    }
    Ok(count)
}

pub fn verify_uber_kafka_stream(csv: &Path, max_rows: usize) -> Result<usize, String> {
    let produced = stream_csv_to_kafka(csv, max_rows)?;
    let group = format!("{}-{}", kafka_group(), std::process::id());
    poll_kafka_count(produced, &group)
}
