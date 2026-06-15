//! Phase 3 JVM parity exports — UTF‑8 JSON for Java (`JSONObject` / row maps). Polars executes in
//! Rust; tabular data crosses FFI as serde‑JSON `DataSet`. Arrow IPC over FFI is reserved under
//! `interchange.notes.arrow_ipc` for Apache Arrow Java (`VectorSchemaRoot`) in a later milestone.

use crate::parity_support::*;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::Path;

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_types_dataset(out: *mut RdpJsonSlice) {
    let slice = parity_types_dataset();
    write_slice(out, slice);
}

fn parity_types_dataset() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match types_dataset_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn types_dataset_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
    ]);
    let ds = DataSet::new(
        schema,
        vec![
            vec![Value::Int64(1), Value::Utf8("Ada".to_string())],
            vec![Value::Int64(2), Value::Utf8("Bob".to_string())],
        ],
    );
    let dataset = serde_json::to_value(&ds).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "types_dataset",
        "engine": "in_memory_data_set",
        "dataset": dataset,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_ingestion(out: *mut RdpJsonSlice) {
    let slice = parity_ingestion();
    write_slice(out, slice);
}

fn parity_ingestion() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match ingestion_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn ingestion_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::csv::ingest_csv_from_reader;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema};
    use std::io::Cursor;

    let csv = "id,name\n1,Ada\n2,Bob\n";
    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
    ]);
    let mut rdr = csv::Reader::from_reader(Cursor::new(csv.as_bytes()));
    let ds: DataSet = ingest_csv_from_reader(&mut rdr, &schema).map_err(|e| e.to_string())?;
    let dataset = serde_json::to_value(&ds).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "ingestion_csv_reader_polars",
        "engine": "polars_csv_then_dataframe_to_dataset",
        "dataset": dataset,
    }))
}

/// Ingest an Excel workbook from a filesystem path and a specific sheet name, returning a JSON
/// `DataSet` under `interchange.dataset`. This is a JVM-only helper (Project Panama) that mirrors
/// Python `ingest_from_path(path, schema, {"format": "excel", "sheet_name": "Sheet1"})` for simple
/// tabular layouts.
///
/// Safety: `path_ptr` and `sheet_ptr` must be valid, NUL-terminated UTF‑8 C strings for the
/// duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rdp_excel_ingest_path_sheet(
    out: *mut RdpJsonSlice,
    path_ptr: *const c_char,
    sheet_ptr: *const c_char,
) {
    let slice = excel_ingest_path_sheet(path_ptr, sheet_ptr);
    unsafe {
        write_slice(out, slice);
    }
}

fn excel_ingest_path_sheet(path_ptr: *const c_char, sheet_ptr: *const c_char) -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        use rust_data_processing::ingestion::{
            ingest_from_path, ExcelSheetSelection, IngestionFormat, IngestionOptions,
        };
        use rust_data_processing::types::Schema;

        let path_cstr = unsafe { CStr::from_ptr(path_ptr) };
        let sheet_cstr = unsafe { CStr::from_ptr(sheet_ptr) };

        let path_str = match path_cstr.to_str() {
            Ok(s) => s,
            Err(e) => return json_err(format!("excel path not valid UTF-8: {e}")),
        };
        let sheet_name = match sheet_cstr.to_str() {
            Ok(s) => s.to_string(),
            Err(e) => return json_err(format!("excel sheet name not valid UTF-8: {e}")),
        };

        let path = Path::new(path_str);

        // Let the caller control the schema: prefer a simple two-column id/name layout by default.
        // For richer schemas, encourage Python / Rust helpers; this is primarily to unblock JVM ETL.
        //
        // NOTE: To avoid pulling Schema builders across FFI, we infer schema from the file using the
        // existing unified ingestion entrypoint and then re-use it for a second ingest. This mirrors
        // Python's "infer then ingest" pattern.
        let opts_infer = IngestionOptions {
            format: Some(IngestionFormat::Excel),
            excel_sheet_selection: ExcelSheetSelection::Sheet(sheet_name.clone()),
            ..IngestionOptions::default()
        };

        let schema: Schema =
            match rust_data_processing::ingestion::infer_schema_from_path(path, &opts_infer) {
                Ok(s) => s,
                Err(e) => return json_err(format!("infer Excel schema failed: {e}")),
            };

        let opts = IngestionOptions {
            format: Some(IngestionFormat::Excel),
            excel_sheet_selection: ExcelSheetSelection::Sheet(sheet_name),
            ..IngestionOptions::default()
        };

        match ingest_from_path(path, &schema, &opts) {
            Ok(ds) => match serde_json::to_value(&ds) {
                Ok(dataset) => json_ok(serde_json::json!({
                    "kind": "excel_ingest_sheet",
                    "dataset": dataset,
                })),
                Err(e) => json_err(format!("serialize Excel DataSet failed: {e}")),
            },
            Err(e) => json_err(format!("Excel ingest failed: {e}")),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        let _ = path_ptr;
        let _ = sheet_ptr;
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

/// Export a small sample [`DataSet`] to a temporary Parquet file and return a JSON envelope with
/// `path`, `row_count`, and `schema` (for Java / Spark to read via `spark.read().parquet(path)` then
/// delete the file).
#[no_mangle]
pub unsafe extern "C" fn rdp_export_parquet_temp(out: *mut RdpJsonSlice) {
    let slice = export_parquet_temp();
    write_slice(out, slice);
}

fn export_parquet_temp() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match export_parquet_temp_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn export_parquet_temp_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::export_dataset_to_parquet;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
    ]);
    let ds = DataSet::new(
        schema,
        vec![
            vec![Value::Int64(1), Value::Utf8("Ada".to_string())],
            vec![Value::Int64(2), Value::Utf8("Bob".to_string())],
        ],
    );

    let base = std::env::temp_dir().join("rdp_jvm_parquet");
    fs::create_dir_all(&base).map_err(|e| e.to_string())?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let path = base.join(format!("rdp_export_{stamp}.parquet"));

    export_dataset_to_parquet(&path, &ds).map_err(|e| e.to_string())?;

    let schema_json = serde_json::to_value(&ds.schema).map_err(|e| e.to_string())?;

    Ok(serde_json::json!({
        "kind": "parquet_export_temp",
        "path": path.to_string_lossy(),
        "row_count": ds.row_count(),
        "schema": schema_json,
    }))
}

