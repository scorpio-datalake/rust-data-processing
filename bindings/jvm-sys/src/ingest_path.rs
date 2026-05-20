//! Parameterized path ingestion over FFI — path + schema JSON + options JSON (UTF‑8 C strings).

use crate::parity_support::{json_err, json_ok, write_slice, RdpJsonSlice};
use serde::Deserialize;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};

const DEFAULT_MAX_DATASET_ROWS: usize = 50_000;

unsafe fn cstr_to_str<'a>(ptr: *const c_char, label: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{label}: null pointer"));
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("{label}: invalid UTF-8: {e}"))
}

#[cfg(feature = "link-main")]
pub(crate) fn parse_ingestion_options(
    json: &str,
    format_override: Option<rust_data_processing::ingestion::IngestionFormat>,
) -> Result<rust_data_processing::ingestion::IngestionOptions, String> {
    use rust_data_processing::ingestion::{ExcelSheetSelection, IngestionFormat, IngestionOptions};

    if json.trim().is_empty() {
        return Err("options JSON is empty".into());
    }
    let v: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("options JSON parse: {e}"))?;
    if !v.is_object() {
        return Err("options JSON must be an object".into());
    }
    let obj = v.as_object().unwrap();

    let mut opts = IngestionOptions::default();

    if let Some(fmt) = obj.get("format").and_then(|x| x.as_str()) {
        opts.format = Some(match fmt {
            "csv" => IngestionFormat::Csv,
            "json" => IngestionFormat::Json,
            "parquet" | "pq" => IngestionFormat::Parquet,
            "excel" | "xlsx" | "xls" | "xlsm" | "xlsb" | "ods" => IngestionFormat::Excel,
            "xml" => IngestionFormat::Xml,
            other => return Err(format!("unknown format: {other}")),
        });
    }

    if let Some(sheet) = obj
        .get("sheet_name")
        .and_then(|x| x.as_str())
        .or_else(|| obj.get("excel_sheet").and_then(|x| x.as_str()))
    {
        opts.excel_sheet_selection = ExcelSheetSelection::Sheet(sheet.to_string());
    }

    if let Some(fmt) = format_override {
        opts.format = Some(fmt);
    }

    if let Some(col) = obj.get("watermark_column").and_then(|x| x.as_str()) {
        opts.watermark_column = Some(col.to_string());
    }
    if let Some(w) = obj.get("watermark_exclusive_above") {
        opts.watermark_exclusive_above = Some(json_scalar_to_value(w)?);
    }

    Ok(opts)
}

#[cfg(feature = "link-main")]
fn json_scalar_to_value(v: &serde_json::Value) -> Result<rust_data_processing::types::Value, String> {
    use rust_data_processing::types::Value;
    match v {
        serde_json::Value::Null => Ok(Value::Null),
        serde_json::Value::Bool(b) => Ok(Value::Bool(*b)),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(Value::Int64(i))
            } else if let Some(f) = n.as_f64() {
                Ok(Value::Float64(f))
            } else {
                Err(format!("unsupported JSON number for watermark value: {n}"))
            }
        }
        serde_json::Value::String(s) => Ok(Value::Utf8(s.clone())),
        serde_json::Value::Object(map) => {
            // Accept serde externally-tagged Value, e.g. {"Int64": 100}
            serde_json::from_value(serde_json::Value::Object(map.clone()))
                .map_err(|e| format!("watermark_exclusive_above object: {e}"))
        }
        _ => Err("watermark_exclusive_above must be a scalar or serde Value object".into()),
    }
}

#[cfg(feature = "link-main")]
fn parse_schema_json(json: &str) -> Result<rust_data_processing::types::Schema, String> {
    serde_json::from_str(json).map_err(|e| format!("schema JSON: {e}"))
}

#[cfg(feature = "link-main")]
fn ingest_one_path(
    path: &Path,
    schema: &rust_data_processing::types::Schema,
    opts: &rust_data_processing::ingestion::IngestionOptions,
    kind: &'static str,
) -> RdpJsonSlice {
    use rust_data_processing::ingestion::ingest_from_path;

    match ingest_from_path(path, schema, opts) {
        Ok(ds) => dataset_envelope(kind, &ds),
        Err(e) => json_err(e.to_string()),
    }
}

#[cfg(feature = "link-main")]
fn dataset_envelope(kind: &str, ds: &rust_data_processing::types::DataSet) -> RdpJsonSlice {
    match serde_json::to_value(ds) {
        Ok(dataset) => json_ok(serde_json::json!({
            "kind": kind,
            "engine": "ingest_from_path",
            "dataset": dataset,
        })),
        Err(e) => json_err(format!("serialize DataSet: {e}")),
    }
}

