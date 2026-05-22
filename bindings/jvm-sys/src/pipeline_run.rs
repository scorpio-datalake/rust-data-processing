//! Single-document pipeline orchestration: `rdp_run_pipeline_json`.
//!
//! JVM passes **control-plane JSON** (local source paths, schema, optional Polars SQL, sinks), or
//! the legacy **student ETL** document (`json_source_paths`, `schema_student_json`, `lake_sink`,
//! `relational_sink`) produced by the Java example builders — same envelope, Rust-only execution.
//!
//! Rust keeps the working frame in Polars; only **sink results** and summaries cross FFI — not
//! bulk row JSON unless a sink explicitly requests it later.
//!
//! Object-store URIs belong in **`sources.object_store_uris`**; remote SQL belongs in **`sources.db_reads`**
//! (ConnectorX URLs, feature **`db_connectorx`**). Local **`sources.paths`** are filesystem paths. Rust
//! reads/writes cloud URIs via **`object_store`** when built with **`cloud_connectors`**. **Iceberg** sinks
//! remain **`connector_pending`**. **PostgreSQL** runs a `COPY` load when `rdp-jvm-sys` is built with
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
struct DbReadSpec {
    /// ConnectorX URL (`postgresql://`, `oracle://`, `mssql://`, `mysql://`) — not `jdbc:`.
    url: String,
    query: String,
}

#[derive(Debug, Deserialize)]
struct SourcesSpec {
    paths: Vec<String>,
    schema: rust_data_processing::types::Schema,
    #[serde(default = "default_empty_object")]
    options: serde_json::Value,
    /// Cloud object-store read URIs (`s3://`, `gs://`, `gcs://`, `abfss://`, `azure://`, `file://`).
    #[serde(default)]
    object_store_uris: Vec<String>,
    /// Remote SQL reads via ConnectorX (feature `db_connectorx` on `rdp_jvm_sys`).
    #[serde(default)]
    db_reads: Vec<DbReadSpec>,
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
    /// Snowflake table load (account URL + stage); connector not linked in-tree yet.
    Snowflake {
        account_url: String,
        #[serde(default)]
        warehouse: Option<String>,
        #[serde(default)]
        database: Option<String>,
        #[serde(default)]
        schema: Option<String>,
        table: String,
        #[serde(default)]
        stage_uri: Option<String>,
        #[serde(default)]
        role: Option<String>,
        #[serde(default)]
        sql: Option<String>,
    },
    /// Databricks / Unity Catalog Delta write; connector not linked in-tree yet.
    Databricks {
        workspace_url: String,
        #[serde(default)]
        catalog_uri: Option<String>,
        #[serde(default)]
        warehouse: Option<String>,
        #[serde(default)]
        namespace: Option<String>,
        table: String,
        #[serde(default)]
        sql: Option<String>,
    },
    /// Spark cluster handoff metadata (read path for `spark.read.*`); connector not linked in-tree yet.
    Spark {
        #[serde(default)]
        master: Option<String>,
        #[serde(default)]
        app_name: Option<String>,
        /// Where Spark should read after Rust staging (`file://`, `s3://`, …).
        handoff_uri: String,
        #[serde(default)]
        sql: Option<String>,
    },
    /// Write Polars output to cloud object storage (`s3://`, `gs://`, `abfss://`, …); not linked yet.
    ObjectStore {
        uri: String,
        #[serde(default)]
        format: Option<String>,
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
                object_store_uris: Vec::new(),
                db_reads: Vec::new(),
            },
            transform: None,
            sinks,
        },
        declared,
    ))
}

fn is_object_store_uri(p: &str) -> bool {
    let lower = p.to_ascii_lowercase();
    lower.starts_with("s3://")
        || lower.starts_with("gs://")
        || lower.starts_with("gcs://")
        || lower.starts_with("abfs://")
        || lower.starts_with("abfss://")
        || lower.starts_with("azure://")
        || lower.starts_with("az://")
        || lower.starts_with("file://")
        || lower.starts_with("https://")
        || lower.starts_with("http://")
}

