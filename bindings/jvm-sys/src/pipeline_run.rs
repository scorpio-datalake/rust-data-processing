//! Single-document pipeline orchestration: `rdp_run_pipeline_json`.
//!
//! JVM passes **control-plane JSON** (local source paths, schema, optional Polars SQL, sinks), or
//! the legacy **student ETL** document (`json_source_paths`, `schema_student_json`, `lake_sink`,
//! `relational_sink`) produced by the Java example builders — same envelope, Rust-only execution.
//!
//! Rust keeps the working frame in Polars; only **sink results** and summaries cross FFI — not
//! bulk row JSON unless a sink explicitly requests it later.
//!
//! Object-store source URIs (`s3://`, …) are rejected with a clear error until object-store wiring
//! lands in this crate. **Delta Lake / Iceberg** sinks return structured `connector_pending` with
//! stable **`error_code`** until native table writers are linked (**`Planning/PHASE3_EPICS.md`**
//! **P3-E1-S10**). **PostgreSQL** runs a `COPY` load when `rdp-jvm-sys` is built with
//! **`--features sink_postgres`**. **`jdbc`** sinks are rejected in-tree — use **`postgresql`**
//! with **`postgresql://`** (libpq), not **`jdbc:`** URLs.
//!
//! **Orchestration** (**P3-E1-S9**): optional **`orchestration`** block supports **`timeout_ms`** (0 =
//! disabled), **`max_ingested_rows`** (0 = disabled), and **`idempotency_key`** (echoed only; no
//! dedupe yet). Failures use structured **`error.code`** / **`error.stage`** (see **`docs/adr/006-*.md`**).