#[cfg(feature = "link-main")]
fn ingest_path_impl(
    path_ptr: *const c_char,
    schema_json_ptr: *const c_char,
    options_json_ptr: *const c_char,
    format_override: Option<rust_data_processing::ingestion::IngestionFormat>,
    kind: &'static str,
) -> RdpJsonSlice {
    let path_str = match unsafe { cstr_to_str(path_ptr, "path") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let schema_json = match unsafe { cstr_to_str(schema_json_ptr, "schema_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let options_json = match unsafe { cstr_to_str(options_json_ptr, "options_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };

    let schema = match parse_schema_json(schema_json) {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let opts = match parse_ingestion_options(options_json, format_override) {
        Ok(o) => o,
        Err(e) => return json_err(e),
    };

    ingest_one_path(Path::new(path_str), &schema, &opts, kind)
}

/// Ingest a single CSV file from `path` using caller-supplied `Schema` and `IngestionOptions` JSON.
///
/// C strings: `path`, `schema_json` (`Schema` serde shape), `options_json` (object, may be `{}`).
#[no_mangle]
pub unsafe extern "C" fn rdp_ingest_csv_path(
    out: *mut RdpJsonSlice,
    path_ptr: *const c_char,
    schema_json_ptr: *const c_char,
    options_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            ingest_path_impl(
                path_ptr,
                schema_json_ptr,
                options_json_ptr,
                Some(rust_data_processing::ingestion::IngestionFormat::Csv),
                "ingest_path_csv",
            )
        }
        #[cfg(not(feature = "link-main"))]
        {
            json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
        }
    };
    unsafe { write_slice(out, slice) }
}

#[no_mangle]
pub unsafe extern "C" fn rdp_ingest_json_path(
    out: *mut RdpJsonSlice,
    path_ptr: *const c_char,
    schema_json_ptr: *const c_char,
    options_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            ingest_path_impl(
                path_ptr,
                schema_json_ptr,
                options_json_ptr,
                Some(rust_data_processing::ingestion::IngestionFormat::Json),
                "ingest_path_json",
            )
        }
        #[cfg(not(feature = "link-main"))]
        {
            json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
        }
    };
    unsafe { write_slice(out, slice) }
}

#[no_mangle]
pub unsafe extern "C" fn rdp_ingest_parquet_path(
    out: *mut RdpJsonSlice,
    path_ptr: *const c_char,
    schema_json_ptr: *const c_char,
    options_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            ingest_path_impl(
                path_ptr,
                schema_json_ptr,
                options_json_ptr,
                Some(rust_data_processing::ingestion::IngestionFormat::Parquet),
                "ingest_path_parquet",
            )
        }
        #[cfg(not(feature = "link-main"))]
        {
            json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
        }
    };
    unsafe { write_slice(out, slice) }
}

#[no_mangle]
pub unsafe extern "C" fn rdp_ingest_xml_path(
    out: *mut RdpJsonSlice,
    path_ptr: *const c_char,
    schema_json_ptr: *const c_char,
    options_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            ingest_path_impl(
                path_ptr,
                schema_json_ptr,
                options_json_ptr,
                Some(rust_data_processing::ingestion::IngestionFormat::Xml),
                "ingest_path_xml",
            )
        }
        #[cfg(not(feature = "link-main"))]
        {
            json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
        }
    };
    unsafe { write_slice(out, slice) }
}

fn default_empty_object() -> serde_json::Value {
    serde_json::json!({})
}

#[derive(Debug, Deserialize)]
struct OrderedPathsRequest {
    paths: Vec<String>,
    schema: rust_data_processing::types::Schema,
    #[serde(default = "default_empty_object")]
    options: serde_json::Value,
    response: ResponseSpec,
}

#[derive(Debug, Deserialize)]
struct ResponseSpec {
    /// `dataset` | `parquet_temp` | `arrow_ipc_temp`
    mode: String,
    /// When `mode == "dataset"`, cap rows returned (default 50_000). Ignored for temp-file modes.
    #[serde(default)]
    max_rows: Option<usize>,
}

