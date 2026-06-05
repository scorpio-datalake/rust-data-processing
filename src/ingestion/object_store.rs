//! Cloud and local object storage ingest/export via the [`object_store`] crate.
//!
//! URIs: `s3://`, `gs://`/`gcs://`, `abfss://`/`abfs://`, `azure://`/`az://`, `https://` (Azure/AWS),
//! and `file://` (for tests and local staging).

use std::io::Read;
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

/// `object_store::parse_url_opts` builds from [`AmazonS3Builder::new`] and only applies the
/// options iterator — it does not read `AWS_*` from the environment unless those pairs are
/// passed explicitly (see upstream docs on `parse_url_opts`).
fn object_store_url_options() -> Vec<(String, String)> {
    let azure_emulator = std::env::var("AZURE_ENDPOINT")
        .ok()
        .is_some_and(|e| e.contains("127.0.0.1") || e.contains("localhost"));
    let mut opts: Vec<(String, String)> = std::env::vars()
        .filter(|(key, _)| {
            if key.starts_with("AWS_") {
                return true;
            }
            if key.starts_with("AZURE_") {
                // Wrong/truncated keys break Azurite; object_store uses EMULATOR_ACCOUNT_KEY.
                if azure_emulator
                    && (key.as_str() == "AZURE_STORAGE_CONNECTION_STRING"
                        || key.as_str() == "AZURE_STORAGE_ACCOUNT_KEY")
                {
                    return false;
                }
                return true;
            }
            key.as_str() == "google_base_url"
        })
        .collect();
    if let Ok(url) = std::env::var("gcs_base_url").or_else(|_| std::env::var("STORAGE_EMULATOR_HOST"))
    {
        opts.push(("google_base_url".into(), url));
        opts.push(("google_skip_signature".into(), "true".into()));
    }
    if azure_emulator {
        opts.push(("azure_storage_use_emulator".into(), "true".into()));
        opts.push(("azure_storage_account_name".into(), "devstoreaccount1".into()));
    }
    opts
}

fn gcs_emulator_base_url() -> Option<String> {
    std::env::var("gcs_base_url")
        .ok()
        .or_else(|| std::env::var("STORAGE_EMULATOR_HOST").ok())
}

fn parse_gs_uri(uri: &str) -> Option<(String, String)> {
    let url = Url::parse(uri).ok()?;
    if url.scheme() != "gs" && url.scheme() != "gcs" {
        return None;
    }
    let bucket = url.host_str()?.to_string();
    let object = url.path().trim_start_matches('/').to_string();
    if bucket.is_empty() || object.is_empty() {
        return None;
    }
    Some((bucket, object))
}

fn url_encode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// fake-gcs-server implements the JSON API; object_store uses XML PUT which returns 405.
fn gcs_emulator_get_bytes(base: &str, bucket: &str, object: &str) -> IngestionResult<Vec<u8>> {
    let url = format!(
        "{}/storage/v1/b/{}/o/{}?alt=media",
        base.trim_end_matches('/'),
        url_encode(bucket),
        url_encode(object)
    );
    let resp = ureq::get(&url).call().map_err(|e| IngestionError::Engine {
        message: format!("gcs emulator get gs://{bucket}/{object}"),
        source: Box::new(e),
    })?;
    if resp.status() != 200 {
        return Err(IngestionError::Engine {
            message: format!(
                "gcs emulator get gs://{bucket}/{object}: HTTP {}",
                resp.status()
            ),
            source: Box::new(std::io::Error::other(format!("HTTP {}", resp.status()))),
        });
    }
    let mut body = Vec::new();
    resp.into_reader()
        .read_to_end(&mut body)
        .map_err(|e| IngestionError::Engine {
            message: format!("gcs emulator read body gs://{bucket}/{object}"),
            source: Box::new(e),
        })?;
    Ok(body)
}

fn gcs_emulator_put_bytes(base: &str, bucket: &str, object: &str, body: &[u8]) -> IngestionResult<()> {
    let url = format!(
        "{}/upload/storage/v1/b/{}/o?uploadType=media&name={}",
        base.trim_end_matches('/'),
        url_encode(bucket),
        url_encode(object)
    );
    let resp = ureq::post(&url)
        .send(body)
        .map_err(|e| IngestionError::Engine {
            message: format!("gcs emulator put gs://{bucket}/{object}"),
            source: Box::new(e),
        })?;
    if !(200..300).contains(&resp.status()) {
        return Err(IngestionError::Engine {
            message: format!(
                "gcs emulator put gs://{bucket}/{object}: HTTP {}",
                resp.status()
            ),
            source: Box::new(std::io::Error::other(format!("HTTP {}", resp.status()))),
        });
    }
    Ok(())
}

/// Parse a URI into an [`ObjectStore`] and object path.
pub fn resolve_object_store_uri(uri: &str) -> IngestionResult<(Arc<dyn ObjectStore>, ObjectPath)> {
    let url = Url::parse(uri).map_err(|e| IngestionError::SchemaMismatch {
        message: format!("invalid object-store URI `{uri}`: {e}"),
    })?;
    let opts = object_store_url_options();
    let (store, path) = object_store::parse_url_opts(
        &url,
        opts.iter().map(|(k, v)| (k.as_str(), v.as_str())),
    )
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
    let bytes = if let (Some(base), Some((bucket, object))) =
        (gcs_emulator_base_url(), parse_gs_uri(uri))
    {
        gcs_emulator_get_bytes(&base, &bucket, &object)?
    } else {
        block_on(async {
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
        })?
        .to_vec()
    };

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
    if let (Some(base), Some((bucket, object))) = (gcs_emulator_base_url(), parse_gs_uri(uri)) {
        gcs_emulator_put_bytes(&base, &bucket, &object, &bytes)?;
    } else {
        block_on(async {
            store
                .put(&object_path, bytes.into())
                .await
                .map_err(|e| IngestionError::Engine {
                    message: format!("object store put `{uri}`"),
                    source: Box::new(e),
                })
        })?;
    }
    Ok(())
}
