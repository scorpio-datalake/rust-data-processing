//! Single-document pipeline orchestration: `rdp_run_pipeline_json`.
//!
//! JVM passes **control-plane JSON** (local source paths, schema, optional Polars SQL, sinks).
//! Rust keeps the working frame in Polars; only **sink results** and summaries cross FFI — not
//! bulk row JSON unless a sink explicitly requests it later.
//!
//! Object-store source URIs (`s3://`, …) are rejected with a clear error until wired.
//! **Delta Lake / Iceberg** sinks return structured `status` (not wired in this build).
//! **PostgreSQL** sink is available when `rdp-jvm-sys` is built with `--features sink_postgres`.

use crate::ingest_path::parse_ingestion_options;
use crate::parity_support::{json_err, json_ok, write_slice, RdpJsonSlice};
use serde::Deserialize;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};

unsafe fn cstr_to_str<'a>(ptr: *const c_char, label: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{label}: null pointer"));
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("{label}: invalid UTF-8: {e}"))
}

fn default_empty_object() -> serde_json::Value {
    serde_json::json!({})
}

#[derive(Debug, Deserialize)]
struct SourcesSpec {
    paths: Vec<String>,
    schema: rust_data_processing::types::Schema,
    #[serde(default = "default_empty_object")]
    options: serde_json::Value,
}

