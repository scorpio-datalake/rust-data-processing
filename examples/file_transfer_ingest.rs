//! Example: ingest over SFTP or FTP (requires `--features cloud_connectors`).
//!
//! Integration tests use the same Rust API via pipeline `file_transfer_uris`:
//! `integration_testing/CloudConnectors/` + `integration_testing/scripts/cloud_pipeline.py`.
//!
//! ```bash
//! cargo run --features cloud_connectors --example file_transfer_ingest -- \
//!   'ftp://etl_user:PASS@127.0.0.1:21/rdp/incoming/data.json'
//! ```
//!
//! Prefer env for secrets: `FTP_PASSWORD`, `SFTP_PASSWORD`, `SFTP_PRIVATE_KEY_PATH`.

use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, ingest_from_file_transfer_uri,
};
use rust_data_processing::types::{DataType, Field, Schema};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let uri = std::env::args().nth(1).unwrap_or_else(|| {
        eprintln!(
            "usage: file_transfer_ingest <sftp://|ftp://|ftps://...>\n\
                 env: FTP_PASSWORD, SFTP_PASSWORD, SFTP_PRIVATE_KEY_PATH"
        );
        std::process::exit(2);
    });

    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
    ]);
    let mut opts = IngestionOptions::default();
    if uri.ends_with(".json") || uri.contains(".json?") {
        opts.format = Some(IngestionFormat::Json);
    } else if uri.ends_with(".parquet") {
        opts.format = Some(IngestionFormat::Parquet);
    }

    let ds = ingest_from_file_transfer_uri(&uri, &schema, &opts)?;
    println!("rows={}", ds.row_count());
    Ok(())
}