/// Export a small sample [`DataSet`] to a temporary Arrow IPC file (Polars writer). JSON envelope
/// uses `kind`: `arrow_ipc_export_temp` for Java Arrow / Spark materializers.
#[no_mangle]
pub unsafe extern "C" fn rdp_export_arrow_ipc_temp(out: *mut RdpJsonSlice) {
    let slice = export_arrow_ipc_temp();
    write_slice(out, slice);
}

fn export_arrow_ipc_temp() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match export_arrow_ipc_temp_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn export_arrow_ipc_temp_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::export_dataset_to_arrow_ipc;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
    ]);
    let ds = DataSet::new(
        schema,
        vec![
            vec![Value::Int64(1), Value::Utf8("Ada".to_string())],
            vec![Value::Int64(2), Value::Utf8("Bob".to_string())],
        ],
    );

    let base = std::env::temp_dir().join("rdp_jvm_arrow_ipc");
    fs::create_dir_all(&base).map_err(|e| e.to_string())?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let path = base.join(format!("rdp_export_{stamp}.arrow"));

    export_dataset_to_arrow_ipc(&path, &ds).map_err(|e| e.to_string())?;

    let schema_json = serde_json::to_value(&ds.schema).map_err(|e| e.to_string())?;

    Ok(serde_json::json!({
        "kind": "arrow_ipc_export_temp",
        "path": path.to_string_lossy(),
        "row_count": ds.row_count(),
        "schema": schema_json,
    }))
}

/// Run a small Polars SQL pipeline (same shape as `rdp_parity_pipeline_sql`), write the result to a
/// temp Parquet file, and return `kind`: `polars_parquet_export_temp` (no embedded `dataset` JSON).
#[no_mangle]
pub unsafe extern "C" fn rdp_export_polars_parquet_temp(out: *mut RdpJsonSlice) {
    let slice = export_polars_parquet_temp();
    write_slice(out, slice);
}