#[derive(Debug, Deserialize, Default)]
struct TransformSpec {
    /// Polars SQL against the ingested frame registered as `df` (same convention as
    /// [`rust_data_processing::sql::query`]).
    #[serde(default)]
    sql: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum SinkSpec {
    /// Write the (optionally per-sink SQL projected) frame to a **local** Parquet file from Rust.
    ParquetFile {
        path: String,
        #[serde(default)]
        sql: Option<String>,
    },
    /// `libpq`-style URL (`postgresql://…`), not JDBC. Optional per-sink SQL on `df`.
    Postgresql {
        url: String,
        table: String,
        #[serde(default)]
        sql: Option<String>,
        /// When true, emit `CREATE TABLE IF NOT EXISTS` from the collected row schema.
        #[serde(default = "default_true")]
        create_table_if_missing: bool,
        #[serde(default)]
        truncate_before_load: bool,
    },
    /// Catalog / warehouse URLs are accepted for forward compatibility; connector wiring is tracked separately.
    DeltaLake {
        #[serde(default)]
        warehouse: Option<String>,
        #[serde(default)]
        catalog_uri: Option<String>,
        #[serde(default)]
        namespace: Option<String>,
        table: String,
        #[serde(default)]
        sql: Option<String>,
    },
    Iceberg {
        catalog_uri: String,
        #[serde(default)]
        warehouse: Option<String>,
        #[serde(default)]
        namespace: Option<String>,
        table: String,
        #[serde(default)]
        sql: Option<String>,
    },
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
struct RunPipelineRequest {
    #[serde(default)]
    version: Option<u32>,
    sources: SourcesSpec,
    #[serde(default)]
    transform: Option<TransformSpec>,
    sinks: Vec<SinkSpec>,
}

fn object_store_path_error(p: &str) -> Option<String> {
    let lower = p.to_ascii_lowercase();
    if lower.starts_with("s3://")
        || lower.starts_with("gs://")
        || lower.starts_with("gcs://")
        || lower.starts_with("abfs://")
        || lower.starts_with("azure://")
        || lower.starts_with("http://")
        || lower.starts_with("https://")
    {
        Some(format!(
            "sources.paths entry `{p}` uses an object-store or HTTP URI; only local filesystem paths are supported in this build"
        ))
    } else {
        None
    }
}

#[cfg(feature = "link-main")]
fn run_pipeline_impl(payload_json: &str) -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{export_dataset_to_parquet, ingest_from_ordered_paths};
    use rust_data_processing::pipeline::DataFrame;
    use rust_data_processing::sql;
    let RunPipelineRequest {
        version: _,
        sources,
        transform,
        sinks,
    } = serde_json::from_str::<RunPipelineRequest>(payload_json)
        .map_err(|e| format!("payload JSON: {e}"))?;

    for p in &sources.paths {
        if let Some(err) = object_store_path_error(p) {
            return Err(err);
        }
    }

    if sources.paths.is_empty() {
        return Err("sources.paths must be non-empty".into());
    }
    if sinks.is_empty() {
        return Err("sinks must be non-empty".into());
    }

    let paths_for_json: Vec<String> = sources.paths.clone();
    let path_bufs: Vec<PathBuf> = sources.paths.into_iter().map(PathBuf::from).collect();
    let opts_json = if sources.options.is_null() {
        "{}".to_string()
    } else {
        sources.options.to_string()
    };
    let schema = sources.schema;
    let opts = parse_ingestion_options(&opts_json, None)?;

    let (ingested_ds, meta) = ingest_from_ordered_paths(&path_bufs, &schema, &opts)
        .map_err(|e| format!("ingest_from_ordered_paths: {e}"))?;

    let mut base = DataFrame::from_dataset(&ingested_ds).map_err(|e| e.to_string())?;
    if let Some(t) = transform {
        if let Some(sql) = t.sql {
            let sql = sql.trim();
            if !sql.is_empty() {
                base = sql::query(&base, sql).map_err(|e| e.to_string())?;
            }
        }
    }

    let mut sink_results = Vec::new();

    for sink in sinks {
        let sink_sql = match &sink {
            SinkSpec::ParquetFile { sql, .. }
            | SinkSpec::Postgresql { sql, .. }
            | SinkSpec::DeltaLake { sql, .. }
            | SinkSpec::Iceberg { sql, .. } => sql.clone(),
        };
        let branch = if let Some(ref s) = sink_sql {
            let t = s.trim();
            if t.is_empty() {
                base.clone()
            } else {
                sql::query(&base, t).map_err(|e| e.to_string())?
            }
        } else {
            base.clone()
        };

        match sink {
            SinkSpec::ParquetFile { path, .. } => {
                let ds = branch.collect().map_err(|e| e.to_string())?;
                let out_path = Path::new(&path);
                if let Some(parent) = out_path.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
                }
                export_dataset_to_parquet(out_path, &ds).map_err(|e| e.to_string())?;
                sink_results.push(serde_json::json!({
                    "kind": "parquet_file",
                    "status": "ok",
                    "path": path,
                    "row_count": ds.row_count(),
                }));
            }
            SinkSpec::Postgresql {
                url,
                table,
                create_table_if_missing,
                truncate_before_load,
                ..
            } => {
                let ds = branch.collect().map_err(|e| e.to_string())?;
                #[cfg(feature = "sink_postgres")]
                {
                    match postgres_copy_sink(
                        &url,
                        &table,
                        &ds,
                        create_table_if_missing,
                        truncate_before_load,
                    ) {
                        Ok(row_count) => {
                            sink_results.push(serde_json::json!({
                                "kind": "postgresql",
                                "status": "ok",
                                "table": table,
                                "row_count": row_count,
                            }));
                        }
                        Err(e) => {
                            sink_results.push(serde_json::json!({
                                "kind": "postgresql",
                                "status": "error",
                                "table": table,
                                "error": e,
                            }));
                        }
                    }
                }
                #[cfg(not(feature = "sink_postgres"))]
                {
                    let _ = (url, table, create_table_if_missing, truncate_before_load, ds);
                    sink_results.push(serde_json::json!({
                        "kind": "postgresql",
                        "status": "skipped",
                        "reason": "rebuild rdp_jvm_sys with --features sink_postgres for libpq COPY from Polars-collected rows",
                    }));
                }
            }
            SinkSpec::DeltaLake {
                warehouse,
                catalog_uri,
                namespace,
                table,
                ..
            } => {
                drop(branch);
                sink_results.push(serde_json::json!({
                    "kind": "delta_lake",
                    "status": "connector_pending",
                    "table": table,
                    "warehouse": warehouse,
                    "catalog_uri": catalog_uri,
                    "namespace": namespace,
                    "detail": "Native Delta Lake write (transaction log + storage) is not linked in this rdp_jvm_sys build; use kind parquet_file to persist Polars output from Rust until the Delta connector is enabled.",
                }));
            }
            SinkSpec::Iceberg {
                catalog_uri,
                warehouse,
                namespace,
                table,
                ..
            } => {
                drop(branch);
                sink_results.push(serde_json::json!({
                    "kind": "iceberg",
                    "status": "connector_pending",
                    "table": table,
                    "catalog_uri": catalog_uri,
                    "warehouse": warehouse,
                    "namespace": namespace,
                    "detail": "Apache Iceberg REST/thrift catalog client is not linked in this rdp_jvm_sys build; use kind parquet_file for on-disk staging from Rust until the Iceberg connector is enabled.",
                }));
            }
        }
    }

