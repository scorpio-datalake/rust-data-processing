//! Rust Oracle integration test — RDP pipeline import + RDP ingest_from_db verify.

use rdp_oracle_integration_test::rdp_pipeline::{import_csv_oracle, verify_count_oracle};
use std::path::PathBuf;

fn csv_path() -> PathBuf {
    let root = std::env::var("RDP_INTEGRATION_ROOT").unwrap_or_else(|_| "integration_testing".into());
    let sample = PathBuf::from(&root).join("data/uber_nyc_pickups_sample.csv");
    let full = PathBuf::from(&root).join("data/uber_nyc_pickups_apr2014.csv");
    if sample.is_file() {
        sample
    } else {
        full
    }
}

fn max_rows() -> usize {
    std::env::var("INTEG_MAX_IMPORT_ROWS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500)
}

#[test]
fn oracle_import_uber_csv() {
    if std::env::var("RUN_ORACLE_INTEGRATION").ok().as_deref() != Some("1") {
        eprintln!("skip oracle_import_uber_csv (set RUN_ORACLE_INTEGRATION=1)");
        return;
    }
    let url = std::env::var("ORACLE_CONNECT_URL").expect("ORACLE_CONNECT_URL");
    let csv = csv_path();
    assert!(csv.is_file(), "missing Uber CSV at {}", csv.display());

    let (ingested, loaded) = import_csv_oracle(&csv, &url, max_rows()).expect("RDP import pipeline");
    assert!(ingested > 0, "no rows ingested from csv");
    assert_eq!(loaded, ingested);

    verify_count_oracle(&url, ingested).expect("RDP verify count");
}
