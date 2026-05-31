//! Rust Oracle integration test — uses `rust-data-processing` lib (db_connectorx) from the repo / crates.io build.

use rdp_oracle_integration_test::oracle_load::{
    ingest_uber_csv, load_dataset, reset_table, verify_row_count,
};
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

    reset_table(&url).expect("reset table");

    let ds = ingest_uber_csv(&csv).expect("ingest csv");
    let mut rows: Vec<_> = ds.rows.into_iter().take(max_rows()).collect();
    let expected = rows.len();
    assert!(expected > 0, "no rows ingested from csv");

    let truncated = rust_data_processing::types::DataSet::new(ds.schema.clone(), rows);
    let loaded = load_dataset(&url, &truncated).expect("load dataset");
    assert_eq!(loaded, expected);

    verify_row_count(&url, expected).expect("verify count");
}
