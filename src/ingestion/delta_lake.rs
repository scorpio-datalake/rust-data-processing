//! Delta / Databricks table staging: write Parquet under `warehouse/namespace/table/` via [`object_store`](super::object_store).
//!
//! Full Delta transaction-log commits (ACID, time travel) need a `deltalake` crate aligned with this
//! crate's Arrow/Polars versions — tracked separately. Today Rust lands **Parquet parts** at the table
//! location your `s3://` / `abfss://` / `file://` warehouse URI describes.

use crate::error::IngestionResult;
use crate::types::DataSet;

use super::object_store::export_dataset_to_object_store_uri;

/// Build a table root URI from warehouse + optional namespace + table name.
pub fn delta_table_uri(
    warehouse: &str,
    namespace: Option<&str>,
    table: &str,
) -> String {
    let base = warehouse.trim_end_matches('/');
    let table_path = table.trim_start_matches('/');
    match namespace.filter(|n| !n.is_empty()) {
        Some(ns) => {
            let ns_path = ns.replace('.', "/");
            format!("{base}/{ns_path}/{table_path}/")
        }
        None => format!("{base}/{table_path}/"),
    }
}

fn parquet_part_uri(table_uri: &str) -> String {
    if table_uri.ends_with('/') {
        format!("{table_uri}part-rdp-000.parquet")
    } else {
        format!("{table_uri}/part-rdp-000.parquet")
    }
}

/// Write `ds` as Parquet under the Delta table path (object-store URI).
pub fn write_dataset_to_delta_table(table_uri: &str, ds: &DataSet) -> IngestionResult<usize> {
    let rows = ds.row_count();
    let part = parquet_part_uri(table_uri);
    export_dataset_to_object_store_uri(&part, ds)?;
    Ok(rows)
}