#[cfg(feature = "link-main")]
fn ordered_paths_impl(payload_json: &str) -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{
        export_dataset_to_arrow_ipc, export_dataset_to_parquet, ingest_from_ordered_paths,
    };
    use rust_data_processing::types::DataSet;

    let req: OrderedPathsRequest =
        serde_json::from_str(payload_json).map_err(|e| format!("payload JSON: {e}"))?;
    let OrderedPathsRequest {
        paths,
        schema,
        options,
        response,
    } = req;
    if paths.is_empty() {
        return Err("paths must be non-empty".into());
    }
    let paths_for_json = paths.clone();
    let path_bufs: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).collect();
    let opts_json = if options.is_null() {
        "{}".to_string()
    } else {
        options.to_string()
    };
    let opts = parse_ingestion_options(&opts_json, None)?;

    let (mut ds, meta) = ingest_from_ordered_paths(&path_bufs, &schema, &opts)
        .map_err(|e| format!("ingest_from_ordered_paths: {e}"))?;

    let total_row_count = ds.row_count();
    let mode = response.mode.as_str();

    match mode {
        "dataset" => {
            let cap = response
                .max_rows
                .unwrap_or(DEFAULT_MAX_DATASET_ROWS)
                .max(1);
            let truncated = total_row_count > cap;
            if truncated {
                let mut rows = std::mem::take(&mut ds.rows);
                rows.truncate(cap);
                ds = DataSet::new(ds.schema.clone(), rows);
            }
            let dataset = serde_json::to_value(&ds).map_err(|e| e.to_string())?;
            let mut ordered_batch = serde_json::json!({
                "paths": meta
                    .paths
                    .iter()
                    .map(|p| p.to_string_lossy().to_string())
                    .collect::<Vec<_>>(),
                "last_path": meta
                    .last_path
                    .as_ref()
                    .map(|p| p.to_string_lossy().to_string()),
            });
            if let Some(v) = &meta.max_watermark_value {
                ordered_batch["max_watermark_value"] =
                    serde_json::to_value(v).map_err(|e| e.to_string())?;
            }
            Ok(serde_json::json!({
                "kind": "ingest_ordered_paths_dataset",
                "engine": "ingest_from_ordered_paths",
                "dataset": dataset,
                "paths": paths_for_json.clone(),
                "total_row_count": total_row_count,
                "returned_row_count": ds.row_count(),
                "truncated": truncated,
                "ordered_batch": ordered_batch,
            }))
        }
        "parquet_temp" => {
            let base = std::env::temp_dir().join("rdp_jvm_ordered_parquet");
            std::fs::create_dir_all(&base).map_err(|e| e.to_string())?;
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|e| e.to_string())?
                .as_nanos();
            let path = base.join(format!("rdp_ordered_{stamp}.parquet"));
            export_dataset_to_parquet(&path, &ds).map_err(|e| e.to_string())?;
            let schema_json = serde_json::to_value(&ds.schema).map_err(|e| e.to_string())?;
            Ok(serde_json::json!({
                "kind": "ingest_ordered_paths_parquet_temp",
                "path": path.to_string_lossy(),
                "row_count": ds.row_count(),
                "schema": schema_json,
                "paths": paths_for_json.clone(),
            }))
        }
        "arrow_ipc_temp" => {
            let base = std::env::temp_dir().join("rdp_jvm_ordered_arrow_ipc");
            std::fs::create_dir_all(&base).map_err(|e| e.to_string())?;
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|e| e.to_string())?
                .as_nanos();
            let path = base.join(format!("rdp_ordered_{stamp}.arrow"));
            export_dataset_to_arrow_ipc(&path, &ds).map_err(|e| e.to_string())?;
            let schema_json = serde_json::to_value(&ds.schema).map_err(|e| e.to_string())?;
            Ok(serde_json::json!({
                "kind": "ingest_ordered_paths_arrow_ipc_temp",
                "path": path.to_string_lossy(),
                "row_count": ds.row_count(),
                "schema": schema_json,
                "paths": paths_for_json,
            }))
        }
        other => Err(format!("unknown response.mode: {other}")),
    }
}

/// Ingest ordered paths from a single UTF‑8 JSON payload (NUL-terminated). Shape:
///
/// ```json
/// {
///   "paths": ["/abs/a.csv", "/abs/b.csv"],
///   "schema": { "fields": [{"name":"id","data_type":"Int64"}, ...] },
///   "options": { "format": "csv" },
///   "response": { "mode": "dataset", "max_rows": 10000 }
/// }
/// ```
///
/// `response.mode`: `dataset` (optional `max_rows`, default 50000), `parquet_temp`, `arrow_ipc_temp`.
#[no_mangle]
pub unsafe extern "C" fn rdp_ingest_ordered_paths_json(
    out: *mut RdpJsonSlice,
    payload_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            match unsafe { cstr_to_str(payload_json_ptr, "payload_json") } {
                Ok(payload) => match ordered_paths_impl(payload) {
                    Ok(v) => json_ok(v),
                    Err(e) => json_err(e),
                },
                Err(e) => json_err(e),
            }
        }
        #[cfg(not(feature = "link-main"))]
        {
            json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
        }
    };
    unsafe { write_slice(out, slice) }
}

