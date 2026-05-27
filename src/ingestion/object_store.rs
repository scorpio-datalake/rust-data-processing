//! Cloud and local object storage ingest/export via the [`object_store`] crate.
//!
//! URIs: `s3://`, `gs://`/`gcs://`, `abfss://`/`abfs://`, `azure://`/`az://`, `https://` (Azure/AWS),
//! and `file://` (for tests and local staging).

use std::path::{Path, PathBuf};
use std::sync::Arc;

use object_store::path::Path as ObjectPath;
use object_store::{ObjectStore, ObjectStoreExt};
use url::Url;

use crate::error::{IngestionError, IngestionResult};
use crate::types::{DataSet, Schema};

use super::{IngestionFormat, IngestionOptions, export_dataset_to_parquet, ingest_from_path};

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("tokio current-thread runtime")
        .block_on(f)
}

/// Parse a URI into an [`ObjectStore`] and object path.
pub fn resolve_object_store_uri(uri: &str) -> IngestionResult<(Arc<dyn ObjectStore>, ObjectPath)> {
    let url = Url::parse(uri).map_err(|e| IngestionError::SchemaMismatch {
        message: format!("invalid object-store URI `{uri}`: {e}"),
    })?;
    let (store, path) = object_store::parse_url_opts(&url, std::iter::empty::<(&str, &str)>())
        .map_err(|e| IngestionError::SchemaMismatch {
            message: format!("invalid object-store URI `{uri}`: {e}"),
        })?;
    Ok((Arc::from(store), path))
}

fn infer_format_from_object_path(
    path: &ObjectPath,
    options: &IngestionOptions,
) -> IngestionResult<IngestionFormat> {
    if let Some(f) = options.format {
        return Ok(f);
    }
    let s = path.as_ref();
    let ext = Path::new(s)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("");
    match ext.to_ascii_lowercase().as_str() {
        "csv" => Ok(IngestionFormat::Csv),
        "json" | "ndjson" => Ok(IngestionFormat::Json),
        "parquet" => Ok(IngestionFormat::Parquet),
        "xml" => Ok(IngestionFormat::Xml),
        _ => Err(IngestionError::SchemaMismatch {
            message: format!(
                "cannot infer ingest format from object path `{s}`; set sources.options.format"
            ),
        }),
    }
}

fn temp_download_path(suffix: &str) -> IngestionResult<PathBuf> {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    Ok(std::env::temp_dir().join(format!("rdp_os_dl_{stamp}{suffix}")))
}

/// Download an object and ingest into a [`DataSet`] using the same path-based readers as local files.
pub fn ingest_from_object_store_uri(
    uri: &str,
    schema: &Schema,
    options: &IngestionOptions,
) -> IngestionResult<DataSet> {
    let (store, object_path) = resolve_object_store_uri(uri)?;
    let fmt = infer_format_from_object_path(&object_path, options)?;
    let suffix = match fmt {
        IngestionFormat::Csv => ".csv",
        IngestionFormat::Json => ".json",
        IngestionFormat::Parquet => ".parquet",
        IngestionFormat::Xml => ".xml",
        IngestionFormat::Excel => {
            return Err(IngestionError::SchemaMismatch {
                message: "excel ingest from object store is not supported".to_string(),
            });
        }
    };

    let local = temp_download_path(suffix)?;
    let bytes = block_on(async {
        let result = store
            .get(&object_path)
            .await
            .map_err(|e| IngestionError::Engine {
                message: format!("object store get `{uri}`"),
                source: Box::new(e),
            })?;
        result.bytes().await.map_err(|e| IngestionError::Engine {
            message: format!("object store read bytes `{uri}`"),
            source: Box::new(e),
        })
    })?;

    std::fs::write(&local, &bytes).map_err(IngestionError::Io)?;
    let mut opts = options.clone();
    opts.format = Some(fmt);
    let ds = ingest_from_path(&local, schema, &opts)?;
    let _ = std::fs::remove_file(&local);
    Ok(ds)
}

/// Write a [`DataSet`] as a single Parquet object at `uri`.
pub fn export_dataset_to_object_store_uri(uri: &str, ds: &DataSet) -> IngestionResult<()> {
    let (store, object_path) = resolve_object_store_uri(uri)?;
    let local = temp_download_path(".parquet")?;
    export_dataset_to_parquet(&local, ds)?;
    let bytes = std::fs::read(&local).map_err(IngestionError::Io)?;
    let _ = std::fs::remove_file(&local);
    block_on(async {
        store
            .put(&object_path, bytes.into())
            .await
            .map_err(|e| IngestionError::Engine {
                message: format!("object store put `{uri}`"),
                source: Box::new(e),
            })
    })?;
    Ok(())
}