fn export_polars_parquet_temp() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match export_polars_parquet_temp_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn export_polars_parquet_temp_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::export_dataset_to_parquet;
    use rust_data_processing::pipeline::DataFrame;
    use rust_data_processing::sql;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("active", DataType::Bool),
        ]),
        vec![
            vec![Value::Int64(1), Value::Bool(true)],
            vec![Value::Int64(2), Value::Bool(false)],
        ],
    );
    let df = DataFrame::from_dataset(&ds).map_err(|e| e.to_string())?;
    let out = sql::query(&df, "SELECT id FROM df WHERE active = TRUE ORDER BY id")
        .map_err(|e| e.to_string())?
        .collect()
        .map_err(|e| e.to_string())?;

    let base = std::env::temp_dir().join("rdp_jvm_polars_parquet");
    fs::create_dir_all(&base).map_err(|e| e.to_string())?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let path = base.join(format!("rdp_polars_export_{stamp}.parquet"));

    export_dataset_to_parquet(&path, &out).map_err(|e| e.to_string())?;

    let schema_json = serde_json::to_value(&out.schema).map_err(|e| e.to_string())?;

    Ok(serde_json::json!({
        "kind": "polars_parquet_export_temp",
        "path": path.to_string_lossy(),
        "row_count": out.row_count(),
        "schema": schema_json,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_processing(out: *mut RdpJsonSlice) {
    let slice = parity_processing();
    write_slice(out, slice);
}

fn parity_processing() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match processing_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn processing_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::processing::{filter, map, reduce, ReduceOp};
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("score", DataType::Float64),
    ]);
    let ds = DataSet::new(
        schema,
        vec![
            vec![Value::Int64(1), Value::Float64(10.0)],
            vec![Value::Int64(2), Value::Float64(20.0)],
        ],
    );
    let filtered = filter(
        &ds,
        |row| matches!(row.first(), Some(Value::Int64(i)) if *i > 1),
    );
    let mapped = map(&filtered, |row| {
        let mut r = row.to_vec();
        if let Some(Value::Float64(x)) = r.get_mut(1) {
            *x *= 2.0;
        }
        r
    });
    let sum = reduce(&mapped, "score", ReduceOp::Sum).unwrap_or(Value::Null);
    Ok(serde_json::json!({
        "kind": "processing_filter_map_reduce",
        "filtered_row_count": filtered.row_count(),
        "mapped_row_count": mapped.row_count(),
        "reduce_sum_score": serde_json::to_value(&sum).map_err(|e| e.to_string())?,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_pipeline_sql(out: *mut RdpJsonSlice) {
    let slice = parity_pipeline_sql();
    write_slice(out, slice);
}

fn parity_pipeline_sql() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match pipeline_sql_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn pipeline_sql_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::pipeline::DataFrame;
    use rust_data_processing::sql;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("active", DataType::Bool),
        ]),
        vec![
            vec![Value::Int64(1), Value::Bool(true)],
            vec![Value::Int64(2), Value::Bool(false)],
        ],
    );
    let df = DataFrame::from_dataset(&ds).map_err(|e| e.to_string())?;
    let out = sql::query(&df, "SELECT id FROM df WHERE active = TRUE ORDER BY id")
        .map_err(|e| e.to_string())?
        .collect()
        .map_err(|e| e.to_string())?;
    let dataset = serde_json::to_value(&out).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "pipeline_sql_polars",
        "engine": "polars_sql",
        "dataset": dataset,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_profiling(out: *mut RdpJsonSlice) {
    write_slice(out, parity_profiling());
}

fn parity_profiling() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match profiling_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn profiling_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::profiling::{
        profile_dataset, render_profile_report_json, ProfileOptions,
    };
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("score", DataType::Float64),
        ]),
        vec![
            vec![Value::Int64(1), Value::Float64(10.0)],
            vec![Value::Int64(2), Value::Float64(30.0)],
        ],
    );
    let rep = profile_dataset(&ds, &ProfileOptions::default()).map_err(|e| e.to_string())?;
    let json_txt = render_profile_report_json(&rep).map_err(|e| e.to_string())?;
    let parsed: serde_json::Value = serde_json::from_str(&json_txt).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "profiling_polars",
        "engine": "polars_profile_dataset",
        "report": parsed,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_validation(out: *mut RdpJsonSlice) {
    write_slice(out, parity_validation());
}

fn parity_validation() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match validation_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn validation_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    use rust_data_processing::validation::{validate_dataset, Check, Severity, ValidationSpec};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("name", DataType::Utf8),
        ]),
        vec![
            vec![Value::Int64(1), Value::Utf8("ok".to_string())],
            vec![Value::Int64(2), Value::Null],
        ],
    );
    let spec = ValidationSpec::new(vec![Check::NotNull {
        column: "name".to_string(),
        severity: Severity::Error,
    }]);
    let rep = validate_dataset(&ds, &spec).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "validation_polars_dsl",
        "engine": "polars_expr_checks",
        "summary": {
            "total_checks": rep.summary.total_checks,
            "failed_checks": rep.summary.failed_checks,
        },
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_outliers(out: *mut RdpJsonSlice) {
    write_slice(out, parity_outliers());
}

fn parity_outliers() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match outliers_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn outliers_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::outliers::{detect_outliers_dataset, OutlierMethod, OutlierOptions};
    use rust_data_processing::profiling::SamplingMode;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![Field::new("x", DataType::Float64)]),
        vec![
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1000.0)],
        ],
    );
    let rep = detect_outliers_dataset(
        &ds,
        "x",
        OutlierMethod::Iqr { k: 1.5 },
        &OutlierOptions {
            sampling: SamplingMode::Full,
            max_examples: 3,
        },
    )
    .map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "outliers_polars",
        "engine": "polars_stats_fences",
        "column": rep.column,
        "row_count": rep.row_count,
        "outlier_count": rep.outlier_count,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_transform(out: *mut RdpJsonSlice) {
    write_slice(out, parity_transform());
}

