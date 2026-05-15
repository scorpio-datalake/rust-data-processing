//! Ingestion entrypoints and implementations.
//!
//! Most callers should use [`ingest_from_path`] (from [`unified`]) which:
//!
//! - auto-detects format by file extension (or you can override via [`IngestionOptions`])
//! - performs ingestion into an in-memory [`crate::types::DataSet`]
//! - optionally reports success/failure/alerts to an [`IngestionObserver`]
//!
//! For **append-only ordered batches**, use [`ingest_from_ordered_paths`] (concatenate rows, then
//! apply the watermark filter once). For stable directory listings, see [`paths_from_directory_scan`]
//! and [`partition`] module docs on deterministic ordering.
//!
//! For ergonomic configuration, prefer [`IngestionOptionsBuilder`] over constructing
//! [`IngestionOptions`] directly.
//!
//! Format-specific functions are also available under:
//! - [`csv`]
//! - [`excel`]
//! - [`json`]
//! - [`parquet`]
//! - [`xml`]

pub mod builder;
pub mod csv;
#[cfg(feature = "excel")]
pub mod excel;
#[cfg(not(feature = "excel"))]
pub mod excel {
    //! Excel ingestion stubs when the `excel` feature is disabled.
    //!
    //! This keeps the public module path stable (`rust_data_processing::ingestion::excel`)
    //! while avoiding pulling Excel dependencies into the default build.

    use std::path::Path;

    use crate::error::{IngestionError, IngestionResult};
    use crate::types::{DataSet, Schema};

    fn disabled() -> IngestionError {
        IngestionError::SchemaMismatch {
            message: "excel ingestion is disabled; enable Cargo feature 'excel'".to_string(),
        }
    }

    pub fn ingest_excel_from_path(
        _path: impl AsRef<Path>,
        _sheet_name: Option<&str>,
        _schema: &Schema,
    ) -> IngestionResult<DataSet> {
        Err(disabled())
    }

    pub fn ingest_excel_workbook_from_path(
        _path: impl AsRef<Path>,
        _sheet_names: Option<&[&str]>,
        _schema: &Schema,
    ) -> IngestionResult<DataSet> {
        Err(disabled())
    }

    pub fn infer_excel_schema_from_path(
        _path: impl AsRef<Path>,
        _sheet_name: Option<&str>,
    ) -> IngestionResult<Schema> {
        Err(disabled())
    }

    pub fn infer_excel_schema_workbook_from_path(
        _path: impl AsRef<Path>,
        _sheet_names: Option<&[&str]>,
    ) -> IngestionResult<Schema> {
        Err(disabled())
    }
}
#[cfg(feature = "db_connectorx")]
pub mod db;
pub mod json;
pub mod parquet;
pub mod xml;
pub mod partition;
#[cfg(not(feature = "db_connectorx"))]
pub mod db {
    //! Direct DB ingestion stubs when `db_connectorx` is disabled.
    //!
    //! Enable with `--features db_connectorx` plus a source, e.g. `--features db_mysql`.

    use crate::error::{IngestionError, IngestionResult};
    use crate::types::{DataSet, Schema};

    fn disabled() -> IngestionError {
        IngestionError::SchemaMismatch {
            message: "db ingestion is disabled; enable Cargo feature 'db_connectorx'".to_string(),
        }
    }

    pub fn ingest_from_db(
        _conn: &str,
        _query: &str,
        _schema: &Schema,
        _options: &super::IngestionOptions,
    ) -> IngestionResult<DataSet> {
        Err(disabled())
    }

    pub fn ingest_from_db_infer(
        _conn: &str,
        _query: &str,
        _options: &super::IngestionOptions,
    ) -> IngestionResult<DataSet> {
        Err(disabled())
    }
}
pub mod observability;
pub(crate) mod polars_bridge;
pub mod unified;
pub mod watermark;

pub use builder::IngestionOptionsBuilder;
pub use observability::{
    CompositeObserver, FileObserver, IngestionContext, IngestionObserver, IngestionSeverity,
    IngestionStats, StdErrObserver,
};
pub use partition::{
    PartitionSegment, PartitionedFile, discover_hive_partitioned_files,
    hive_segments_for_relative_parent, parse_partition_segment, paths_from_directory_scan,
    paths_from_explicit_list, paths_from_glob,
};
pub use unified::{
    ExcelSheetSelection, IngestionFormat, IngestionOptions, IngestionRequest,
    OrderedBatchIngestMetadata, export_dataset_to_arrow_ipc, export_dataset_to_parquet,
    export_dataset_to_xml,
    infer_schema_from_path, ingest_from_ordered_paths, ingest_from_path, ingest_from_path_infer,
};
pub use watermark::{
    apply_watermark_after_ingest, apply_watermark_filter, max_value_in_column,
    validate_watermark_config,
};

pub use db::{ingest_from_db, ingest_from_db_infer};
