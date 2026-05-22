//! Snowflake load: write Parquet to a stage URI (S3 / GCS / ABFS / `file://`), then optional `COPY INTO`.
//!
//! Automatic `COPY INTO` requires `SNOWFLAKE_USER` / `SNOWFLAKE_PASSWORD` and a future native driver;
//! today Rust always lands data on the stage via [`export_dataset_to_object_store_uri`].

use crate::error::{IngestionError, IngestionResult};
use crate::types::DataSet;

use super::object_store::export_dataset_to_object_store_uri;

/// Write `ds` as Parquet to `stage_uri` (object-store URI Rust can write).
pub fn write_dataset_to_snowflake_stage(
    stage_uri: &str,
    ds: &DataSet,
) -> IngestionResult<usize> {
    let rows = ds.row_count();
    export_dataset_to_object_store_uri(stage_uri, ds)?;
    Ok(rows)
}

/// Optional `COPY INTO` — not linked in-tree; stage write is the supported path.
pub fn copy_into_table_from_stage(
    account_url: &str,
    warehouse: Option<&str>,
    database: Option<&str>,
    schema: Option<&str>,
    table: &str,
    stage_uri: &str,
    role: Option<&str>,
) -> IngestionResult<()> {
    let _ = (account_url, warehouse, database, schema, table, stage_uri, role);
    if std::env::var("SNOWFLAKE_USER").is_ok() && std::env::var("SNOWFLAKE_PASSWORD").is_ok() {
        return Err(IngestionError::SchemaMismatch {
            message: "SNOWFLAKE_USER/PASSWORD are set but in-tree COPY INTO is not linked yet; run COPY FROM the staged Parquet in Snowflake SQL or add snowflake driver in a future release".to_string(),
        });
    }
    Err(IngestionError::SchemaMismatch {
        message: "COPY INTO skipped: set SNOWFLAKE_USER and SNOWFLAKE_PASSWORD to enable (driver not linked in this build)".to_string(),
    })
}