fn object_store_scheme(p: &str) -> &'static str {
    let lower = p.to_ascii_lowercase();
    if lower.starts_with("s3://") {
        "s3"
    } else if lower.starts_with("gs://") || lower.starts_with("gcs://") {
        "gcs"
    } else if lower.starts_with("abfss://") || lower.starts_with("abfs://") {
        "abfs"
    } else if lower.starts_with("azure://") || lower.starts_with("az://") {
        "azure"
    } else if lower.starts_with("file://") {
        "file"
    } else {
        "http"
    }
}

fn object_store_path_error(p: &str) -> Option<String> {
    if is_object_store_uri(p) {
        Some(format!(
            "sources.paths entry `{p}` uses an object-store or HTTP URI; use local filesystem paths for ingest and declare cloud URIs in sources.object_store_uris"
        ))
    } else {
        None
    }
}

fn db_read_url_error(url: &str) -> Option<String> {
    let lower = url.to_ascii_lowercase();
    if lower.starts_with("jdbc:") {
        return Some(format!(
            "sources.db_reads.url must use ConnectorX form (oracle://, mssql://, postgresql://, mysql://), not JDBC `{url}`"
        ));
    }
    if lower.starts_with("postgresql://")
        || lower.starts_with("oracle://")
        || lower.starts_with("mssql://")
        || lower.starts_with("mysql://")
    {
        None
    } else {
        Some(format!(
            "sources.db_reads.url `{url}` has unsupported scheme; expected postgresql://, oracle://, mssql://, or mysql://"
        ))
    }
}

#[cfg(feature = "link-main")]
fn export_dataset_to_handoff_uri(uri: &str, ds: &rust_data_processing::types::DataSet) -> Result<usize, PipelineErr> {
    use rust_data_processing::ingestion::{
        export_dataset_to_object_store_uri, export_dataset_to_parquet,
    };

    let rows = ds.row_count();
    if is_object_store_uri(uri) {
        export_dataset_to_object_store_uri(uri, ds).map_err(|e| {
            PipelineErr::structured("OBJECT_STORE_SINK_FAILED", format!("{e}"), "sink")
        })?;
    } else if let Some(path) = uri.strip_prefix("file://") {
        let p = Path::new(path);
        if let Some(parent) = p.parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                PipelineErr::structured("PARQUET_SINK_IO_FAILED", e.to_string(), "sink")
            })?;
        }
        export_dataset_to_parquet(p, ds).map_err(|e| {
            PipelineErr::structured("PARQUET_SINK_WRITE_FAILED", format!("{e}"), "sink")
        })?;
    } else {
        let p = Path::new(uri);
        if let Some(parent) = p.parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                PipelineErr::structured("PARQUET_SINK_IO_FAILED", e.to_string(), "sink")
            })?;
        }
        export_dataset_to_parquet(p, ds).map_err(|e| {
            PipelineErr::structured("PARQUET_SINK_WRITE_FAILED", format!("{e}"), "sink")
        })?;
    }
    Ok(rows)
}

#[cfg(feature = "link-main")]
fn ingest_db_reads(
    db_reads: &[DbReadSpec],
    schema: &rust_data_processing::types::Schema,
    opts: &rust_data_processing::ingestion::IngestionOptions,
) -> Result<(Vec<serde_json::Value>, Vec<Vec<rust_data_processing::types::Value>>), PipelineErr> {
    if db_reads.is_empty() {
        return Ok((Vec::new(), Vec::new()));
    }
    #[cfg(not(feature = "db_connectorx"))]
    {
        let _ = (schema, opts);
        return Err(PipelineErr::structured(
            "DB_CONNECTORX_NOT_BUILT",
            "sources.db_reads requires rdp_jvm_sys built with --features db_connectorx (or full)",
            "ingest",
        ));
    }
    #[cfg(feature = "db_connectorx")]
    {
        use rust_data_processing::ingestion::ingest_from_db;

        let mut results = Vec::new();
        let mut rows = Vec::new();
        for db in db_reads {
            match ingest_from_db(&db.url, &db.query, schema, opts) {
                Ok(ds) => {
                    results.push(serde_json::json!({
                        "url": db.url,
                        "status": "ok",
                        "row_count": ds.row_count(),
                    }));
                    rows.extend(ds.rows);
                }
                Err(e) => {
                    return Err(PipelineErr::structured(
                        "DB_SOURCE_FAILED",
                        format!("ingest db `{}`: {e}", db.url),
                        "ingest",
                    ));
                }
            }
        }
        Ok((results, rows))
    }
}