use crate::ingest_path::parse_ingestion_options;
#[cfg(not(feature = "link-main"))]
use crate::parity_support::json_err;
use crate::parity_support::{json_err_structured, json_ok, write_slice, RdpJsonSlice};
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
    /// Write the frame to row-oriented `<rdp_records>` XML (see `rust_data_processing::ingestion::xml`).
    XmlFile {
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
    /// JDBC URLs are not executed in-tree; use **`postgresql`** sink with **`postgresql://`** (libpq).
    Jdbc {
        url: String,
        table: String,
        #[serde(default)]
        sql: Option<String>,
    },
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize, Default)]
struct OrchestrationSpec {
    /// Wall-clock budget; **0** = disabled. Checked after ingest, after transform, before each sink.
    #[serde(default)]
    timeout_ms: Option<u64>,
    /// Hard cap on ingested row count from ordered paths; **0** = disabled.
    #[serde(default)]
    max_ingested_rows: Option<usize>,
    /// Echoed in the success envelope for operators (no dedupe / idempotent commit in Rust yet).
    #[serde(default)]
    idempotency_key: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RunPipelineRequest {
    /// Explicit contract revision (**≥ 1**). Alias **`version`** accepted for back-compat.
    #[serde(default = "default_pipeline_spec_version", alias = "version")]
    pipeline_spec_version: u32,
    #[serde(default)]
    orchestration: Option<OrchestrationSpec>,
    sources: SourcesSpec,
    #[serde(default)]
    transform: Option<TransformSpec>,
    sinks: Vec<SinkSpec>,
}

fn default_pipeline_spec_version() -> u32 {
    1
}

/// Legacy control-plane shape from Java `syntheticPipelineSpec()` (docs example).
#[derive(Debug, Deserialize)]
struct LegacyStudentEtlSpec {
    #[serde(default)]
    engine: Option<String>,
    json_source_paths: Vec<String>,
    schema_student_json: rust_data_processing::types::Schema,
    #[serde(default)]
    schema_lake_grade_stats: Option<serde_json::Value>,
    #[serde(default)]
    schema_postgres_courses_teachers: Option<serde_json::Value>,
    lake_sink: serde_json::Value,
    relational_sink: serde_json::Value,
    #[serde(default)]
    notes: Option<String>,
}

#[derive(Debug, Deserialize)]
struct LegacyLakeSink {
    #[serde(default)]
    format: Option<String>,
    catalog_uri: String,
    #[serde(default)]
    warehouse: Option<String>,
    #[serde(default)]
    namespace: Option<String>,
    table_student_grades: String,
}

#[derive(Debug, Deserialize)]
struct LegacyRelationalSink {
    postgresql_url: String,
    courses_teachers_table: String,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum RunPipelineEnvelope {
    V1(RunPipelineRequest),
    Legacy(LegacyStudentEtlSpec),
}

fn legacy_student_etl_to_v1(spec: LegacyStudentEtlSpec) -> Result<(RunPipelineRequest, serde_json::Value), String> {
    let lake: LegacyLakeSink =
        serde_json::from_value(spec.lake_sink.clone()).map_err(|e| format!("lake_sink: {e}"))?;
    let rel: LegacyRelationalSink =
        serde_json::from_value(spec.relational_sink.clone()).map_err(|e| format!("relational_sink: {e}"))?;

    let declared = serde_json::json!({
        "engine": spec.engine,
        "notes": spec.notes,
        "schema_lake_grade_stats": spec.schema_lake_grade_stats,
        "schema_postgres_courses_teachers": spec.schema_postgres_courses_teachers,
        "lake_sink_format": lake.format,
    });

    let sinks = vec![
        SinkSpec::DeltaLake {
            warehouse: lake.warehouse.clone(),
            catalog_uri: Some(lake.catalog_uri.clone()),
            namespace: lake.namespace.clone(),
            table: lake.table_student_grades.clone(),
            sql: None,
        },
        SinkSpec::Iceberg {
            catalog_uri: lake.catalog_uri,
            warehouse: lake.warehouse,
            namespace: lake.namespace,
            table: lake.table_student_grades,
            sql: None,
        },
        SinkSpec::Postgresql {
            url: rel.postgresql_url,
            table: rel.courses_teachers_table,
            sql: None,
            create_table_if_missing: true,
            truncate_before_load: false,
        },
    ];

    Ok((
        RunPipelineRequest {
            pipeline_spec_version: 1,
            orchestration: None,
            sources: SourcesSpec {
                paths: spec.json_source_paths,
                schema: spec.schema_student_json,
                options: serde_json::json!({"format": "json"}),
            },
            transform: None,
            sinks,
        },
        declared,
    ))
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
#[derive(Debug)]
pub(crate) enum PipelineErr {
    Structured {
        code: &'static str,
        message: String,
        stage: &'static str,
    },
}

#[cfg(feature = "link-main")]
impl PipelineErr {
    pub(crate) fn structured(code: &'static str, message: impl Into<String>, stage: &'static str) -> Self {
        Self::Structured {
            code,
            message: message.into(),
            stage,
        }
    }

    pub(crate) fn into_slice(self) -> RdpJsonSlice {
        let Self::Structured { code, message, stage } = self;
        json_err_structured(code, message, Some(stage))
    }
}

#[cfg(feature = "link-main")]
fn pipeline_deadline(orch: &Option<OrchestrationSpec>) -> Option<std::time::Instant> {
    let ms = orch.as_ref()?.timeout_ms?;
    if ms == 0 {
        return None;
    }
    Some(std::time::Instant::now() + std::time::Duration::from_millis(ms))
}

#[cfg(feature = "link-main")]
fn check_deadline(deadline: &Option<std::time::Instant>, stage: &'static str) -> Result<(), PipelineErr> {
    if let Some(t) = deadline {
        if std::time::Instant::now() >= *t {
            return Err(PipelineErr::structured(
                "ORCHESTRATION_TIMEOUT",
                format!("wall-clock deadline exceeded (stage={stage})"),
                stage,
            ));
        }
    }
    Ok(())
}

#[cfg(feature = "link-main")]
fn run_pipeline_impl(payload_json: &str) -> Result<serde_json::Value, PipelineErr> {
    use rust_data_processing::ingestion::{
        export_dataset_to_parquet, export_dataset_to_xml, ingest_from_ordered_paths,
    };
    use rust_data_processing::pipeline::DataFrame;
    use rust_data_processing::sql;

    let t_start = std::time::Instant::now();

    let (
        RunPipelineRequest {
            pipeline_spec_version,
            orchestration,
            sources,
            transform,
            sinks,
        },
        legacy_declared,
    ) = match serde_json::from_str::<RunPipelineEnvelope>(payload_json) {
        Err(e) => {
            return Err(PipelineErr::structured(
                "ORCHESTRATION_JSON_INVALID",
                e.to_string(),
                "parse",
            ));
        }
        Ok(RunPipelineEnvelope::V1(r)) => (r, None),
        Ok(RunPipelineEnvelope::Legacy(l)) => {
            let (r, d) = legacy_student_etl_to_v1(l).map_err(|e| {
                PipelineErr::structured("ORCHESTRATION_VALIDATION", e, "parse")
            })?;
            (r, Some(d))
        }
    };

    if pipeline_spec_version == 0 {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VALIDATION",
            "pipeline_spec_version must be >= 1",
            "parse",
        ));
    }
    if pipeline_spec_version > 2 {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VERSION_UNSUPPORTED",
            format!("pipeline_spec_version {pipeline_spec_version} is not supported (max 2)"),
            "parse",
        ));
    }

    let deadline = pipeline_deadline(&orchestration);

    for p in &sources.paths {
        if let Some(err) = object_store_path_error(p) {
            return Err(PipelineErr::structured(
                "OBJECT_STORE_SOURCE_NOT_SUPPORTED",
                err,
                "ingest",
            ));
        }
    }

    if sources.paths.is_empty() {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VALIDATION",
            "sources.paths must be non-empty",
            "parse",
        ));
    }
    if sinks.is_empty() {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VALIDATION",
            "sinks must be non-empty",
            "parse",
        ));
    }

    check_deadline(&deadline, "parse")?;

    let paths_for_json: Vec<String> = sources.paths.clone();
    let path_bufs: Vec<PathBuf> = sources.paths.into_iter().map(PathBuf::from).collect();
    let opts_json = if sources.options.is_null() {
        "{}".to_string()
    } else {
        sources.options.to_string()
    };
    let schema = sources.schema;
    let opts = parse_ingestion_options(&opts_json, None).map_err(|e| {
        PipelineErr::structured("ORCHESTRATION_VALIDATION", e, "parse")
    })?;

    let (ingested_ds, meta) = ingest_from_ordered_paths(&path_bufs, &schema, &opts).map_err(|e| {
        PipelineErr::structured("INGEST_FAILED", format!("ingest_from_ordered_paths: {e}"), "ingest")
    })?;

    check_deadline(&deadline, "ingest")?;

    if let Some(ref orch) = orchestration {
        if let Some(limit) = orch.max_ingested_rows {
            if limit > 0 && ingested_ds.row_count() > limit {
                return Err(PipelineErr::structured(
                    "ORCHESTRATION_ROW_BUDGET",
                    format!(
                        "ingested {} rows exceed max_ingested_rows {}",
                        ingested_ds.row_count(),
                        limit
                    ),
                    "ingest",
                ));
            }
        }
    }

    let mut base = DataFrame::from_dataset(&ingested_ds).map_err(|e| {
        PipelineErr::structured("DATAFRAME_MATERIALIZATION_FAILED", e.to_string(), "transform")
    })?;
    check_deadline(&deadline, "transform")?;
    if let Some(t) = transform {
        if let Some(sql) = t.sql {
            let sql = sql.trim();
            if !sql.is_empty() {
                base = sql::query(&base, sql).map_err(|e| {
                    PipelineErr::structured("TRANSFORM_SQL_FAILED", e.to_string(), "transform")
                })?;
            }
        }
    }

    let mut sink_results = Vec::new();

    for sink in sinks {
        check_deadline(&deadline, "sink")?;

        let sink_sql = match &sink {
            SinkSpec::ParquetFile { sql, .. }
            | SinkSpec::XmlFile { sql, .. }
            | SinkSpec::Postgresql { sql, .. }
            | SinkSpec::DeltaLake { sql, .. }
            | SinkSpec::Iceberg { sql, .. }
            | SinkSpec::Jdbc { sql, .. } => sql.clone(),
        };
        let branch = if let Some(ref s) = sink_sql {
            let t = s.trim();
            if t.is_empty() {
                base.clone()
            } else {
                sql::query(&base, t).map_err(|e| {
                    PipelineErr::structured("TRANSFORM_SQL_FAILED", e.to_string(), "sink_sql")
                })?
            }
        } else {
            base.clone()
        };

        match sink {
            SinkSpec::ParquetFile { path, .. } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let out_path = Path::new(&path);
                if let Some(parent) = out_path.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| {
                        PipelineErr::structured("PARQUET_SINK_IO_FAILED", e.to_string(), "sink")
                    })?;
                }
                export_dataset_to_parquet(out_path, &ds).map_err(|e| {
                    PipelineErr::structured("PARQUET_SINK_WRITE_FAILED", e.to_string(), "sink")
                })?;
                sink_results.push(serde_json::json!({
                    "kind": "parquet_file",
                    "status": "ok",
                    "path": path,
                    "row_count": ds.row_count(),
                }));
            }
            SinkSpec::XmlFile { path, .. } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let out_path = Path::new(&path);
                if let Some(parent) = out_path.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| {
                        PipelineErr::structured("XML_SINK_IO_FAILED", e.to_string(), "sink")
                    })?;
                }
                export_dataset_to_xml(out_path, &ds).map_err(|e| {
                    PipelineErr::structured("XML_SINK_WRITE_FAILED", e.to_string(), "sink")
                })?;
                sink_results.push(serde_json::json!({
                    "kind": "xml_file",
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
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
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
                                "error_code": "POSTGRES_SINK_FAILED",
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
                        "error_code": "POSTGRES_SINK_NOT_BUILT",
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
                    "error_code": "DELTA_LAKE_CONNECTOR_PENDING",
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
                    "error_code": "ICEBERG_CONNECTOR_PENDING",
                    "table": table,
                    "catalog_uri": catalog_uri,
                    "warehouse": warehouse,
                    "namespace": namespace,
                    "detail": "Apache Iceberg REST/thrift catalog client is not linked in this rdp_jvm_sys build; use kind parquet_file for on-disk staging from Rust until the Iceberg connector is enabled.",
                }));
            }
            SinkSpec::Jdbc { url, table, .. } => {
                drop(branch);
                let jdbc = url.to_ascii_lowercase().starts_with("jdbc:");
                let detail = if jdbc {
                    "JDBC URLs are not executed in-tree. Use sink kind postgresql with a libpq postgresql:// URL, or parquet_file for local staging."
                } else {
                    "Generic JDBC is not linked in this build. Use postgresql sink with postgresql:// or parquet_file."
                };
                sink_results.push(serde_json::json!({
                    "kind": "jdbc",
                    "status": "unsupported",
                    "error_code": "JDBC_PROTOCOL_NOT_LINKED",
                    "table": table,
                    "detail": detail,
                }));
            }
        }
    }

    let elapsed_ms = t_start.elapsed().as_millis() as u64;
    let mut out = serde_json::json!({
        "kind": "run_pipeline_json",
        "engine": "ingest_ordered_paths_then_polars_sql_then_sinks",
        "paths": paths_for_json,
        "ingested_row_count": ingested_ds.row_count(),
        "ordered_batch": {
            "paths": meta.paths.iter().map(|p| p.to_string_lossy().to_string()).collect::<Vec<_>>(),
            "last_path": meta.last_path.as_ref().map(|p| p.to_string_lossy().to_string()),
        },
        "orchestration": {
            "pipeline_spec_version": pipeline_spec_version,
            "idempotency_key": orchestration.as_ref().and_then(|o| o.idempotency_key.clone()),
            "elapsed_ms": elapsed_ms,
            "timeout_ms": orchestration.as_ref().and_then(|o| o.timeout_ms),
            "max_ingested_rows": orchestration.as_ref().and_then(|o| o.max_ingested_rows),
        },
        "sink_results": sink_results,
    });
    if let Some(d) = legacy_declared {
        out["declared_staging_schemas"] = d;
    }
    Ok(out)
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
    use std::io::Write;
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
                    Err(e) => e.into_slice(),
                },
                Err(e) => json_err_structured("ORCHESTRATION_VALIDATION", e, Some("parse")),
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
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

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

        let bundle = PipelineBundle::from_repo_fixture("jvm_contract");
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/ordered_json_to_parquet.pipeline.json",
                &HashMap::from([
                    ("SOURCE_PATH_A".into(), p1.to_string_lossy().into_owned()),
                    ("SOURCE_PATH_B".into(), p2.to_string_lossy().into_owned()),
                    (
                        "SINK_PATH".into(),
                        out_parquet.to_string_lossy().into_owned(),
                    ),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"], 2);
        assert_eq!(v["orchestration"]["pipeline_spec_version"], 1);
        assert!(v["orchestration"]["elapsed_ms"].as_u64().is_some());
        assert!(out_parquet.exists());
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks[0]["status"], "ok");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn run_pipeline_legacy_student_etl_envelope() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("rdp_pipeline_legacy_test_{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let p1 = dir.join("part-0.json");
        std::fs::write(
            &p1,
            r#"[{"student_id":1,"legal_name":"A","email":"a@x","homeroom":"1","gpa":3.5,"enrollment_year":2024}]"#,
        )
        .unwrap();

        let bundle = PipelineBundle::from_repo_fixture("student_etl");
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/legacy_student_etl.pipeline.json",
                &HashMap::from([("SOURCE_PATH".into(), p1.to_string_lossy().into_owned())]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"].as_i64(), Some(1));
        assert!(v.get("declared_staging_schemas").is_some());
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks.len(), 3);
        assert_eq!(sinks[0]["kind"].as_str(), Some("delta_lake"));
        assert_eq!(sinks[0]["error_code"].as_str(), Some("DELTA_LAKE_CONNECTOR_PENDING"));
        assert_eq!(sinks[1]["kind"].as_str(), Some("iceberg"));
        assert_eq!(sinks[1]["error_code"].as_str(), Some("ICEBERG_CONNECTOR_PENDING"));
        assert_eq!(sinks[2]["kind"].as_str(), Some("postgresql"));
        assert_eq!(sinks[2]["error_code"].as_str(), Some("POSTGRES_SINK_NOT_BUILT"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Same committed parts as `docs/java/examples/RDPOnlyETLExample.java` (`student_etl/data/part-*.json`).
    #[test]
    fn run_pipeline_legacy_student_etl_three_committed_parts() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let bundle = PipelineBundle::from_repo_fixture("student_etl");
        let root = bundle.root();
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/legacy_student_etl_three_paths.pipeline.json",
                &HashMap::from([
                    (
                        "PATH_A".into(),
                        root.join("data/part-00000.json")
                            .to_string_lossy()
                            .into_owned(),
                    ),
                    (
                        "PATH_B".into(),
                        root.join("data/part-00001.json")
                            .to_string_lossy()
                            .into_owned(),
                    ),
                    (
                        "PATH_C".into(),
                        root.join("data/part-00002.json")
                            .to_string_lossy()
                            .into_owned(),
                    ),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"].as_i64(), Some(3));
        assert!(v.get("declared_staging_schemas").is_some());
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks.len(), 3);
        assert_eq!(sinks[0]["error_code"].as_str(), Some("DELTA_LAKE_CONNECTOR_PENDING"));
    }

    /// Same pipeline as `docs/java/examples/ParquetSnippets.java` (`people/pipelines/csv_to_parquet.pipeline.json`).
    #[test]
    fn run_pipeline_people_csv_to_parquet_committed_fixture() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
        let csv = repo.join("tests/fixtures/people.csv");
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let out = std::env::temp_dir().join(format!("rdp_people_parquet_{stamp}.parquet"));

        let bundle = PipelineBundle::from_repo_fixture("people");
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/csv_to_parquet.pipeline.json",
                &HashMap::from([
                    (
                        "SOURCE_PATH".into(),
                        csv.to_string_lossy().into_owned(),
                    ),
                    (
                        "SINK_PATH".into(),
                        out.to_string_lossy().into_owned(),
                    ),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks.len(), 1);
        assert_eq!(sinks[0]["kind"].as_str(), Some("parquet_file"));
        assert_eq!(sinks[0]["status"].as_str(), Some("ok"));
        assert_eq!(sinks[0]["row_count"].as_i64(), Some(2));
        assert!(out.is_file());
        let _ = std::fs::remove_file(out);
    }

    #[test]
    fn run_pipeline_invalid_json_returns_structured_error() {
        let err = run_pipeline_impl("{not json").unwrap_err();
        assert!(matches!(
            err,
            PipelineErr::Structured {
                code: "ORCHESTRATION_JSON_INVALID",
                ..
            }
        ));
    }

    #[test]
    fn run_pipeline_row_budget_exceeded() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("rdp_pipeline_budget_test_{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let p1 = dir.join("a.json");
        std::fs::write(&p1, r#"[{"id":1,"name":"A"},{"id":2,"name":"B"}]"#).unwrap();
        let out_parquet = dir.join("out.parquet");

        let payload = serde_json::json!({
            "pipeline_spec_version": 1,
            "orchestration": { "max_ingested_rows": 1 },
            "sources": {
                "paths": [p1.to_str().unwrap()],
                "schema": {
                    "fields": [
                        {"name": "id", "data_type": "Int64"},
                        {"name": "name", "data_type": "Utf8"}
                    ]
                },
                "options": {"format": "json"}
            },
            "sinks": [
                {"kind": "parquet_file", "path": out_parquet.to_str().unwrap()}
            ]
        });

        let err = run_pipeline_impl(&payload.to_string()).unwrap_err();
        assert!(matches!(
            err,
            PipelineErr::Structured {
                code: "ORCHESTRATION_ROW_BUDGET",
                ..
            }
        ));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn run_pipeline_spec_version_unsupported() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("rdp_pipeline_ver_test_{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let p1 = dir.join("a.json");
        std::fs::write(&p1, r#"[{"id":1,"name":"A"}]"#).unwrap();
        let out_parquet = dir.join("out.parquet");

        let payload = serde_json::json!({
            "pipeline_spec_version": 99,
            "sources": {
                "paths": [p1.to_str().unwrap()],
                "schema": {
                    "fields": [
                        {"name": "id", "data_type": "Int64"},
                        {"name": "name", "data_type": "Utf8"}
                    ]
                },
                "options": {"format": "json"}
            },
            "sinks": [
                {"kind": "parquet_file", "path": out_parquet.to_str().unwrap()}
            ]
        });

        let err = run_pipeline_impl(&payload.to_string()).unwrap_err();
        assert!(matches!(
            err,
            PipelineErr::Structured {
                code: "ORCHESTRATION_VERSION_UNSUPPORTED",
                ..
            }
        ));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