#[cfg(all(test, feature = "link-main"))]
mod people_payload_tests {
    use super::ordered_paths_impl;
    use rust_data_processing::pipeline_spec::PipelineBundle;
    use std::collections::HashMap;
    use std::path::PathBuf;

    fn people_json() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures/people.json")
    }

    fn people_csv() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures/people.csv")
    }

    fn people_xlsx() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures/people.xlsx")
    }

    #[test]
    fn people_excel_sheet_dataset_payload_ordered_ingest() {
        let xlsx = people_xlsx();
        assert!(
            xlsx.is_file(),
            "missing {} — run: cargo run --features excel_test_writer --bin generate_people_xlsx_fixture",
            xlsx.display()
        );
        let bundle = PipelineBundle::from_repo_fixture("people");
        let payload = bundle
            .resolve_payload_json(
                "payloads/excel_sheet_dataset.payload.json",
                &HashMap::from([
                    (
                        "SOURCE_PATH".into(),
                        xlsx.to_string_lossy().into_owned(),
                    ),
                    ("SHEET_NAME".into(), "Sheet1".into()),
                ]),
            )
            .unwrap();
        let v = ordered_paths_impl(&payload).unwrap();
        assert_eq!(v["kind"], "ingest_ordered_paths_dataset");
        assert_eq!(v["returned_row_count"].as_i64(), Some(2));
    }

    #[test]
    fn people_json_path_dataset_payload_ordered_ingest() {
        let bundle = PipelineBundle::from_repo_fixture("people");
        let payload = bundle
            .resolve_payload_json(
                "payloads/json_path_dataset.payload.json",
                &HashMap::from([(
                    "SOURCE_PATH".into(),
                    people_json().to_string_lossy().into_owned(),
                )]),
            )
            .unwrap();
        let v = ordered_paths_impl(&payload).unwrap();
        assert_eq!(v["kind"], "ingest_ordered_paths_dataset");
        assert_eq!(v["returned_row_count"].as_i64(), Some(2));
    }

    #[test]
    fn watermark_directory_scan_payload_includes_max_watermark_in_ordered_batch() {
        let dir = std::env::temp_dir().join(format!(
            "rdp_jvm_wm_{}_{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let nested = dir.join("nested");
        std::fs::create_dir_all(&nested).unwrap();
        std::fs::write(dir.join("a.csv"), "id,ts\n1,50\n2,99\n").unwrap();
        std::fs::write(nested.join("b.csv"), "id,ts\n3,150\n4,200\n").unwrap();

        let paths = rust_data_processing::ingestion::paths_from_directory_scan(&dir, Some("**/*.csv"))
            .unwrap();
        let bundle = PipelineBundle::from_repo_fixture("watermark");
        let mut body: serde_json::Value = serde_json::from_str(
            &bundle
                .resolve_payload_json("payloads/csv_watermark_ingest.body.json", &HashMap::new())
                .unwrap(),
        )
        .unwrap();
        let path_strings: Vec<String> = paths
            .iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        body["paths"] = serde_json::Value::Array(
            path_strings
                .iter()
                .map(|s| serde_json::Value::String(s.clone()))
                .collect(),
        );
        let v = ordered_paths_impl(&serde_json::to_string(&body).unwrap()).unwrap();
        assert_eq!(v["returned_row_count"].as_i64(), Some(2));
        assert_eq!(
            v["ordered_batch"]["max_watermark_value"]["Int64"].as_i64(),
            Some(200)
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn people_csv_path_dataset_payload_ordered_ingest() {
        let bundle = PipelineBundle::from_repo_fixture("people");
        let payload = bundle
            .resolve_payload_json(
                "payloads/csv_path_dataset.payload.json",
                &HashMap::from([(
                    "SOURCE_PATH".into(),
                    people_csv().to_string_lossy().into_owned(),
                )]),
            )
            .unwrap();
        let v = ordered_paths_impl(&payload).unwrap();
        assert_eq!(v["kind"], "ingest_ordered_paths_dataset");
        assert_eq!(v["returned_row_count"].as_i64(), Some(2));
    }
}