    Ok(serde_json::json!({
        "kind": "run_pipeline_json",
        "engine": "ingest_ordered_paths_then_polars_sql_then_sinks",
        "paths": paths_for_json,
        "ingested_row_count": ingested_ds.row_count(),
        "ordered_batch": {
            "paths": meta.paths.iter().map(|p| p.to_string_lossy().to_string()).collect::<Vec<_>>(),
            "last_path": meta.last_path.as_ref().map(|p| p.to_string_lossy().to_string()),
        },
        "sink_results": sink_results,
    }))
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn postgres_copy_sink(
    url: &str,
    table: &str,
    ds: &rust_data_processing::types::DataSet,
    create_table_if_missing: bool,
    truncate_before_load: bool,
) -> Result<usize, String> {
    use postgres::{Client, NoTls};
    use rust_data_processing::types::{DataType, Value};

    let mut client = Client::connect(url, NoTls).map_err(|e| format!("postgres connect: {e}"))?;

    let (schema_part, table_part) = split_table_ident(table)?;
    let fq = format!(
        "{}.{}",
        quote_pg_ident(&schema_part),
        quote_pg_ident(&table_part)
    );

    if create_table_if_missing {
        let ddl = pg_create_table_ddl(&fq, &ds.schema)?;
        client
            .batch_execute(&ddl)
            .map_err(|e| format!("postgres create table: {e}"))?;
    }

    if truncate_before_load {
        client
            .execute(&format!("TRUNCATE TABLE {fq}"), [])
            .map_err(|e| format!("postgres truncate: {e}"))?;
    }

    let col_names: Vec<String> = ds
        .schema
        .fields
        .iter()
        .map(|f| f.name.clone())
        .collect();
    let col_list: String = col_names
        .iter()
        .map(|c| quote_pg_ident(c))
        .collect::<Vec<_>>()
        .join(", ");

    let copy_sql = format!(
        "COPY {fq} ({col_list}) FROM STDIN WITH (FORMAT text, NULL '\\N', ENCODING 'UTF8')"
    );

    let row_count = ds.row_count();
    if row_count == 0 {
        return Ok(0);
    }

    let mut writer = client
        .copy_in(&copy_sql)
        .map_err(|e| format!("postgres copy_in: {e}"))?;
    use std::io::Write;
    for row in &ds.rows {
        for (i, v) in row.iter().enumerate() {
            if i > 0 {
                writer.write_all(b"\t").map_err(|e| e.to_string())?;
            }
            append_copy_text_field(&mut writer, v)?;
        }
        writer.write_all(b"\n").map_err(|e| e.to_string())?;
    }
    writer
        .finish()
        .map_err(|e| format!("postgres copy finish: {e}"))?;

    Ok(row_count)
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn quote_pg_ident(id: &str) -> String {
    format!("\"{}\"", id.replace('"', "\"\""))
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn split_table_ident(table: &str) -> Result<(String, String), String> {
    let t = table.trim();
    if t.is_empty() {
        return Err("postgresql table is empty".into());
    }
    if !t
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.')
    {
        return Err(format!(
            "postgresql table `{t}` must be ascii alnum/underscore/dot only (schema.table)"
        ));
    }
    if let Some((s, rest)) = t.split_once('.') {
        if s.is_empty() || rest.is_empty() || rest.contains('.') {
            return Err(format!("invalid postgresql table `{t}`"));
        }
        Ok((s.to_string(), rest.to_string()))
    } else {
        Ok(("public".to_string(), t.to_string()))
    }
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn pg_type_sql(dt: &rust_data_processing::types::DataType) -> Result<&'static str, String> {
    use rust_data_processing::types::DataType;
    Ok(match dt {
        DataType::Int64 => "BIGINT",
        DataType::Float64 => "DOUBLE PRECISION",
        DataType::Bool => "BOOLEAN",
        DataType::Utf8 => "TEXT",
        DataType::Null => {
            return Err("schema field data_type Null is not supported for postgresql sink".into())
        }
    })
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn pg_create_table_ddl(fq: &str, schema: &rust_data_processing::types::Schema) -> Result<String, String> {
    let mut cols = Vec::new();
    for f in &schema.fields {
        let ty = pg_type_sql(&f.data_type)?;
        cols.push(format!("{} {}", quote_pg_ident(&f.name), ty));
    }
    Ok(format!(
        "CREATE TABLE IF NOT EXISTS {fq} ({})",
        cols.join(", ")
    ))
}

#[cfg(all(feature = "link-main", feature = "sink_postgres"))]
fn append_copy_text_field<W: std::io::Write>(w: &mut W, v: &rust_data_processing::types::Value) -> Result<(), String> {
    use rust_data_processing::types::Value;
    match v {
        Value::Null => w.write_all(br"\N").map_err(|e| e.to_string()),
        Value::Int64(i) => write!(w, "{i}").map_err(|e| e.to_string()),
        Value::Float64(f) => write!(w, "{f}").map_err(|e| e.to_string()),
        Value::Bool(b) => {
            if *b {
                w.write_all(b"t").map_err(|e| e.to_string())
            } else {
                w.write_all(b"f").map_err(|e| e.to_string())
            }
        }
        Value::Utf8(s) => {
            for ch in s.chars() {
                match ch {
                    '\\' => w.write_all(br"\\").map_err(|e| e.to_string())?,
                    '\n' => w.write_all(br"\n").map_err(|e| e.to_string())?,
                    '\r' => w.write_all(br"\r").map_err(|e| e.to_string())?,
                    '\t' => w.write_all(br"\t").map_err(|e| e.to_string())?,
                    c if ('\x01'..='\x08').contains(&c) || c == '\x0B' || c == '\x0C' || ('\x0E'..='\x1F').contains(&c) => {
                        return Err(format!("unsupported control character in Utf8 cell (U+{:04X})", c as u32));
                    }
                    c => {
                        let mut buf = [0u8; 4];
                        let sl = c.encode_utf8(&mut buf);
                        w.write_all(sl.as_bytes()).map_err(|e| e.to_string())?;
                    }
                }
            }
            Ok(())
        }
    }
}

/// JVM supplies one UTF‑8 JSON document: `sources` (paths, schema, options), optional `transform.sql`
/// on table `df`, and `sinks` (each optional `sql` on `df` before the sink action).
#[no_mangle]
pub unsafe extern "C" fn rdp_run_pipeline_json(
    out: *mut RdpJsonSlice,
    payload_json_ptr: *const c_char,
) {
    let slice = {
        #[cfg(feature = "link-main")]
        {
            match unsafe { cstr_to_str(payload_json_ptr, "payload_json") } {
                Ok(payload) => match run_pipeline_impl(payload) {
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
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn run_pipeline_parquet_sink_roundtrip() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("rdp_pipeline_run_test_{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let p1 = dir.join("a.json");
        std::fs::write(&p1, r#"[{"id":1,"name":"A"}]"#).unwrap();
        let p2 = dir.join("b.json");
        std::fs::write(&p2, r#"[{"id":2,"name":"B"}]"#).unwrap();
        let out_parquet = dir.join("out.parquet");

        let payload = serde_json::json!({
            "version": 1,
            "sources": {
                "paths": [p1.to_str().unwrap(), p2.to_str().unwrap()],
                "schema": {
                    "fields": [
                        {"name": "id", "data_type": "Int64"},
                        {"name": "name", "data_type": "Utf8"}
                    ]
                },
                "options": {"format": "json"}
            },
            "transform": {"sql": "SELECT * FROM df ORDER BY id"},
            "sinks": [
                {"kind": "parquet_file", "path": out_parquet.to_str().unwrap()}
            ]
        });

        let v = run_pipeline_impl(&payload.to_string()).unwrap();
        assert_eq!(v["ingested_row_count"], 2);
        assert!(out_parquet.exists());
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks[0]["status"], "ok");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
