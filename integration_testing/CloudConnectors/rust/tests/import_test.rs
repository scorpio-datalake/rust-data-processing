use rdp_cloud_integration_test::rdp_pipeline::{verify_file_transfer_import, verify_object_store_roundtrip};
use std::path::PathBuf;

fn csv_path() -> PathBuf {
    let root = std::env::var("RDP_INTEGRATION_ROOT").unwrap_or_else(|_| "integration_testing".into());
    let sample = PathBuf::from(&root).join("data/uber_nyc_pickups_sample.csv");
    if sample.is_file() {
        sample
    } else {
        PathBuf::from(&root).join("data/uber_nyc_pickups_apr2014.csv")
    }
}

fn max_rows() -> usize {
    std::env::var("INTEG_MAX_IMPORT_ROWS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500)
}

#[test]
fn cloud_import_uber_csv() {
    if std::env::var("RUN_CLOUD_INTEGRATION").ok().as_deref() != Some("1") {
        eprintln!("skip cloud_import_uber_csv");
        return;
    }
    let csv = csv_path();
    assert!(csv.is_file(), "missing Uber CSV at {}", csv.display());
    let rows = max_rows();
    for protocol in ["s3", "gcs", "azure"] {
        verify_object_store_roundtrip(protocol, &csv, rows)
            .unwrap_or_else(|e| panic!("{protocol} roundtrip: {e}"));
    }
    for protocol in ["sftp", "ftp"] {
        verify_file_transfer_import(protocol)
            .unwrap_or_else(|e| panic!("{protocol} import: {e}"));
    }
}