fn parity_transform() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match transform_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn transform_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::pipeline::CastMode;
    use rust_data_processing::transform::{TransformSpec, TransformStep};
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("score", DataType::Int64),
        ]),
        vec![
            vec![Value::Int64(1), Value::Int64(10)],
            vec![Value::Int64(2), Value::Null],
        ],
    );
    let out_schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("score_f", DataType::Float64),
    ]);
    let spec = TransformSpec::new(out_schema.clone())
        .with_step(TransformStep::Rename {
            pairs: vec![("score".to_string(), "score_f".to_string())],
        })
        .with_step(TransformStep::Cast {
            column: "score_f".to_string(),
            to: DataType::Float64,
            mode: CastMode::Lossy,
        })
        .with_step(TransformStep::FillNull {
            column: "score_f".to_string(),
            value: Value::Float64(0.0),
        });
    let out = spec.apply(&ds).map_err(|e| e.to_string())?;
    let dataset = serde_json::to_value(&out).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "kind": "transform_spec_polars",
        "engine": "polars_lazy_plan",
        "dataset": dataset,
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_cdc(out: *mut RdpJsonSlice) {
    write_slice(out, parity_cdc());
}

fn parity_cdc() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match cdc_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn cdc_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::cdc::{CdcEvent, CdcOp, RowImage, SourceMeta, TableRef};
    use rust_data_processing::types::Value;

    let ev = CdcEvent {
        meta: SourceMeta {
            source: Some("demo".to_string()),
            checkpoint: None,
        },
        table: TableRef::with_schema("public", "users"),
        op: CdcOp::Insert,
        before: None,
        after: Some(RowImage::new(vec![
            ("id".to_string(), Value::Int64(1)),
            ("name".to_string(), Value::Utf8("Ada".to_string())),
        ])),
    };
    Ok(serde_json::json!({
        "kind": "cdc_boundary_types",
        "engine": "crate_cdc_module",
        "event": {
            "op": format!("{:?}", ev.op),
            "table": ev.table.name,
            "schema": ev.table.schema,
        },
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_export_privacy_reports(out: *mut RdpJsonSlice) {
    write_slice(out, parity_export_privacy_reports());
}

fn parity_export_privacy_reports() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match export_privacy_reports_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn export_privacy_reports_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::export::{dataset_to_jsonl, train_test_row_indices};
    use rust_data_processing::privacy::{
        render_privacy_report_json, summarize_utf8_column_changes,
    };
    use rust_data_processing::reports::truncate_utf8_by_bytes;
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![Field::new("email", DataType::Utf8)]),
        vec![
            vec![Value::Utf8("a@x.com".to_string())],
            vec![Value::Utf8("b@x.com".to_string())],
        ],
    );
    let jsonl = dataset_to_jsonl(&ds, &["email".to_string()]).map_err(|e| e.to_string())?;
    let before = ds.clone();
    let mut after = ds.clone();
    after.rows[0][0] = Value::Utf8("masked".to_string());
    let privacy_rows = summarize_utf8_column_changes(&before, &after, &[String::from("email")]);
    let privacy_json = render_privacy_report_json(&privacy_rows).map_err(|e| e.to_string())?;
    let truncated = truncate_utf8_by_bytes(&privacy_json, 120);
    let (train, test) = train_test_row_indices(100, 0.2);
    Ok(serde_json::json!({
        "kind": "export_privacy_reports_phase2",
        "jsonl_preview_lines": jsonl.lines().take(2).collect::<Vec<_>>(),
        "privacy_report_json": serde_json::from_str::<serde_json::Value>(&privacy_json).unwrap_or(serde_json::Value::Null),
        "reports_truncated_sample": truncated,
        "train_test_indices_demo": { "train_len": train.len(), "test_len": test.len() },
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_kafka(out: *mut RdpJsonSlice) {
    write_slice(out, parity_kafka());
}

fn parity_kafka() -> RdpJsonSlice {
    json_ok(serde_json::json!({
        "kind": "kafka",
        "status": "in_progress",
        "model": "streaming_elt",
        "extract": "poll_kafka_window (rdp_kafka_poll_window_json FFI)",
        "load": "elt_load_kafka_records_json",
        "transform": "separate Polars SQL / rdp_run_pipeline_json",
        "docs": "docs/KAFKA_ELT.md",
        "tracker": "P3-E2",
    }))
}