#[cfg(feature = "link-main")]
fn ingest_pipeline_sources(
    local_paths: Vec<PathBuf>,
    object_store_uris: &[String],
    db_reads: &[DbReadSpec],
    schema: &rust_data_processing::types::Schema,
    opts: &rust_data_processing::ingestion::IngestionOptions,
) -> Result<
    (
        rust_data_processing::types::DataSet,
        rust_data_processing::ingestion::OrderedBatchIngestMetadata,
        Vec<serde_json::Value>,
        Vec<serde_json::Value>,
    ),
    PipelineErr,
> {
    use rust_data_processing::ingestion::{
        apply_watermark_after_ingest, ingest_from_object_store_uri, ingest_from_ordered_paths,
    };
    use rust_data_processing::types::{DataSet, Value};

    let mut all_rows: Vec<Vec<Value>> = Vec::new();
    let mut batch_meta = rust_data_processing::ingestion::OrderedBatchIngestMetadata {
        paths: Vec::new(),
        last_path: None,
        max_watermark_value: None,
    };
    let mut object_store_source_results = Vec::new();

    if !local_paths.is_empty() {
        let (ds, meta) = ingest_from_ordered_paths(&local_paths, schema, opts).map_err(|e| {
            PipelineErr::structured("INGEST_FAILED", format!("ingest_from_ordered_paths: {e}"), "ingest")
        })?;
        all_rows.extend(ds.rows);
        batch_meta = meta;
    }

    for uri in object_store_uris {
        match ingest_from_object_store_uri(uri, schema, opts) {
            Ok(ds) => {
                object_store_source_results.push(serde_json::json!({
                    "uri": uri,
                    "scheme": object_store_scheme(uri),
                    "status": "ok",
                    "row_count": ds.row_count(),
                }));
                all_rows.extend(ds.rows);
            }
            Err(e) => {
                return Err(PipelineErr::structured(
                    "OBJECT_STORE_SOURCE_FAILED",
                    format!("ingest `{uri}`: {e}"),
                    "ingest",
                ));
            }
        }
    }

    let (db_source_results, db_rows) = ingest_db_reads(db_reads, schema, opts)?;
    all_rows.extend(db_rows);

    if all_rows.is_empty() {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VALIDATION",
            "no rows ingested: provide sources.paths, sources.object_store_uris, and/or sources.db_reads",
            "ingest",
        ));
    }

    let mut ingested = DataSet::new(schema.clone(), all_rows);
    ingested = apply_watermark_after_ingest(ingested, schema, opts).map_err(|e| {
        PipelineErr::structured("INGEST_FAILED", format!("watermark: {e}"), "ingest")
    })?;

    if let Some(col) = &opts.watermark_column {
        batch_meta.max_watermark_value =
            rust_data_processing::ingestion::max_value_in_column(&ingested, schema, col);
    }

    Ok((ingested, batch_meta, object_store_source_results, db_source_results))
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
    use rust_data_processing::ingestion::{export_dataset_to_parquet, export_dataset_to_xml};
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
    let object_store_uris = sources.object_store_uris.clone();
    for u in &object_store_uris {
        if !is_object_store_uri(u) {
            return Err(PipelineErr::structured(
                "ORCHESTRATION_VALIDATION",
                format!(
                    "sources.object_store_uris entry `{u}` must be an object-store URI (s3://, gs://, gcs://, abfs://, abfss://, azure://, or https://)"
                ),
                "parse",
            ));
        }
    }
    for p in &sources.paths {
        if let Some(err) = object_store_path_error(p) {
            return Err(PipelineErr::structured(
                "OBJECT_STORE_SOURCE_NOT_SUPPORTED",
                err,
                "ingest",
            ));
        }
    }

    for db in &sources.db_reads {
        if let Some(err) = db_read_url_error(&db.url) {
            return Err(PipelineErr::structured(
                "DB_SOURCE_URL_INVALID",
                err,
                "parse",
            ));
        }
        if db.query.trim().is_empty() {
            return Err(PipelineErr::structured(
                "ORCHESTRATION_VALIDATION",
                "sources.db_reads.query must be non-empty",
                "parse",
            ));
        }
    }

    if sources.paths.is_empty() && object_store_uris.is_empty() && sources.db_reads.is_empty() {
        return Err(PipelineErr::structured(
            "ORCHESTRATION_VALIDATION",
            "sources must declare at least one of paths, object_store_uris, or db_reads",
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

    let db_reads = sources.db_reads;
    let (ingested_ds, meta, object_store_source_results, db_source_results) =
        ingest_pipeline_sources(path_bufs, &object_store_uris, &db_reads, &schema, &opts)?;

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
            | SinkSpec::Jdbc { sql, .. }
            | SinkSpec::Snowflake { sql, .. }
            | SinkSpec::Databricks { sql, .. }
            | SinkSpec::Spark { sql, .. }
            | SinkSpec::ObjectStore { sql, .. } => sql.clone(),
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
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let wh = warehouse.as_deref().ok_or_else(|| {
                    PipelineErr::structured(
                        "ORCHESTRATION_VALIDATION",
                        "delta_lake sink requires warehouse (s3://, abfss://, or file://)",
                        "sink",
                    )
                })?;
                let table_uri = rust_data_processing::ingestion::delta_table_uri(
                    wh,
                    namespace.as_deref(),
                    &table,
                );
                match rust_data_processing::ingestion::write_dataset_to_delta_table(&table_uri, &ds) {
                    Ok(row_count) => {
                        sink_results.push(serde_json::json!({
                            "kind": "delta_lake",
                            "status": "ok",
                            "table": table,
                            "table_uri": table_uri,
                            "warehouse": warehouse,
                            "catalog_uri": catalog_uri,
                            "namespace": namespace,
                            "row_count": row_count,
                        }));
                    }
                    Err(e) => {
                        sink_results.push(serde_json::json!({
                            "kind": "delta_lake",
                            "status": "error",
                            "error_code": "DELTA_LAKE_SINK_FAILED",
                            "table_uri": table_uri,
                            "error": format!("{e:?}"),
                        }));
                    }
                }
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
            SinkSpec::Snowflake {
                account_url,
                warehouse,
                database,
                schema: sf_schema,
                table,
                stage_uri,
                role,
                ..
            } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let stage = stage_uri.as_deref().ok_or_else(|| {
                    PipelineErr::structured(
                        "ORCHESTRATION_VALIDATION",
                        "snowflake sink requires stage_uri (s3://, gs://, abfss://, or file://)",
                        "sink",
                    )
                })?;
                let stage_parquet = if stage.ends_with('/') {
                    format!("{stage}load.parquet")
                } else {
                    format!("{stage}.parquet")
                };
                match rust_data_processing::ingestion::write_dataset_to_snowflake_stage(
                    &stage_parquet,
                    &ds,
                ) {
                    Ok(row_count) => {
                        let copy_ok = rust_data_processing::ingestion::copy_into_table_from_stage(
                            &account_url,
                            warehouse.as_deref(),
                            database.as_deref(),
                            sf_schema.as_deref(),
                            &table,
                            &stage_parquet,
                            role.as_deref(),
                        );
                        let (copy_status, copy_detail) = match copy_ok {
                            Ok(()) => ("ok", None),
                            Err(e) => (
                                "skipped",
                                Some(format!(
                                    "stage write ok; COPY INTO skipped ({e}). Set SNOWFLAKE_USER/PASSWORD and feature snowflake for automatic COPY."
                                )),
                            ),
                        };
                        sink_results.push(serde_json::json!({
                            "kind": "snowflake",
                            "status": "ok",
                            "account_url": account_url,
                            "table": table,
                            "stage_uri": stage_parquet,
                            "row_count": row_count,
                            "copy_status": copy_status,
                            "copy_detail": copy_detail,
                        }));
                    }
                    Err(e) => {
                        sink_results.push(serde_json::json!({
                            "kind": "snowflake",
                            "status": "error",
                            "error_code": "SNOWFLAKE_SINK_FAILED",
                            "error": format!("{e:?}"),
                        }));
                    }
                }
            }
            SinkSpec::Databricks {
                workspace_url,
                catalog_uri,
                warehouse,
                namespace,
                table,
                ..
            } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let wh = warehouse.as_deref().ok_or_else(|| {
                    PipelineErr::structured(
                        "ORCHESTRATION_VALIDATION",
                        "databricks sink requires warehouse (abfss:// or s3://)",
                        "sink",
                    )
                })?;
                let table_uri = rust_data_processing::ingestion::delta_table_uri(
                    wh,
                    namespace.as_deref(),
                    &table,
                );
                match rust_data_processing::ingestion::write_dataset_to_delta_table(&table_uri, &ds) {
                    Ok(row_count) => {
                        sink_results.push(serde_json::json!({
                            "kind": "databricks",
                            "status": "ok",
                            "workspace_url": workspace_url,
                            "catalog_uri": catalog_uri,
                            "table_uri": table_uri,
                            "row_count": row_count,
                        }));
                    }
                    Err(e) => {
                        sink_results.push(serde_json::json!({
                            "kind": "databricks",
                            "status": "error",
                            "error_code": "DATABRICKS_SINK_FAILED",
                            "table_uri": table_uri,
                            "error": format!("{e:?}"),
                        }));
                    }
                }
            }
            SinkSpec::Spark {
                master,
                app_name,
                handoff_uri,
                ..
            } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                match export_dataset_to_handoff_uri(&handoff_uri, &ds) {
                    Ok(row_count) => {
                        sink_results.push(serde_json::json!({
                            "kind": "spark",
                            "status": "ok",
                            "master": master,
                            "app_name": app_name,
                            "handoff_uri": handoff_uri,
                            "row_count": row_count,
                            "detail": "Parquet written by Rust at handoff_uri; Spark driver reads with spark.read.parquet (no JVM connector in rdp_jvm_sys).",
                        }));
                    }
                    Err(e) => {
                        sink_results.push(serde_json::json!({
                            "kind": "spark",
                            "status": "error",
                            "error_code": "SPARK_HANDOFF_FAILED",
                            "handoff_uri": handoff_uri,
                            "error": format!("{e:?}"),
                        }));
                    }
                }
            }
            SinkSpec::ObjectStore { uri, format, .. } => {
                let ds = branch.collect().map_err(|e| {
                    PipelineErr::structured("SINK_MATERIALIZATION_FAILED", e.to_string(), "sink")
                })?;
                let target = if uri.ends_with('/') {
                    format!("{uri}data.parquet")
                } else {
                    uri.clone()
                };
                match rust_data_processing::ingestion::export_dataset_to_object_store_uri(&target, &ds)
                {
                    Ok(()) => {
                        sink_results.push(serde_json::json!({
                            "kind": "object_store",
                            "status": "ok",
                            "uri": target,
                            "scheme": object_store_scheme(&target),
                            "format": format,
                            "row_count": ds.row_count(),
                        }));
                    }
                    Err(e) => {
                        sink_results.push(serde_json::json!({
                            "kind": "object_store",
                            "status": "error",
                            "error_code": "OBJECT_STORE_SINK_FAILED",
                            "uri": target,
                            "error": e.to_string(),
                        }));
                    }
                }
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
    if !object_store_source_results.is_empty() {
        out["object_store_source_results"] = serde_json::Value::Array(object_store_source_results);
    }
    if !db_source_results.is_empty() {
        out["db_source_results"] = serde_json::Value::Array(db_source_results);
    }
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
        assert!(
            sinks[0]["status"].as_str() == Some("ok") || sinks[0]["status"].as_str() == Some("error"),
            "delta_lake: {:?}",
            sinks[0]
        );
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
        assert!(
            sinks[0]["status"].as_str() == Some("ok") || sinks[0]["status"].as_str() == Some("error"),
            "delta_lake: {:?}",
            sinks[0]
        );
    }

    /// Same pipeline as `docs/java/examples/PlatformConnectorsPipelineExample.java`.
    #[test]
    fn run_pipeline_platform_connectors_committed_fixture() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let bundle = PipelineBundle::from_repo_fixture("cloud_connectors");
        let root = bundle.root();
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let stage = std::env::temp_dir().join(format!("rdp_connectors_stage_{stamp}"));
        let delta_wh = std::env::temp_dir().join(format!("rdp_connectors_delta_{stamp}"));
        std::fs::create_dir_all(&stage).unwrap();
        std::fs::create_dir_all(&delta_wh).unwrap();

        let file_base = root.to_string_lossy().into_owned();
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/platform_connectors.pipeline.json",
                &HashMap::from([
                    ("FILE_BASE".into(), file_base),
                    ("STAGE_BASE".into(), stage.to_string_lossy().into_owned()),
                    ("DELTA_WH".into(), delta_wh.to_string_lossy().into_owned()),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"].as_i64(), Some(6));
        let os = v["object_store_source_results"].as_array().unwrap();
        assert_eq!(os.len(), 3);
        assert_eq!(os[0]["status"].as_str(), Some("ok"));
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks.len(), 6);
        assert_eq!(sinks[0]["kind"].as_str(), Some("snowflake"));
        assert_eq!(sinks[0]["status"].as_str(), Some("ok"));
        assert_eq!(sinks[2]["kind"].as_str(), Some("spark"));
        assert_eq!(sinks[2]["status"].as_str(), Some("ok"));
        let _ = std::fs::remove_dir_all(&stage);
        let _ = std::fs::remove_dir_all(&delta_wh);
    }

    /// Same pipeline as `docs/java/examples/ObjectStoreUrlsExample.java`.
    #[test]
    fn run_pipeline_object_store_sources_with_local_parquet_sink() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let bundle = PipelineBundle::from_repo_fixture("cloud_connectors");
        let root = bundle.root();
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("rdp_object_store_src_test_{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let sink = dir.join("out.parquet");

        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/object_store_sources_only.pipeline.json",
                &HashMap::from([
                    ("FILE_BASE".into(), root.to_string_lossy().into_owned()),
                    ("SINK_PATH".into(), sink.to_string_lossy().into_owned()),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"].as_i64(), Some(6));
        assert_eq!(v["object_store_source_results"].as_array().unwrap().len(), 3);
        assert!(sink.exists());
        let _ = std::fs::remove_dir_all(&dir);
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

    /// Same pipeline as `docs/java/examples/DataFrameCentricPipeline.java`.
    #[test]
    fn run_pipeline_dataframe_centric_sql_committed_fixture() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
        let json_input = repo.join("tests/fixtures/jvm_contract_three_rows.json");
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let out = std::env::temp_dir().join(format!("rdp_dataframe_centric_{stamp}.parquet"));

        let bundle = PipelineBundle::from_repo_fixture("jvm_contract");
        let payload = bundle
            .resolve_pipeline_json(
                "pipelines/dataframe_centric_sql.pipeline.json",
                &HashMap::from([
                    (
                        "SOURCE_PATH".into(),
                        json_input.to_string_lossy().into_owned(),
                    ),
                    (
                        "SINK_PATH".into(),
                        out.to_string_lossy().into_owned(),
                    ),
                ]),
            )
            .unwrap();

        let v = run_pipeline_impl(&payload).unwrap();
        assert_eq!(v["ingested_row_count"].as_i64(), Some(3));
        let sinks = v["sink_results"].as_array().unwrap();
        assert_eq!(sinks.len(), 1);
        assert_eq!(sinks[0]["kind"].as_str(), Some("parquet_file"));
        assert_eq!(sinks[0]["status"].as_str(), Some("ok"));
        assert_eq!(sinks[0]["row_count"].as_i64(), Some(2));
        assert!(out.is_file());
        let _ = std::fs::remove_file(out);
    }

    /// Same two-stage pipeline as `docs/java/examples/GhcnJsonXmlParquetPipeline.java`.
    #[test]
    fn run_pipeline_ghcn_json_xml_parquet_committed_fixture() {
        use rust_data_processing::pipeline_spec::PipelineBundle;
        use std::collections::HashMap;

        let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
        let json_input = repo.join("tests/fixtures/ghcn/ghcn_stations_sample.json");
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let work = std::env::temp_dir().join(format!("rdp_ghcn_pipeline_{stamp}"));
        std::fs::create_dir_all(&work).unwrap();
        let xml_out = work.join("stations.xml");
        let parquet_out = work.join("stations.parquet");

        let bundle = PipelineBundle::from_repo_fixture("ghcn");
        let json_to_xml = bundle
            .resolve_pipeline_json(
                "pipelines/json_to_xml.pipeline.json",
                &HashMap::from([
                    (
                        "SOURCE_PATH".into(),
                        json_input.to_string_lossy().into_owned(),
                    ),
                    (
                        "SINK_PATH".into(),
                        xml_out.to_string_lossy().into_owned(),
                    ),
                ]),
            )
            .unwrap();
        let v1 = run_pipeline_impl(&json_to_xml).unwrap();
        let xml_sink = &v1["sink_results"].as_array().unwrap()[0];
        assert_eq!(xml_sink["kind"].as_str(), Some("xml_file"));
        assert_eq!(xml_sink["status"].as_str(), Some("ok"));
        assert_eq!(xml_sink["row_count"].as_i64(), Some(5));
        assert!(xml_out.is_file());

        let xml_to_parquet = bundle
            .resolve_pipeline_json(
                "pipelines/xml_to_parquet.pipeline.json",
                &HashMap::from([
                    (
                        "SOURCE_PATH".into(),
                        xml_out.to_string_lossy().into_owned(),
                    ),
                    (
                        "SINK_PATH".into(),
                        parquet_out.to_string_lossy().into_owned(),
                    ),
                ]),
            )
            .unwrap();
        let v2 = run_pipeline_impl(&xml_to_parquet).unwrap();
        let parquet_sink = &v2["sink_results"].as_array().unwrap()[0];
        assert_eq!(parquet_sink["kind"].as_str(), Some("parquet_file"));
        assert_eq!(parquet_sink["status"].as_str(), Some("ok"));
        assert_eq!(parquet_sink["row_count"].as_i64(), Some(5));
        assert!(parquet_out.is_file());

        let _ = std::fs::remove_dir_all(work);
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

    #[test]
    fn run_pipeline_rejects_jdbc_url_in_db_reads() {
        let payload = serde_json::json!({
            "pipeline_spec_version": 1,
            "sources": {
                "paths": [],
                "db_reads": [{
                    "url": "jdbc:oracle:thin:@//db01.example.com:1521/ORCLPDB1",
                    "query": "SELECT 1 FROM dual"
                }],
                "schema": {
                    "fields": [
                        {"name": "id", "data_type": "Int64"}
                    ]
                },
                "options": {}
            },
            "sinks": [
                {"kind": "parquet_file", "path": "/tmp/out.parquet"}
            ]
        });
        let err = run_pipeline_impl(&payload.to_string()).unwrap_err();
        assert!(matches!(
            err,
            PipelineErr::Structured {
                code: "DB_SOURCE_URL_INVALID",
                ..
            }
        ));
    }

    #[cfg(not(feature = "db_connectorx"))]
    #[test]
    fn run_pipeline_db_reads_requires_db_connectorx_feature() {
        let payload = serde_json::json!({
            "pipeline_spec_version": 1,
            "sources": {
                "paths": [],
                "db_reads": [{
                    "url": "oracle://etl_user:pass@db01.example.com:1521/ORCLPDB1",
                    "query": "SELECT 1 FROM dual"
                }],
                "schema": {
                    "fields": [
                        {"name": "id", "data_type": "Int64"}
                    ]
                },
                "options": {}
            },
            "sinks": [
                {"kind": "parquet_file", "path": "/tmp/out.parquet"}
            ]
        });
        let err = run_pipeline_impl(&payload.to_string()).unwrap_err();
        assert!(matches!(
            err,
            PipelineErr::Structured {
                code: "DB_CONNECTORX_NOT_BUILT",
                ..
            }
        ));
    }
}
